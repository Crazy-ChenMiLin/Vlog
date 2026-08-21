package com.tongji.benchmark.evaluator;

import com.tongji.benchmark.assembler.FullTranscriptAssembler;
import com.tongji.benchmark.model.dto.BenchmarkCaseDTO;
import com.tongji.llm.observability.enums.RagTranscriptStageEnum;
import com.tongji.llm.observability.enums.RagTranscriptStatusEnum;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptCandidateDTO;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptDTO;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptStageDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FullTranscriptAssemblerTest {

    @Test
    void addsEvaluationToTheSameRuntimeTranscript() {
        RagTranscriptDTO runtimeTranscript = transcript();
        BenchmarkCaseDTO benchmarkCase = new BenchmarkCaseDTO(
                "gold-003", "HyDE 如何提升召回？", List.of("gold#1"), List.of("RAG")
        );

        RagTranscriptDTO evaluated = FullTranscriptAssembler.attachEvaluation(
                runtimeTranscript, benchmarkCase, "run-001", "gold-dataset-v1"
        );

        assertThat(runtimeTranscript.evaluation()).isNull();
        assertThat(runtimeTranscript.stages().get(1).goldHit()).isNull();
        assertThat(evaluated.evaluation().caseId()).isEqualTo("gold-003");
        assertThat(evaluated.evaluation().expectedChunkIds()).containsExactly("gold#1");
        assertThat(evaluated.stages().get(1).goldHit()).isTrue();
        assertThat(evaluated.stages().get(1).goldRanks()).containsExactly(2);
        assertThat(evaluated.finalAnswer()).isEqualTo("最终回答");
    }

    private RagTranscriptDTO transcript() {
        RagTranscriptStageDTO original = new RagTranscriptStageDTO(
                RagTranscriptStageEnum.ORIGINAL,
                List.of(new RagTranscriptCandidateDTO(1, "miss#0", "post-1", 0.91)),
                null,
                List.of()
        );
        RagTranscriptStageDTO hyde = new RagTranscriptStageDTO(
                RagTranscriptStageEnum.HYDE,
                List.of(
                        new RagTranscriptCandidateDTO(1, "miss#0", "post-1", 0.92),
                        new RagTranscriptCandidateDTO(2, "gold#1", "post-2", 0.88)
                ),
                null,
                List.of()
        );
        return new RagTranscriptDTO(
                "rag-transcript-v1", "trace-001", "global", "原问题", "改写问题", "HyDE 假设答案", 5,
                List.of(original, hyde), List.of(), "最终回答", RagTranscriptStatusEnum.COMPLETED, null
        );
    }
}
