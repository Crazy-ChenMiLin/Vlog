package com.tongji.llm.agent.state;

import org.springframework.util.StringUtils;

/**
 * EvidenceCheck 的检查报告。
 *
 * <p>我们讨论过它至少要回答四件事：证据是否足够、分数是多少、原因是什么、
 * 下一步建议做什么。MainAgent 只根据 suggestedAction 做有限补救，不让模型无限重试。</p>
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
