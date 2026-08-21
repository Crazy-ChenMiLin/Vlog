package com.tongji.benchmark.model.dto;

/**
 * 一次内部评测调用的上下文。
 *
 * <p>普通用户请求没有该对象；它只用于把指定 Gold case 标记为 Benchmark 执行。
 */
public record BenchmarkEvaluationContextDTO(
        String runId,
        String caseId,
        String datasetVersion
) {
}
