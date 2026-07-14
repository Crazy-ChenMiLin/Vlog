package com.tongji.llm.DTO;

import java.util.List;

/**
 * Read-only view of the three retrieval stages used by single-post RAG.
 */
public record RagRetrievalDebugDTO(
        long postId,
        String question,
        String hypotheticalAnswer,
        double similarityThreshold,
        List<RetrievedChunk> originalResults,
        List<RetrievedChunk> hydeResults,
        List<RetrievedChunk> fusedResults
) {
    public record RetrievedChunk(
            int rank,
            String chunkId,
            String title,
            Integer position,
            Double vectorScore,
            String textPreview
    ) {
    }
}
