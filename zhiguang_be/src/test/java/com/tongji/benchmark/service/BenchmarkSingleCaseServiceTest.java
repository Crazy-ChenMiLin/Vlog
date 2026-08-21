package com.tongji.benchmark.service;

import com.tongji.benchmark.model.dto.BenchmarkCaseDTO;
import com.tongji.benchmark.model.dto.BenchmarkEvaluationContextDTO;
import com.tongji.llm.chat.RagQueryService;
import com.tongji.llm.observability.enums.RagTranscriptStageEnum;
import com.tongji.llm.observability.enums.RagTranscriptStatusEnum;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptCandidateDTO;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptDTO;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptStageDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BenchmarkSingleCaseServiceTest {

    @Mock
    private BenchmarkCaseService benchmarkCaseService;
    @Mock
    private RagQueryService ragQueryService;

    @Test
    void executesOneCaseAndAnnotatesTheTranscriptReturnedByTheSameRagRun() {
        BenchmarkCaseDTO benchmarkCase = new BenchmarkCaseDTO(
                "gold-003", "HyDE 如何提升召回？", List.of("gold#1"), List.of("RAG")
        );
        BenchmarkEvaluationContextDTO context = new BenchmarkEvaluationContextDTO(
                "run-001", "gold-003", "gold-dataset-v1"
        );
        when(benchmarkCaseService.getRequiredCase("gold-003")).thenReturn(benchmarkCase);
        when(ragQueryService.generateGlobalTranscript("HyDE 如何提升召回？", 5, "run-001"))
                .thenReturn(Mono.just(runtimeTranscript()));
        BenchmarkSingleCaseService service = new BenchmarkSingleCaseService(benchmarkCaseService, ragQueryService);

        RagTranscriptDTO result = service.execute(context, 5).block();

        assertThat(result.traceId()).isEqualTo("trace-001");
        assertThat(result.finalAnswer()).isEqualTo("最终回答");
        assertThat(result.evaluation().caseId()).isEqualTo("gold-003");
        assertThat(result.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.goldHit()).isTrue();
            assertThat(stage.goldRanks()).containsExactly(2);
        });
        verify(ragQueryService).generateGlobalTranscript(eq("HyDE 如何提升召回？"), eq(5), eq("run-001"));
    }

    private RagTranscriptDTO runtimeTranscript() {
        RagTranscriptStageDTO stage = new RagTranscriptStageDTO(
                RagTranscriptStageEnum.HYDE,
                List.of(
                        new RagTranscriptCandidateDTO(1, "miss#0", "post-1", 0.9),
                        new RagTranscriptCandidateDTO(2, "gold#1", "post-2", 0.8)
                ),
                null,
                List.of()
        );
        return new RagTranscriptDTO(
                "rag-transcript-v1", "trace-001", "global", "原问题", "改写问题", "假设答案", 5,
                List.of(stage), List.of(), "最终回答", RagTranscriptStatusEnum.COMPLETED, null
        );
    }
}
