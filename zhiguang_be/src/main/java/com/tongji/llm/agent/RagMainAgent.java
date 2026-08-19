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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * RAG Main Agent: the main coordinator for one question-answer request.
 *
 * <p>This class deliberately keeps the workflow close to the graph/node model we discussed:
 * the agent owns the route, every node only handles its own step, and {@link RagAgentState}
 * carries the shared state between nodes.</p>
 */
@Slf4j
@Service
public class RagMainAgent {
    private final PlanNode planNode;
    private final EvidenceCheckNode evidenceCheckNode;
    private final GraphTraceNode graphTraceNode;
    private final RetrieveNode retrieveNode;
    private final RerankNode rerankNode;
    private final ExpandTopKNode expandTopKNode;
    private final DirectAnswerNode directAnswerNode;
    private final RagAgentEdgePolicy edgePolicy;
    private final List<ExternalKnowledgeProvider> externalKnowledgeProviders;

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

    /** Keeps existing unit tests and callers source-compatible while no provider is configured. */
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
        return run(scope, postId, originalQuestion, standaloneQuestion, topK, null);
    }

    public RagAgentState run(String scope, Long postId, String originalQuestion, String standaloneQuestion, int topK, String evalRunId) {
        String effectiveQuestion = StringUtils.hasText(standaloneQuestion) ? standaloneQuestion.trim() : originalQuestion;
        RagAgentState state = new RagAgentState(originalQuestion, effectiveQuestion, topK);
        state.evalRunId(evalRunId);

        RagAgentPlan plan = timed(state, "plan", "PLANNER", () -> planNode.execute(state));
        recordStep(state, new RagAgentStepTrace("plan_result", plan.retrievalMode().name(), true, 0, plan.reason()));

        if (edgePolicy.shouldDirectAnswer(plan)) {
            directAnswer(state);
            return state;
        }

        try {
            if (edgePolicy.shouldQueryGraph(plan)) {
                queryGraphTrace(state);
            }

            executeRetrievalRound(state, scope, postId);
            EvidenceResult evidence = checkEvidence(state);

            if (edgePolicy.shouldExpandTopK(state, evidence)) {
                String retrySummary = expandTopKNode.execute(state);
                recordStep(state, new RagAgentStepTrace("retry", "EXPAND_TOP_K", true, 0, retrySummary));
                executeRetrievalRound(state, scope, postId);
                checkEvidence(state);
            }

            discoverExternalLinksWhenNeeded(state);

            logAgentCompleted(state);
            return state;
        } catch (Exception e) {
            logAgentFailed(state, e);
            throw e;
        }
    }

    private void directAnswer(RagAgentState state) {
        timed(state, "direct_answer", "LLM_DIRECT", () -> directAnswerNode.execute(state));
    }

    private void queryGraphTrace(RagAgentState state) {
        GraphContext graphContext = timed(state, "graph_trace", "QUERY_NEO4J", () -> graphTraceNode.execute(state));
        recordStep(state, new RagAgentStepTrace(
                "graph_trace_result",
                graphContext.isEmpty() ? "GRAPH_MISS" : "GRAPH_HIT",
                true,
                0,
                "relations=" + graphContext.relations().size() + ", entities=" + graphContext.matchedEntities().size()
        ));
    }

    private void executeRetrievalRound(RagAgentState state, String scope, Long postId) {
        timed(state, "retrieve", "TOP" + state.currentTopK(), () -> retrieveNode.execute(state, scope, postId));
        if (edgePolicy.shouldRerank(state)) {
            timed(state, "rerank", "TOP" + state.currentTopK(), () -> rerankNode.execute(state));
        } else {
            rerankNode.skip(state);
        }
    }

    private EvidenceResult checkEvidence(RagAgentState state) {
        return timed(state, "evidence_check", "CHECK_TOP" + state.currentTopK(), () -> evidenceCheckNode.execute(state));
    }

    /**
     * Link-only MVP fallback. The provider receives the question, but no external
     * document text is ever inserted into the LLM context or the local vector store.
     */
    private void discoverExternalLinksWhenNeeded(RagAgentState state) {
        EvidenceResult evidence = state.evidenceResult();
        if (evidence == null || evidence.sufficient() || externalKnowledgeProviders.isEmpty()) {
            return;
        }
        List<ExternalKnowledgeResource> resources = externalKnowledgeProviders.stream()
                .filter(provider -> provider.supports(state.standaloneQuestion()))
                .flatMap(provider -> provider.findResources(state.standaloneQuestion(), 3).stream())
                .limit(3)
                .toList();
        recordStep(state, new RagAgentStepTrace(
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

    private <T> T timed(RagAgentState state, String stepName, String decision, Supplier<T> supplier) {
        long started = System.nanoTime();
        try {
            T result = supplier.get();
            recordStep(state, new RagAgentStepTrace(stepName, decision, true, elapsedMs(started), summary(result)));
            return result;
        } catch (Exception e) {
            recordStep(state, new RagAgentStepTrace(stepName, decision, false, elapsedMs(started), e.getMessage()));
            throw e;
        }
    }

    private void recordStep(RagAgentState state, RagAgentStepTrace step) {
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
