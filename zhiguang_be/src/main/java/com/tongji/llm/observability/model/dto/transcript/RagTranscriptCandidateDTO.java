package com.tongji.llm.observability.model.dto.transcript;

/**
 * Transcript 中一个检索候选 chunk 的最小可复盘信息。
 */
public record RagTranscriptCandidateDTO(
        int rank,
        String chunkId,
        String postId,
        Double score
) {
}
