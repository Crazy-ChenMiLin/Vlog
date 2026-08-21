package com.tongji.benchmark.api;

import com.tongji.benchmark.api.dto.BenchmarkSingleCaseRequest;
import com.tongji.benchmark.model.dto.BenchmarkEvaluationContextDTO;
import com.tongji.benchmark.service.BenchmarkSingleCaseService;
import com.tongji.llm.observability.enums.RagTranscriptStatusEnum;
import com.tongji.llm.observability.model.dto.evaluation.RagTranscriptEvaluationDTO;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BenchmarkControllerTest {

    @Mock
    private BenchmarkSingleCaseService benchmarkSingleCaseService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BenchmarkController(benchmarkSingleCaseService)).build();
    }

    @Test
    void executesOneGoldCaseWithTheDefaultTopK() {
        when(benchmarkSingleCaseService.execute(any(BenchmarkEvaluationContextDTO.class), eq(5)))
                .thenReturn(Mono.just(transcript()));
        BenchmarkController controller = new BenchmarkController(benchmarkSingleCaseService);

        RagTranscriptDTO result = controller.executeSingleCase(
                new BenchmarkSingleCaseRequest("local-run-001", "gold-003", null)
        ).block();

        assertThat(result.evaluation().caseId()).isEqualTo("gold-003");
        ArgumentCaptor<BenchmarkEvaluationContextDTO> contextCaptor =
                ArgumentCaptor.forClass(BenchmarkEvaluationContextDTO.class);
        verify(benchmarkSingleCaseService).execute(contextCaptor.capture(), eq(5));
        assertThat(contextCaptor.getValue()).isEqualTo(
                new BenchmarkEvaluationContextDTO("local-run-001", "gold-003", "gold-dataset-v1")
        );
    }

    @Test
    void passesTheRequestedTopKToTheBenchmarkService() {
        when(benchmarkSingleCaseService.execute(any(BenchmarkEvaluationContextDTO.class), eq(8)))
                .thenReturn(Mono.just(transcript()));
        BenchmarkController controller = new BenchmarkController(benchmarkSingleCaseService);

        controller.executeSingleCase(new BenchmarkSingleCaseRequest("local-run-002", "gold-004", 8)).block();

        verify(benchmarkSingleCaseService).execute(
                new BenchmarkEvaluationContextDTO("local-run-002", "gold-004", "gold-dataset-v1"),
                8
        );
    }

    @Test
    void exposesTheEvaluatedTranscriptThroughTheInternalHttpEndpoint() throws Exception {
        when(benchmarkSingleCaseService.execute(any(BenchmarkEvaluationContextDTO.class), eq(5)))
                .thenReturn(Mono.just(transcript()));

        MvcResult initialResult = mockMvc.perform(post("/api/internal/rag-benchmark/single-case")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runId":"local-run-003","caseId":"gold-003"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(initialResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("trace-001"))
                .andExpect(jsonPath("$.evaluation.caseId").value("gold-003"));
    }

    private RagTranscriptDTO transcript() {
        return new RagTranscriptDTO(
                "rag-transcript-v1",
                "trace-001",
                "global",
                "问题",
                "问题",
                null,
                5,
                List.of(),
                List.of(),
                "回答",
                RagTranscriptStatusEnum.COMPLETED,
                new RagTranscriptEvaluationDTO(
                        "local-run-001", "gold-003", "gold-dataset-v1", "scripts/rag-eval/gold-dataset-v1.json", List.of("chunk#1")
                )
        );
    }
}
