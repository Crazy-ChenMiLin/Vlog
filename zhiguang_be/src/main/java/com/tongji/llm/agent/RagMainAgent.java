package com.tongji.llm.agent;

import com.tongji.llm.DTO.RagRetrievalResultDTO;
import com.tongji.llm.agent.edge.RagAgentEdgePolicy;
import com.tongji.llm.agent.node.anwserNode.DirectAnswerNode;
import com.tongji.llm.agent.node.infraNode.EvidenceCheckNode;
import com.tongji.llm.agent.node.infraNode.ExpandTopKNode;
import com.tongji.llm.agent.node.graphNode.GraphTraceNode;
import com.tongji.llm.agent.node.PlanNode.PlanNode;
import com.tongji.llm.agent.node.SearchNode.RerankNode;
import com.tongji.llm.agent.node.SearchNode.RetrieveNode;
import com.tongji.llm.agent.state.EvidenceResult;
import com.tongji.llm.agent.state.RagAgentPlan;
import com.tongji.llm.agent.state.RagAgentState;
import com.tongji.llm.agent.state.RagAgentStepTrace;
import com.tongji.llm.external.ExternalKnowledgeProvider;
import com.tongji.llm.external.ExternalKnowledgeResource;
import com.tongji.llm.graphService.model.GraphContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * 单次 RAG 问答请求的主编排器。
 *
 * <p>该类负责选择并串联执行路径；各节点只处理一个步骤，节点之间通过
 * {@link RagAgentState} 共享本次请求的状态。</p>
 */
@Slf4j
@Service
public class RagMainAgent {
    private static final BiConsumer<RagAgentState, RagAgentStepTrace> NO_OP_STEP_LISTENER = (state, step) -> { };

    private final PlanNode planNode;
    private final EvidenceCheckNode evidenceCheckNode;
    private final GraphTraceNode graphTraceNode;
    private final RetrieveNode retrieveNode;
    private final RerankNode rerankNode;
    private final ExpandTopKNode expandTopKNode;
    private final DirectAnswerNode directAnswerNode;
    private final RagAgentEdgePolicy edgePolicy;
    private final List<ExternalKnowledgeProvider> externalKnowledgeProviders;

    @Autowired
    public RagMainAgent(
            PlanNode planNode,
            EvidenceCheckNode evidenceCheckNode,
            GraphTraceNode graphTraceNode,
            RetrieveNode retrieveNode,
            RerankNode rerankNode,
            ExpandTopKNode expandTopKNode,
            DirectAnswerNode directAnswerNode,
            RagAgentEdgePolicy edgePolicy,
            List<ExternalKnowledgeProvider> externalKnowledgeProviders) {
        this.planNode = planNode;
        this.evidenceCheckNode = evidenceCheckNode;
        this.graphTraceNode = graphTraceNode;
        this.retrieveNode = retrieveNode;
        this.rerankNode = rerankNode;
        this.expandTopKNode = expandTopKNode;
        this.directAnswerNode = directAnswerNode;
        this.edgePolicy = edgePolicy;
        this.externalKnowledgeProviders = externalKnowledgeProviders == null ? List.of() : List.copyOf(externalKnowledgeProviders);
    }

    /**
     * 兼容未配置外部知识提供器的调用方和既有测试。
     */
    public RagMainAgent(
            PlanNode planNode,
            EvidenceCheckNode evidenceCheckNode,
            GraphTraceNode graphTraceNode,
            RetrieveNode retrieveNode,
            RerankNode rerankNode,
            ExpandTopKNode expandTopKNode,
            DirectAnswerNode directAnswerNode,
            RagAgentEdgePolicy edgePolicy) {
        this(planNode, evidenceCheckNode, graphTraceNode, retrieveNode, rerankNode, expandTopKNode,
                directAnswerNode, edgePolicy, List.of());
    }

    public RagAgentState run(String scope, Long postId, String originalQuestion, String standaloneQuestion, int topK) {
        return run(scope, postId, originalQuestion, standaloneQuestion, topK, null, NO_OP_STEP_LISTENER);
    }

    public RagAgentState run(String scope, Long postId, String originalQuestion, String standaloneQuestion, int topK, String evalRunId) {
        return run(scope, postId, originalQuestion, standaloneQuestion, topK, evalRunId, NO_OP_STEP_LISTENER);
    }

