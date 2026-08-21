package com.tongji.benchmark.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 一道已人工标注的 Gold 评测题。
 *
 * @param caseId           题目唯一标识，例如 gold-001
 * @param question         发送给 RAG 的问题
 * @param expectedChunkIds 正确证据对应的 chunkId 列表
 * @param scenarioTags     用于后续按场景分组统计的标签
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BenchmarkCaseDTO(
        @JsonProperty("id") String caseId,
        String question,
        @JsonProperty("expected_chunk_ids") List<String> expectedChunkIds,
        @JsonProperty("scenario_tags") List<String> scenarioTags
) {
    public BenchmarkCaseDTO {
        expectedChunkIds = expectedChunkIds == null ? List.of() : List.copyOf(expectedChunkIds);
        scenarioTags = scenarioTags == null ? List.of() : List.copyOf(scenarioTags);
    }
}
