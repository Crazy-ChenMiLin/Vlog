package com.tongji.llm.agent.state;

import org.springframework.util.StringUtils;

/**
 * 单次 Agent 请求中的一条执行步骤记录。
 *
 * <p>{@link RagAgentState} 按执行顺序保存这些记录，用于定位耗时、失败节点及 topK 扩展情况。</p>
 */
public record RagAgentStepTrace(
        String stepName,
        String decision,
        boolean success,
        long costMs,
        String summary
) {
    public RagAgentStepTrace {
        stepName = StringUtils.hasText(stepName) ? stepName.trim() : "unknown";
        decision = StringUtils.hasText(decision) ? decision.trim() : "";
        costMs = Math.max(0, costMs);
        summary = StringUtils.hasText(summary) ? summary.trim() : "";
    }
}
