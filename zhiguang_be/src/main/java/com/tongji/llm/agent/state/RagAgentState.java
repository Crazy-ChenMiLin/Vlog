package com.tongji.llm.agent.state;

import com.tongji.llm.DTO.RagRetrievalResultDTO;
import com.tongji.llm.graphService.model.GraphContext;
import org.springframework.ai.document.Document;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一次 RAG Agent 执行的总状态容器。
 *
 * <p>它不是四种问题类型各建一个 State，而是一个 State 覆盖 CHAT、KEYWORD、HYBRID、
 * GRAPH_AUGMENTED_HYBRID 等模式。不同模式下某些字段为空，例如闲聊时 graphContext
 * 和 answerDocs 可以为空，关系题时 graphContext 会被填充。</p>
 *
 * <p>State 本身不主动干活；真正写入 plan、retrieval、check、trace 的是 RagMainAgent。
 * 可以把它理解成本次问答的“过程记录本”。</p>
 */
public class RagAgentState {
    private final String traceId;
    private final String originalQuestion;
    private final String standaloneQuestion;
    private String evalRunId;
    private RagAgentPlan plan;
    private int currentTopK;
    private GraphContext graphContext = GraphContext.empty();
    private RagRetrievalResultDTO retrievalResult;
    private List<Document> rerankedDocs = List.of();
    private List<Document> answerDocs = List.of();
    private EvidenceResult evidenceResult;
    private int retryCount;
    private String finalAnswer;
    private final List<RagAgentStepTrace> steps = new ArrayList<>();

    public RagAgentState(String originalQuestion, String standaloneQuestion, int topK) {
        this.traceId = UUID.randomUUID().toString();
        this.originalQuestion = normalize(originalQuestion);
        this.standaloneQuestion = normalize(standaloneQuestion);
        this.currentTopK = Math.max(1, Math.min(20, topK));
    }

    public String traceId() {
        return traceId;
    }

    public String originalQuestion() {
        return originalQuestion;
    }

    public String standaloneQuestion() {
        return standaloneQuestion;
    }

    public String evalRunId() {
        return evalRunId;
    }

    public void evalRunId(String evalRunId) {
        this.evalRunId = normalize(evalRunId);
    }

    public RagAgentPlan plan() {
        return plan;
    }

    public void plan(RagAgentPlan plan) {
        this.plan = plan;
    }

    public int currentTopK() {
        return currentTopK;
    }

    public void currentTopK(int currentTopK) {
        this.currentTopK = Math.max(1, Math.min(20, currentTopK));
    }

    public GraphContext graphContext() {
        return graphContext;
    }

    public void graphContext(GraphContext graphContext) {
        this.graphContext = graphContext == null ? GraphContext.empty() : graphContext;
    }

    public RagRetrievalResultDTO retrievalResult() {
        return retrievalResult;
    }

    public void retrievalResult(RagRetrievalResultDTO retrievalResult) {
        this.retrievalResult = retrievalResult;
    }

    public List<Document> rerankedDocs() {
        return rerankedDocs;
    }

    public void rerankedDocs(List<Document> rerankedDocs) {
        this.rerankedDocs = rerankedDocs == null ? List.of() : List.copyOf(rerankedDocs);
    }

    public List<Document> answerDocs() {
        return answerDocs;
    }

    public void answerDocs(List<Document> answerDocs) {
        this.answerDocs = answerDocs == null ? List.of() : List.copyOf(answerDocs);
    }

    public EvidenceResult evidenceResult() {
        return evidenceResult;
    }

    public void evidenceResult(EvidenceResult evidenceResult) {
        this.evidenceResult = evidenceResult;
    }

    public int retryCount() {
        return retryCount;
    }

    public void incrementRetryCount() {
        this.retryCount++;
    }

    public String finalAnswer() {
        return finalAnswer;
    }

    public void finalAnswer(String finalAnswer) {
        this.finalAnswer = StringUtils.hasText(finalAnswer) ? finalAnswer.trim() : null;
    }

    public List<RagAgentStepTrace> steps() {
        return List.copyOf(steps);
    }

    public void addStep(RagAgentStepTrace step) {
        if (step != null) {
            steps.add(step);
        }
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }
}
