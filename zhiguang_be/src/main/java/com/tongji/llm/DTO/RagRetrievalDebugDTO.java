package com.tongji.llm.DTO;

import com.tongji.llm.graphService.model.GraphEntity;
import com.tongji.llm.graphService.model.GraphRelation;

import java.util.List;

/**
 * 单篇知文或全库 RAG 各检索阶段的只读调试视图。
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
            // 兼容历史字段名：向量结果为相似度，BM25 结果为 ES _score，均不是 RRF 分数。
            Double vectorScore,
            String sectionTitle,
            String sectionType,
            String questionIntent,
            String relationIntent,
            Double rerankScore,
            Double sectionBoost,
            Double graphBoost,
            Double finalScore,
            String textPreview
    ) {
    }

    public record GraphContextDebugDTO(
            List<GraphEntity> matchedEntities,
            List<GraphEntity> llmEntities,
            String relationIntent,
            String questionType,
            List<GraphRelation> relations,
            List<String> parentConcepts,
            List<String> expandedTerms
    ) {
        public GraphContextDebugDTO {
            matchedEntities = matchedEntities == null ? List.of() : List.copyOf(matchedEntities);
            llmEntities = llmEntities == null ? List.of() : List.copyOf(llmEntities);
            relations = relations == null ? List.of() : List.copyOf(relations);
            parentConcepts = parentConcepts == null ? List.of() : List.copyOf(parentConcepts);
            expandedTerms = expandedTerms == null ? List.of() : List.copyOf(expandedTerms);
        }
    }
}
