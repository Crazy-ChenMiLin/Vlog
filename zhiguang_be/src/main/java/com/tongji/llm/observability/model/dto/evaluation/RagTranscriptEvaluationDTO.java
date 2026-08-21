package com.tongji.llm.observability.model.dto.evaluation;

import java.util.List;

/**
 * 仅在 Benchmark 模式下附加的 Gold 评测信息。
 */
public record RagTranscriptEvaluationDTO(
        String runId,
        String caseId,
        String datasetVersion,
        String questionSource,
        List<String> expectedChunkIds
) {
    public RagTranscriptEvaluationDTO {
        expectedChunkIds = expectedChunkIds == null ? List.of() : List.copyOf(expectedChunkIds);
    }
}
