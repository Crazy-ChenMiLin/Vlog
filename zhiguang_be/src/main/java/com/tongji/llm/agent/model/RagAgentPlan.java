package com.tongji.llm.agent.model;

import org.springframework.util.StringUtils;

/**
 * Planner 产出的事前路线图。
 *
 * <p>它只描述“准备用什么工具”，不放检索分数和执行结果。
 * 执行后的结果属于 RagAgentState，证据够不够属于 EvidenceResult。</p>
 */
public record RagAgentPlan(
        QuestionType questionType,
        RetrievalMode retrievalMode,
        boolean needDirectAnswer,
        boolean needKeywordSearch,
        boolean needVectorSearch,
        boolean needHyde,
        boolean needGraphTrace,
        boolean needRerank,
        int initialTopK,
        String reason
) {
    public RagAgentPlan {
        questionType = questionType == null ? QuestionType.NORMAL_QA : questionType;
        retrievalMode = retrievalMode == null ? RetrievalMode.HYBRID : retrievalMode;
        initialTopK = Math.max(1, Math.min(20, initialTopK));
        reason = StringUtils.hasText(reason) ? reason.trim() : "Default RAG agent plan.";
    }

    public static RagAgentPlan defaultHybrid(int topK, String reason) {
        return new RagAgentPlan(
                QuestionType.NORMAL_QA,
                RetrievalMode.HYBRID,
                false,
                true,
                true,
                true,
                false,
                true,
                topK,
                reason
        );
    }
}
