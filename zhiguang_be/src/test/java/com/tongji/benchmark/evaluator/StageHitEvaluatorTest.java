package com.tongji.benchmark.evaluator;

import com.tongji.llm.observability.enums.RagTranscriptStageEnum;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptCandidateDTO;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptStageDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StageHitEvaluatorTest {

    @Test
    void addsGoldHitAndKeepsOneBasedRanks() {
        RagTranscriptStageDTO annotated = StageHitEvaluator.annotate(
                stage(RagTranscriptStageEnum.HYDE, "11#0", "22#1", "33#2"),
                Set.of("22#1", "33#2")
        );

        assertThat(annotated.goldHit()).isTrue();
        assertThat(annotated.goldRanks()).containsExactly(2, 3);
    }

    @Test
    void marksNoHitWhenNoCandidateMatchesGold() {
        RagTranscriptStageDTO annotated = StageHitEvaluator.annotate(
                stage(RagTranscriptStageEnum.RERANKED, "11#0", "22#1"),
                Set.of("99#0")
        );

        assertThat(annotated.goldHit()).isFalse();
        assertThat(annotated.goldRanks()).isEmpty();
    }

    private RagTranscriptStageDTO stage(RagTranscriptStageEnum stage, String... chunkIds) {
        List<RagTranscriptCandidateDTO> candidates = java.util.stream.IntStream.range(0, chunkIds.length)
                .mapToObj(index -> new RagTranscriptCandidateDTO(index + 1, chunkIds[index], "post", null))
                .toList();
        return new RagTranscriptStageDTO(stage, candidates, null, List.of());
    }
}
