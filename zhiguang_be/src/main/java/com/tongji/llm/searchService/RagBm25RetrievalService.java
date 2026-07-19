package com.tongji.llm.searchService;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.config.EsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG BM25 关键词召回服务。
 * 查询 RAG chunk 索引中的 content 字段，补充向量召回不擅长的精确词命中。
 */
@Service
@RequiredArgsConstructor
public class RagBm25RetrievalService {
    private static final int FETCH_MULTIPLIER = 3;
    private static final int MAX_FETCH_SIZE = 50;
    private static final String SECTION_TEST_QUESTION = "TEST_QUESTION";
    private static final String SECTION_INTERVIEW_TEMPLATE = "INTERVIEW_TEMPLATE";

    private final ElasticsearchClient es;
    private final EsProperties esProps;

    public List<Document> search(Long postId, String query, int topK) {
        if (!StringUtils.hasText(query) || topK <= 0) {
            return List.of();
        }
        if (!StringUtils.hasText(esProps.getIndex())) {
            throw new BusinessException(ErrorCode.RAG_RETRIEVAL_FAILED, "未配置知识索引名称");
        }

        try {
            int fetchSize = Math.min(Math.max(topK * FETCH_MULTIPLIER, topK), MAX_FETCH_SIZE);
            var response = es.search(s -> s
                            .index(esProps.getIndex())
                            .size(fetchSize)
                            .query(q -> q.bool(b -> {
                                b.must(m -> m.match(mm -> mm
                                        .field("content")
                                        .query(query)));
                                if (postId != null) {
                                    // RAG chunk 的 postId 存在 metadata 里；单篇问答要限制在当前知文内。
                                    b.filter(f -> f.term(t -> t
                                            .field("metadata.postId")
                                            .value(v -> v.stringValue(String.valueOf(postId)))));
                                }
                                return b;
                            })),
                    Map.class);
            if (response.hits() == null || response.hits().hits() == null) {
                return List.of();
            }
            List<Document> docs = response.hits().hits().stream()
                    .map(this::toDocument)
                    .filter(document -> StringUtils.hasText(document.getText()))
                    .toList();
            return preferAnswerSections(docs, topK);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.RAG_RETRIEVAL_FAILED, "关键词知识检索暂时不可用");
        }
    }

    private List<Document> preferAnswerSections(List<Document> docs, int topK) {
        List<Document> preferred = new ArrayList<>();
        List<Document> interviewFallback = new ArrayList<>();
        List<Document> testFallback = new ArrayList<>();

        for (Document doc : docs) {
            String sectionType = stringValue(doc.getMetadata().get("sectionType"));
            if (SECTION_TEST_QUESTION.equals(sectionType)) {
                testFallback.add(doc);
            } else if (SECTION_INTERVIEW_TEMPLATE.equals(sectionType)) {
                interviewFallback.add(doc);
            } else {
                preferred.add(doc);
            }
        }

        /*
         * BM25 对重复关键词非常敏感，容易把“测试问题/面试模板”这类词密集 chunk 顶到 RRF 前面。
         * 这里不直接丢弃兜底 chunk：先给正文/概念/方案类 chunk 机会，不够 topK 时再补模板，最后才补测试题。
         */
        List<Document> result = new ArrayList<>(topK);
        appendUntilFull(result, preferred, topK);
        appendUntilFull(result, interviewFallback, topK);
        appendUntilFull(result, testFallback, topK);
        return result;
    }

    private void appendUntilFull(List<Document> target, List<Document> source, int topK) {
        for (Document doc : source) {
            if (target.size() >= topK) {
                return;
            }
            target.add(doc);
        }
    }

    @SuppressWarnings("unchecked")
    private Document toDocument(Hit<Map> hit) {
        Map source = hit.source();
        if (source == null) {
            return new Document("");
        }
        String content = stringValue(source.get("content"));
        Map<String, Object> metadata = new HashMap<>();
        Object metadataValue = source.get("metadata");
        if (metadataValue instanceof Map<?, ?> sourceMetadata) {
            sourceMetadata.forEach((key, value) -> {
                if (key != null) {
                    metadata.put(String.valueOf(key), value);
                }
            });
        }
        // 保留 BM25 原始分数，debug 时可以和向量召回分数区分观察。
        metadata.put("bm25Score", hit.score());
        return Document.builder()
                .text(content)
                .metadata(metadata)
                .score(hit.score())
                .build();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
