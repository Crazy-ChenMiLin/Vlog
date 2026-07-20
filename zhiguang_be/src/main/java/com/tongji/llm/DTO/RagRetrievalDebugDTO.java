package com.tongji.llm.DTO;

import com.tongji.llm.graphService.model.GraphEntity;
import com.tongji.llm.graphService.model.GraphRelation;

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
        GraphContextDebugDTO graphContext,
        List<RetrievedChunk> originalResults,
        List<RetrievedChunk> hydeResults,
        List<RetrievedChunk> keywordResults,
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
            Double vectorScore,// 历史字段名：向量结果是相似度分数，BM25 结果是 ES _score；不是 RRF 分数。
            String sectionTitle,
            String sectionType,
            String questionIntent,
            Double rerankScore,
            Double sectionBoost,
            Double finalScore,
            String textPreview
    ) {
    }

    public record GraphContextDebugDTO(
            List<GraphEntity> matchedEntities,
            List<GraphRelation> relations,
            List<String> parentConcepts,
            List<String> expandedTerms
    ) {
        public GraphContextDebugDTO {
            matchedEntities = matchedEntities == null ? List.of() : List.copyOf(matchedEntities);
            relations = relations == null ? List.of() : List.copyOf(relations);
            parentConcepts = parentConcepts == null ? List.of() : List.copyOf(parentConcepts);
            expandedTerms = expandedTerms == null ? List.of() : List.copyOf(expandedTerms);
        }
    }
}
