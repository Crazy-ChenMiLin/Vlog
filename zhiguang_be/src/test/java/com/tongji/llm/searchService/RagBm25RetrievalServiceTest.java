package com.tongji.llm.searchService;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import com.tongji.common.exception.BusinessException;
import com.tongji.config.EsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagBm25RetrievalServiceTest {
    @Mock
    private ElasticsearchClient es;

    @Test
    @SuppressWarnings("unchecked")
    void searchMapsEsHitToDocument() throws IOException {
        EsProperties props = new EsProperties();
        props.setIndex("zhiguang-ai-index");
        Map<String, Object> source = Map.of(
                "content", "BM25 命中的正文",
                "metadata", Map.of(
                        "postId", "123",
                        "chunkId", "123#0",
                        "title", "测试知文"
                )
        );
        SearchResponse<Map> response = SearchResponse.of(r -> r
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(HitsMetadata.of(h -> h
                        .total(t -> t.value(1).relation(TotalHitsRelation.Eq))
                        .hits(List.of(Hit.of(hit -> hit
                                .index("zhiguang-ai-index")
                                .id("123#0")
                                .score(7.5)
                                .source(source)))))));
        when(es.search(any(java.util.function.Function.class), eq(Map.class))).thenReturn(response);
        RagBm25RetrievalService service = new RagBm25RetrievalService(es, props);

        List<Document> result = service.search(123L, "BM25", 5);

        assertThat(result).singleElement().satisfies(document -> {
            assertThat(document.getText()).isEqualTo("BM25 命中的正文");
            assertThat(document.getMetadata()).containsEntry("postId", "123");
            assertThat(document.getMetadata()).containsEntry("chunkId", "123#0");
            assertThat(document.getMetadata()).containsEntry("bm25Score", 7.5);
            assertThat(document.getScore()).isEqualTo(7.5);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchPrefersAnswerSectionsBeforeTemplateAndTestQuestion() throws IOException {
        EsProperties props = new EsProperties();
        props.setIndex("zhiguang-ai-index");
        SearchResponse<Map> response = SearchResponse.of(r -> r
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(HitsMetadata.of(h -> h
                        .total(t -> t.value(4).relation(TotalHitsRelation.Eq))
                        .hits(List.of(
                                hit("test#6", "测试题 chunk", "TEST_QUESTION", 10.0),
                                hit("tpl#5", "面试模板 chunk", "INTERVIEW_TEMPLATE", 9.0),
                                hit("concept#2", "核心概念 chunk", "CONCEPT", 8.0),
                                hit("solution#3", "解决方案 chunk", "SOLUTION", 7.0)
                        )))));
        when(es.search(any(java.util.function.Function.class), eq(Map.class))).thenReturn(response);
        RagBm25RetrievalService service = new RagBm25RetrievalService(es, props);

        List<Document> result = service.search(null, "关键词", 3);

        assertThat(result)
                .extracting(document -> document.getMetadata().get("chunkId"))
                .containsExactly("concept#2", "solution#3", "tpl#5");
    }

    @Test
    void missingIndexFailsFast() {
        RagBm25RetrievalService service = new RagBm25RetrievalService(es, new EsProperties());

        assertThatThrownBy(() -> service.search(null, "问题", 5))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未配置知识索引名称");
    }

    private Hit<Map> hit(String chunkId, String content, String sectionType, double score) {
        return Hit.of(hit -> hit
                .index("zhiguang-ai-index")
                .id(chunkId)
                .score(score)
                .source(Map.of(
                        "content", content,
                        "metadata", Map.of(
                                "postId", "123",
                                "chunkId", chunkId,
                                "sectionType", sectionType,
                                "title", "测试知文"
                        )
                )));
    }
}
