package com.tongji.llm.DTO;

import java.util.List;

/**
 * Read-only view of the three retrieval stages used by single-post or global RAG.
 */
public record RagRetrievalDebugDTO(
        String scope,
        Long postId,
        String question,
        String hypotheticalAnswer,
        double similarityThreshold,
        List<RetrievedChunk> originalResults,
        List<RetrievedChunk> hydeResults,
        List<RetrievedChunk> fusedResults,
        List<RetrievedChunk> rerankedResults,
        List<RetrievedChunk> answerResults
) {
    public record RetrievedChunk(
            int rank,
            String postId,
            String chunkId,
            String title,
            Integer position,
            Double vectorScore,//得分向量检索返回的相似度分数，不是rrf得分
            String textPreview
    ) {
    }
}
