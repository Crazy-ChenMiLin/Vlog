package com.tongji.benchmark.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 触发一道 Gold 题内部评测的请求参数。
 *
 * <p>题目和标准答案不由调用方传入，服务端根据 {@code caseId} 从固定 Gold 数据集读取，
 * 以避免评测基准被调用方篡改。
 */
public record BenchmarkSingleCaseRequest(
        @NotBlank(message = "runId 不能为空")
        @Size(max = 80, message = "runId 长度不能超过 80")
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$", message = "runId 格式不合法")
        String runId,

        @NotBlank(message = "caseId 不能为空")
        @Pattern(regexp = "^gold-\\d{3}$", message = "caseId 必须是 gold-001 形式")
        String caseId,

        @Min(value = 1, message = "topK 最小为 1")
        @Max(value = 20, message = "topK 最大为 20")
        Integer topK
) {
}
