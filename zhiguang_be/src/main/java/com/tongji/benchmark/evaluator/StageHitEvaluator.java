package com.tongji.benchmark.evaluator;

import com.tongji.llm.observability.model.dto.transcript.RagTranscriptStageDTO;

import java.util.List;
import java.util.Set;

/**
 * 为一份已存在的阶段运行记录补充 Gold 命中结果。
 */
public final class StageHitEvaluator {

    private StageHitEvaluator() {
    }

    public static RagTranscriptStageDTO annotate(
            RagTranscriptStageDTO stage,
            Set<String> expectedChunkIds) {
        Set<String> safeExpectedChunkIds = expectedChunkIds == null ? Set.of() : expectedChunkIds;
        List<Integer> goldRanks = stage.candidates().stream()
                .filter(candidate -> candidate.chunkId() != null)
                .filter(candidate -> safeExpectedChunkIds.contains(candidate.chunkId()))
                .map(candidate -> candidate.rank())
                .toList();

        return new RagTranscriptStageDTO(
                stage.stage(),
                stage.candidates(),
                !goldRanks.isEmpty(),
                goldRanks
        );
    }
}
