package com.tongji.auth.config;

import com.tongji.benchmark.controller.BenchmarkController;
import com.tongji.benchmark.model.dto.BenchmarkEvaluationContextDTO;
import com.tongji.benchmark.service.BenchmarkSingleCaseService;
import com.tongji.llm.observability.enums.RagTranscriptStatusEnum;
import com.tongji.llm.observability.model.dto.evaluation.RagTranscriptEvaluationDTO;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies that the CI token is authenticated inside the real Security filter chain. */
@WebMvcTest(controllers = BenchmarkController.class, properties = "BENCHMARK_TOKEN=expected-token")
@Import(SecurityConfig.class)
class BenchmarkTokenSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BenchmarkSingleCaseService benchmarkSingleCaseService;

    @MockBean(name = "jwtDecoder")
    private JwtDecoder jwtDecoder;

    @Test
    void acceptsTheCiTokenThroughTheSecurityChain() throws Exception {
        stubSuccessfulBenchmarkExecution();

        MvcResult initial = mockMvc.perform(benchmarkRequest()
                        .header(BenchmarkTokenAuthenticationFilter.HEADER_NAME, "expected-token"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(initial)).andExpect(status().isOk());
    }

    @Test
    void rejectsAnIncorrectCiTokenBeforeTheController() throws Exception {
        mockMvc.perform(benchmarkRequest()
                        .header(BenchmarkTokenAuthenticationFilter.HEADER_NAME, "incorrect-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void stillAcceptsTheNormalJwtPathWhenNoCiTokenIsSupplied() throws Exception {
        stubSuccessfulBenchmarkExecution();
        when(jwtDecoder.decode("normal-jwt")).thenReturn(new Jwt(
                "normal-jwt",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of("sub", "manual-user", "scope", "read")
        ));

        MvcResult initial = mockMvc.perform(benchmarkRequest()
                        .header("Authorization", "Bearer normal-jwt"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(initial)).andExpect(status().isOk());
    }

    private void stubSuccessfulBenchmarkExecution() {
        when(benchmarkSingleCaseService.execute(any(BenchmarkEvaluationContextDTO.class), eq(5)))
                .thenReturn(Mono.just(new RagTranscriptDTO(
                        "rag-transcript-v1", "trace-security-001", "global", "question", "question", null, 5,
                        List.of(), List.of(), "answer", RagTranscriptStatusEnum.COMPLETED,
                        new RagTranscriptEvaluationDTO(
                                "security-run-001", "gold-001", "gold-dataset-v1", "benchmark/gold-dataset-v1.json", List.of()
                        )
                )));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder benchmarkRequest() {
        return post("/api/internal/rag-benchmark/single-case")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"runId":"security-run-001","caseId":"gold-001"}
                        """);
    }
}