    /**
     * 执行 Agent，并在每个节点记录步骤后立即调用 {@code stepListener}。
     *
     * <p>执行链仍保持同步状态模型，SSE 调用方可通过监听器实时推送进度。</p>
     */
    public RagAgentState run(
            String scope,
            Long postId,
            String originalQuestion,
            String standaloneQuestion,
            int topK,
            String evalRunId,
            BiConsumer<RagAgentState, RagAgentStepTrace> stepListener) {
        BiConsumer<RagAgentState, RagAgentStepTrace> effectiveStepListener =
                stepListener == null ? NO_OP_STEP_LISTENER : stepListener;
        String effectiveQuestion = StringUtils.hasText(standaloneQuestion) ? standaloneQuestion.trim() : originalQuestion;
        RagAgentState state = new RagAgentState(originalQuestion, effectiveQuestion, topK);
        state.evalRunId(evalRunId);

        RagAgentPlan plan = timed(state, effectiveStepListener, "plan", "PLANNER", () -> planNode.execute(state));
        recordStep(state, effectiveStepListener, new RagAgentStepTrace("plan_result", plan.retrievalMode().name(), true, 0, plan.reason()));

        if (edgePolicy.shouldDirectAnswer(plan)) {
            directAnswer(state, effectiveStepListener);
            return state;
        }

        try {
            if (edgePolicy.shouldQueryGraph(plan)) {
                queryGraphTrace(state, effectiveStepListener);
            }

            executeRetrievalRound(state, effectiveStepListener, scope, postId);
            EvidenceResult evidence = checkEvidence(state, effectiveStepListener);

            if (edgePolicy.shouldExpandTopK(state, evidence)) {
                String retrySummary = expandTopKNode.execute(state);
                recordStep(state, effectiveStepListener, new RagAgentStepTrace("retry", "EXPAND_TOP_K", true, 0, retrySummary));
                executeRetrievalRound(state, effectiveStepListener, scope, postId);
                checkEvidence(state, effectiveStepListener);
            }

            discoverExternalLinksWhenNeeded(state, effectiveStepListener);

            logAgentCompleted(state);
            return state;
        } catch (Exception e) {
            logAgentFailed(state, e);
            throw e;
        }
    }

    private void directAnswer(RagAgentState state, BiConsumer<RagAgentState, RagAgentStepTrace> stepListener) {
        timed(state, stepListener, "direct_answer", "LLM_DIRECT", () -> directAnswerNode.execute(state));
    }

    private void queryGraphTrace(RagAgentState state, BiConsumer<RagAgentState, RagAgentStepTrace> stepListener) {
        GraphContext graphContext = timed(state, stepListener, "graph_trace", "QUERY_NEO4J", () -> graphTraceNode.execute(state));
        recordStep(state, stepListener, new RagAgentStepTrace(
                "graph_trace_result",
                graphContext.isEmpty() ? "GRAPH_MISS" : "GRAPH_HIT",
                true,
                0,
                "relations=" + graphContext.relations().size() + ", entities=" + graphContext.matchedEntities().size()
        ));
    }

    private void executeRetrievalRound(
            RagAgentState state,
            BiConsumer<RagAgentState, RagAgentStepTrace> stepListener,
            String scope,
            Long postId) {
        timed(state, stepListener, "retrieve", "TOP" + state.currentTopK(), () -> retrieveNode.execute(state, scope, postId));
        if (edgePolicy.shouldRerank(state)) {
            timed(state, stepListener, "rerank", "TOP" + state.currentTopK(), () -> rerankNode.execute(state));
        } else {
            rerankNode.skip(state);
        }
    }

    private EvidenceResult checkEvidence(RagAgentState state, BiConsumer<RagAgentState, RagAgentStepTrace> stepListener) {
        return timed(state, stepListener, "evidence_check", "CHECK_TOP" + state.currentTopK(), () -> evidenceCheckNode.execute(state));
    }

