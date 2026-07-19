package com.tongji.llm.searchService;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * RAG 向量召回服务。
 * 当前底层通过 Spring AI Elasticsearch VectorStore 做 kNN 检索，使用 cosine 相似度。
 */
@Service
@RequiredArgsConstructor
public class RagVectorRetrievalService {
    public static final double MIN_SIMILARITY_SCORE = 0.30;

    private final VectorStore vectorStore;

    public List<Document> search(Long postId, String query, int topK) {
        if (!StringUtils.hasText(query) || topK <= 0) {
            return List.of();
        }
        try {
            var requestBuilder = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(MIN_SIMILARITY_SCORE);
            if (postId != null) {
                var postFilter = new FilterExpressionBuilder()
                        .eq("postId", String.valueOf(postId))
                        .build();
                requestBuilder.filterExpression(postFilter);
            }
            List<Document> docs = vectorStore.similaritySearch(requestBuilder.build());
            return filterTextDocs(docs);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.RAG_RETRIEVAL_FAILED, "知识检索暂时不可用");
        }
    }

    private List<Document> filterTextDocs(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        return docs.stream()
                .filter(document -> StringUtils.hasText(document.getText()))
                .toList();
    }
}
