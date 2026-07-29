package com.tongji.llm.chat.dto;

import com.tongji.llm.agent.state.RagAgentStepTrace;

/**
 * 推给前端的 Agent 过程事件。
 *
 * <p>它只展示工具调用过程摘要，不展示完整 prompt、完整 chunk 或模型内部推理。
 * 前端可以用这些事件做展开/折叠的“思考过程”面板。</p>
 */
public record AgentStepEventDTO(
        String traceId,
        String stepName,
        String title,
        String decision,
        boolean success,
        long costMs,
        String summary
) {
    public static AgentStepEventDTO from(String traceId, RagAgentStepTrace step) {
        return new AgentStepEventDTO(
                traceId,
                step.stepName(),
                title(step.stepName()),
                step.decision(),
                step.success(),
                step.costMs(),
                step.summary()
        );
    }

    static String title(String stepName) {
        return switch (stepName) {
            case "plan", "plan_result" -> "理解问题并制定计划";
            case "graph_trace", "graph_trace_result" -> "查询 Neo4j 图谱线索";
            case "retrieve" -> "检索知识库候选内容";
            case "rerank" -> "重排候选片段";
            case "evidence_check" -> "检查证据是否足够";
            case "retry" -> "扩大检索范围";
            case "direct_answer" -> "直接回答";
            default -> "执行 Agent 步骤";
        };
    }
}