    /**
     * 站内证据不足时补充外部官方资料链接。
     *
     * <p>仅将问题交给外部提供器并返回链接，不读取外部正文，也不将其写入模型上下文或本地向量库。</p>
     */
    private void discoverExternalLinksWhenNeeded(
            RagAgentState state,
            BiConsumer<RagAgentState, RagAgentStepTrace> stepListener) {
        EvidenceResult evidence = state.evidenceResult();
        if (evidence == null || evidence.sufficient() || externalKnowledgeProviders.isEmpty()) {
            return;
        }
        List<ExternalKnowledgeResource> resources = externalKnowledgeProviders.stream()
                .filter(provider -> provider.supports(state.standaloneQuestion()))
                .flatMap(provider -> provider.findResources(state.standaloneQuestion(), 3).stream())
                .limit(3)
                .toList();
        recordStep(state, stepListener, new RagAgentStepTrace(
                "external_knowledge",
                "OFFICIAL_LINK_FALLBACK",
                true,
                0,
                "official resources=" + resources.size()
        ));
        if (!resources.isEmpty()) {
            state.finalAnswer(externalLinkAnswer(resources));
        }
    }

    private String externalLinkAnswer(List<ExternalKnowledgeResource> resources) {
        String links = resources.stream()
                .map(resource -> "- [" + resource.title() + "](" + resource.url() + ")"
                        + " — `" + resource.repository() + "/" + resource.path() + "`")
                .collect(Collectors.joining("\n"));
        return "站内知识库暂未找到足够证据回答这个 Go 问题。以下是 GitHub 官方资料，可继续查阅：\n\n" + links
                + "\n\n> 此处仅推荐外部官方资料，未将外部内容写入站内知识库，也未由 AI 基于外部正文生成回答。";
    }

    private <T> T timed(
            RagAgentState state,
            BiConsumer<RagAgentState, RagAgentStepTrace> stepListener,
            String stepName,
            String decision,
            Supplier<T> supplier) {
        long started = System.nanoTime();
        try {
            T result = supplier.get();
            recordStep(state, stepListener, new RagAgentStepTrace(stepName, decision, true, elapsedMs(started), summary(result)));
            return result;
        } catch (Exception e) {
            recordStep(state, stepListener, new RagAgentStepTrace(stepName, decision, false, elapsedMs(started), e.getMessage()));
            throw e;
        }
    }

    private void recordStep(
            RagAgentState state,
            BiConsumer<RagAgentState, RagAgentStepTrace> stepListener,
            RagAgentStepTrace step) {
        state.addStep(step);
        log.info("rag_agent_step",
                kv("event_type", "rag_agent_step"),
                kv("trace_id", state.traceId()),
                kv("original_question", state.originalQuestion()),
                kv("standalone_question", state.standaloneQuestion()),
                kv("step_name", step.stepName()),
                kv("decision", step.decision()),
                kv("success", step.success()),
                kv("cost_ms", step.costMs()),
                kv("summary", step.summary()));
        try {
            stepListener.accept(state, step);
        } catch (RuntimeException listenerError) {
            log.warn("rag_agent_step_listener_failed", listenerError,
                    kv("trace_id", state.traceId()),
                    kv("step_name", step.stepName()));
        }
    }

    private void logAgentCompleted(RagAgentState state) {
        EvidenceResult evidence = state.evidenceResult();
        log.info("rag_agent_completed",
                kv("event_type", "rag_agent_completed"),
                kv("trace_id", state.traceId()),
                kv("question_type", state.plan().questionType().name()),
                kv("retrieval_mode", state.plan().retrievalMode().name()),
                kv("top_k", state.currentTopK()),
                kv("retry_count", state.retryCount()),
                kv("evidence_sufficient", evidence != null && evidence.sufficient()),
                kv("evidence_score", evidence == null ? null : evidence.score()),
                kv("evidence_action", evidence == null ? null : evidence.suggestedAction().name()),
                kv("answer_doc_count", state.answerDocs().size()),
                kv("step_count", state.steps().size()));
    }

    private void logAgentFailed(RagAgentState state, Exception exception) {
        log.warn("rag_agent_failed",
                kv("event_type", "rag_agent_failed"),
                kv("trace_id", state.traceId()),
                kv("top_k", state.currentTopK()),
                kv("retry_count", state.retryCount()),
                kv("step_count", state.steps().size()),
                kv("error_type", exception.getClass().getSimpleName()),
                kv("error_message", exception.getMessage()));
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
