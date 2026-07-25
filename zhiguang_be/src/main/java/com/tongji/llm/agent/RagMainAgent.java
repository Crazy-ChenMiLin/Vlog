package com.tongji.llm.agent;

import com.tongji.llm.DTO.RagRetrievalResultDTO;
import com.tongji.llm.agent.model.EvidenceAction;
import com.tongji.llm.agent.model.EvidenceResult;
import com.tongji.llm.agent.model.QuestionType;
import com.tongji.llm.agent.model.RagAgentPlan;
import com.tongji.llm.agent.model.RagAgentState;
import com.tongji.llm.agent.model.RagAgentStepTrace;
import com.tongji.llm.enhanceService.RerankService;
import com.tongji.llm.graphService.MainService;
import com.tongji.llm.graphService.model.GraphContext;
import com.tongji.llm.searchService.RagRetrievalOptions;
import com.tongji.llm.searchService.RagRetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.function.Supplier;

/**
 * RAG 主 Agent，也就是这条问答链路的“main 函数”。
 *
 * <p>这版没有引入 LangGraph4j，而是先用 Spring 服务手写一个轻量状态机：
 * Plan 决定本轮要开什么工具，State 保存执行结果，EvidenceCheck 判断证据够不够，
 * Trace 记录实际走过的步骤。这样代码结构先贴近 LangGraph 的状态/节点/边思想，
 * 但不会在第一版引入额外框架复杂度。</p>
 *
 * <p>注意：Graph 不是最后才补充。对于关系/对比类问题，Planner 会打开 graph_trace，
 * MainAgent 会先查 Neo4j，再把图谱线索带入后续检索、rerank 和最终回答。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagMainAgent {
    private final AgentPlannerService plannerService;
    private final EvidenceCheckService evidenceCheckService;
    private final MainService graphService;
    private final RagRetrievalService retrievalService;
    private final RerankService rerankService;
    private final ChatClient chatClient;

    public RagAgentState run(String scope, Long postId, String originalQuestion, String standaloneQuestion, int topK) {
        String effectiveQuestion = StringUtils.hasText(standaloneQuestion) ? standaloneQuestion.trim() : originalQuestion;
        RagAgentState state = new RagAgentState(originalQuestion, effectiveQuestion, topK);

        // Plan 是事前路线图：让 LLM 判断问题类型和工具开关，而不是用大量关键词规则硬判。
        RagAgentPlan plan = timed(state, "plan", "PLANNER", () -> plannerService.plan(effectiveQuestion, topK));
        state.plan(plan);
        state.currentTopK(plan.initialTopK());
        state.addStep(new RagAgentStepTrace("plan_result", plan.retrievalMode().name(), true, 0, plan.reason()));

        // 闲聊/无需知识库的问题直接回答，避免“你好”也走 ES、向量、图谱这一整套重链路。
        if (plan.questionType() == QuestionType.CHAT || plan.needDirectAnswer()) {
            directAnswer(state, originalQuestion);
            return state;
        }

        // 关系型问题前置查图谱：Neo4j 返回的是 trace/线索，用来增强后面的检索和重排。
        if (plan.needGraphTrace()) {
            GraphContext graphContext = timed(state, "graph_trace", "QUERY_NEO4J", () -> graphService.build(effectiveQuestion));
            state.graphContext(graphContext);
            state.addStep(new RagAgentStepTrace(
                    "graph_trace_result",
                    graphContext.isEmpty() ? "GRAPH_MISS" : "GRAPH_HIT",
                    true,
                    0,
                    "relations=" + graphContext.relations().size() + ", entities=" + graphContext.matchedEntities().size()
            ));
        }

        executeRetrievalRound(state, scope, postId);
        EvidenceResult evidence = checkEvidence(state);
        state.evidenceResult(evidence);

        // Agent 的“观察后行动”只做一轮：top5 证据不足时扩大到 top10，避免无限重试。
        if (!evidence.sufficient()
                && evidence.suggestedAction() == EvidenceAction.EXPAND_TOP_K
                && state.retryCount() == 0
                && state.currentTopK() < 10) {
            state.incrementRetryCount();
            state.currentTopK(Math.min(10, Math.max(state.currentTopK() + 1, 10)));
            state.addStep(new RagAgentStepTrace("retry", "EXPAND_TOP_K", true, 0, "Expand topK to " + state.currentTopK()));
            executeRetrievalRound(state, scope, postId);
            state.evidenceResult(checkEvidence(state));
        }

        log.info("RAG main agent traceId={} questionType={} mode={} topK={} retry={} evidence={} steps={}",
                state.traceId(),
                state.plan().questionType(),
                state.plan().retrievalMode(),
                state.currentTopK(),
                state.retryCount(),
                state.evidenceResult(),
                state.steps());
        return state;
    }

    private void directAnswer(RagAgentState state, String originalQuestion) {
        String answer = timed(state, "direct_answer", "LLM_DIRECT", () -> chatClient
                .prompt()
                .system("你是中文助手。用户的问题不需要知识库检索时，直接简洁回答；如果涉及实时信息，请说明无法确认实时状态。")
                .user(originalQuestion)
                .options(OpenAiChatOptions.builder()
                        .temperature(0.2)
                        .build())
                .call()
                .content());
        state.finalAnswer(answer);
    }

    private void executeRetrievalRound(RagAgentState state, String scope, Long postId) {
        RagRetrievalOptions options = options(state.plan(), state.graphContext());
        RagRetrievalResultDTO retrieval = timed(state, "retrieve", "TOP" + state.currentTopK(), () -> {
            if ("post".equalsIgnoreCase(scope)) {
                return retrievalService.retrieveForPost(postId, state.standaloneQuestion(), state.currentTopK(), options);
            }
            return retrievalService.retrieveGlobal(state.standaloneQuestion(), state.currentTopK(), options);
        });
        state.retrievalResult(retrieval);
        if (state.graphContext().isEmpty() && !retrieval.graphContext().isEmpty()) {
            state.graphContext(retrieval.graphContext());
        }

        List<Document> reranked = state.plan().needRerank()
                ? timed(state, "rerank", "TOP" + state.currentTopK(), () -> rerankService.rerank(
                state.standaloneQuestion(),
                retrieval.fusedDocs(),
                state.currentTopK(),
                retrieval.graphContext()
        ))
                : retrieval.fusedDocs().stream().limit(state.currentTopK()).toList();

        if (reranked == null) {
            reranked = retrieval.fusedDocs().stream().limit(state.currentTopK()).toList();
        }
        state.rerankedDocs(reranked);
        // 扩大 topK 是为了捞更多候选，最终给回答模型的上下文仍控制在前 5 个，避免噪声过多。
        state.answerDocs(reranked.stream().limit(Math.min(5, state.currentTopK())).toList());
    }

    private EvidenceResult checkEvidence(RagAgentState state) {
        // EvidenceCheck 只判断“现有证据够不够支撑回答”，不直接生成最终答案。
        return timed(state, "evidence_check", "CHECK_TOP" + state.currentTopK(), () -> evidenceCheckService.check(
                state.standaloneQuestion(),
                state.answerDocs(),
                state.graphContext(),
                state.currentTopK(),
                state.retryCount()
        ));
    }

    private RagRetrievalOptions options(RagAgentPlan plan, GraphContext graphContext) {
        return new RagRetrievalOptions(
                plan.needVectorSearch(),
                plan.needHyde(),
                plan.needKeywordSearch(),
                plan.needGraphTrace(),
                graphContext
        );
    }

    private <T> T timed(RagAgentState state, String stepName, String decision, Supplier<T> supplier) {
        long started = System.nanoTime();
        try {
            T result = supplier.get();
            // Trace 记录的是本次请求实际走过的路径，所以用 List 顺序追加，而不是记录整张流程图。
            state.addStep(new RagAgentStepTrace(stepName, decision, true, elapsedMs(started), summary(result)));
            return result;
        } catch (Exception e) {
            state.addStep(new RagAgentStepTrace(stepName, decision, false, elapsedMs(started), e.getMessage()));
            throw e;
        }
    }

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private String summary(Object result) {
        if (result instanceof RagAgentPlan plan) {
            return plan.questionType() + "/" + plan.retrievalMode();
        }
        if (result instanceof GraphContext graphContext) {
            return "relations=" + graphContext.relations().size() + ", entities=" + graphContext.matchedEntities().size();
        }
        if (result instanceof RagRetrievalResultDTO retrieval) {
            return "original=" + retrieval.originalDocs().size()
                    + ", hyde=" + retrieval.hydeDocs().size()
                    + ", keyword=" + retrieval.keywordDocs().size()
                    + ", fused=" + retrieval.fusedDocs().size();
        }
        if (result instanceof List<?> list) {
            return "count=" + list.size();
        }
        if (result instanceof EvidenceResult evidence) {
            return "sufficient=" + evidence.sufficient()
                    + ", score=" + evidence.score()
                    + ", action=" + evidence.suggestedAction();
        }
        if (result instanceof String text) {
            return text.length() <= 120 ? text : text.substring(0, 120);
        }
        return result == null ? "null" : result.toString();
    }
}
