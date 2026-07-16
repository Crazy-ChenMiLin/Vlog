package com.tongji.llm.DTO;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Result after retrieval candidates are reranked and selected for final answer context.
 */
public record RagRetrievalResultRankDTO(
        RagRetrievalResultDTO retrieval,
        List<Document> rerankedDocs,
        List<Document> answerDocs
) {
    public RagRetrievalResultRankDTO {
        rerankedDocs = List.copyOf(rerankedDocs);
        answerDocs = List.copyOf(answerDocs);
    }
}
