package com.tongji.llm.agent.state;

import org.springframework.util.StringUtils;

/**
 * 单次请求实际走过的一步 Trace。
 *
 * <p>Trace 不是整张流程图，而是本次执行路径，所以 RagAgentState 里用 List 顺序保存。
 * 这份轻量记录后续可以直接打到 ELK，用于观察哪一步慢、哪一步失败、是否触发 topK 扩展。</p>
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
