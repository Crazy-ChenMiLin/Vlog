package com.tongji.benchmark.controller;

import com.tongji.benchmark.model.dto.BenchmarkSingleCaseRequest;
import com.tongji.benchmark.model.dto.BenchmarkEvaluationContextDTO;
import com.tongji.benchmark.service.BenchmarkSingleCaseService;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 仅供评测脚本和 CI 调用的内部 Benchmark 接口。
 *
 * <p>该路径未配置为公开路径：人工调用沿用默认 JWT 鉴权，CI 可使用
 * {@code X-Benchmark-Token} 取得临时 Benchmark 身份。正常用户 RAG 接口及其返回体不受影响。
 */
@RestController
@RequestMapping("/api/internal/rag-benchmark")
@Validated
@RequiredArgsConstructor
public class BenchmarkController {

    private static final String DATASET_VERSION = "gold-dataset-v1";
    private static final int DEFAULT_TOP_K = 5;

    private final BenchmarkSingleCaseService benchmarkSingleCaseService;

    /**
     * 执行一道固定 Gold 题，返回同一次真实 RAG 调用生成的完整评测 Transcript。
     */
    @PostMapping("/single-case")
    public Mono<RagTranscriptDTO> executeSingleCase(
            @Valid @RequestBody BenchmarkSingleCaseRequest request) {
        BenchmarkEvaluationContextDTO context = new BenchmarkEvaluationContextDTO(
                request.runId(),
                request.caseId(),
                DATASET_VERSION
        );
        int effectiveTopK = request.topK() == null ? DEFAULT_TOP_K : request.topK();

        try {
            return benchmarkSingleCaseService.execute(context, effectiveTopK)
                    .onErrorMap(IllegalArgumentException.class, exception -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "未找到对应的 Gold case：" + request.caseId(),
                            exception
                    ));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "未找到对应的 Gold case：" + request.caseId(),
                    exception
            );
        }
    }
}
