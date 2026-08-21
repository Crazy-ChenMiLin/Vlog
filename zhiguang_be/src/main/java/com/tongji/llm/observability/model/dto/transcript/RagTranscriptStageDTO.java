package com.tongji.llm.observability.model.dto.transcript;

import com.tongji.llm.observability.enums.RagTranscriptStageEnum;

import java.util.List;

/**
 * 同一份 Transcript 在一个检索阶段的过程与评测结果。
 *
 * <p>普通请求没有 Gold 标准答案，因此 {@code goldHit} 为 {@code null}；
 * Benchmark 请求才会填入命中结果和排名。
 */
public record RagTranscriptStageDTO(
        RagTranscriptStageEnum stage,
        List<RagTranscriptCandidateDTO> candidates,
        Boolean goldHit,
        List<Integer> goldRanks
) {
    public RagTranscriptStageDTO {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        goldRanks = goldRanks == null ? List.of() : List.copyOf(goldRanks);
    }
}
