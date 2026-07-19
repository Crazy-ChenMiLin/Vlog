package com.tongji.llm.searchService;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.llm.enhanceService.HyDEService;
import com.tongji.llm.enhanceService.RrfFusionService;
import com.tongji.llm.DTO.RagRetrievalResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 负责单篇或全库的原问题向量检索、HyDE 向量检索和 RRF 融合。
 * 底层通过 Spring AI Elasticsearch VectorStore 做 kNN 检索，当前使用 cosine 相似度。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrievalService {
    private static final double MIN_SIMILARITY_SCORE = 0.30;

    private final VectorStore vectorStore;
    private final RagIndexService indexService;
    private final HyDEService hydeService;
    private final RrfFusionService rrfFusion;

    public RagRetrievalResultDTO retrieveForPost(long postId, String question, int topK) {
        indexService.ensureIndexed(postId);
        return retrieveInternal(postId, question, topK);
    }

    public RagRetrievalResultDTO retrieveGlobal(String question, int topK) {
        return retrieveInternal(null, question, topK);
    }

    private RagRetrievalResultDTO retrieveInternal(Long postId, String question, int topK) {
        //hyde生成
        String hypotheticalAnswer = hydeService.generateHypotheticalAnswer(question);
        List<Document> originalDocs = searchDocuments(postId, question, topK);
        List<Document> hydeDocs = StringUtils.hasText(hypotheticalAnswer)
                ? searchDocuments(postId, hypotheticalAnswer, topK)
                : List.of();
        // 两路召回都是向量检索：原问题召回 + HyDE 假设答案召回。
        // RRF（Reciprocal Rank Fusion，倒数排名融合）只负责融合排名，不代表 BM25 混合召回。
        List<Document> fusedDocs = hydeDocs.isEmpty()
                ? originalDocs.stream().limit(topK).toList()
                : rrfFusion.fuse(List.of(originalDocs, hydeDocs), topK);

        String scope = postId == null ? "global" : "post:" + postId;
        log.info("RAG retrieval scope={} original={} hyde={} fused={}",
                scope, chunkIds(originalDocs), chunkIds(hydeDocs), chunkIds(fusedDocs));
        return new RagRetrievalResultDTO(
                hypotheticalAnswer,
                MIN_SIMILARITY_SCORE,
                originalDocs,
                hydeDocs,
                fusedDocs
        );
    }

    private List<Document> searchDocuments(Long postId, String query, int topK) {
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
            if (docs == null || docs.isEmpty()) {
                return List.of();
            }
            return docs.stream()
                    .filter(document -> StringUtils.hasText(document.getText()))
                    .toList();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.RAG_RETRIEVAL_FAILED, "知识检索暂时不可用");
        }
    }

    private List<String> chunkIds(List<Document> docs) {
        return docs.stream()
                .map(document -> String.valueOf(document.getMetadata().get("chunkId")))
                .toList();
    }
}
