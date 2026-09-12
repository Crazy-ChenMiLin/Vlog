package com.tongji.llm.DTO;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 检索候选经过重排并筛选最终回答上下文后的结果。
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
