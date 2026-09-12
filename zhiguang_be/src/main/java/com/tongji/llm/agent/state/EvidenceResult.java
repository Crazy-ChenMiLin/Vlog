package com.tongji.llm.agent.state;

import org.springframework.util.StringUtils;

/**
 * EvidenceCheck 的检查报告。
 *
 * <p>包含证据充分性、置信分数、判断原因和下一步建议。主 Agent 仅依据
 * {@code suggestedAction} 执行有限补救，避免模型无限重试。</p>
 */
public record EvidenceResult(
        boolean sufficient,
        double score,
        String reason,
        EvidenceAction suggestedAction
) {
    public EvidenceResult {
        score = Math.max(0, Math.min(1, score));
        reason = StringUtils.hasText(reason) ? reason.trim() : "";
        suggestedAction = suggestedAction == null ? EvidenceAction.ANSWER_WITH_LIMITATION : suggestedAction;
    }

    public static EvidenceResult sufficient(String reason) {
        return new EvidenceResult(true, 1.0, reason, EvidenceAction.NONE);
    }

    public static EvidenceResult limited(String reason) {
        return new EvidenceResult(false, 0.0, reason, EvidenceAction.ANSWER_WITH_LIMITATION);
    }
}
