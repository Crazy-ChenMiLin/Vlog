package com.tongji.llm.searchService;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.llm.DTO.RagRetrievalResultDTO;
import com.tongji.llm.enhanceService.HyDEService;
import com.tongji.llm.enhanceService.RrfFusionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagRetrievalServiceTest {

    @Mock
    private RagIndexService indexService;
    @Mock
    private RagVectorRetrievalService vectorRetrievalService;
    @Mock
    private RagBm25RetrievalService bm25RetrievalService;
    @Mock
    private HyDEService hydeService;
    @Mock
    private RrfFusionService rrfFusion;

    @Test
    void retrievalUsesOriginalHydeAndBm25RoutesWhenEnabled() {
        Document original = document("1#0");
        Document hyde = document("1#1");
        Document keyword = document("1#2");
        Document fused = document("1#3");
        when(hydeService.generateHypotheticalAnswer("问题")).thenReturn("用于检索的假设性答案");
        when(vectorRetrievalService.search(123L, "问题", 5)).thenReturn(List.of(original));
        when(vectorRetrievalService.search(123L, "用于检索的假设性答案", 5)).thenReturn(List.of(hyde));
        when(bm25RetrievalService.search(123L, "问题", 5)).thenReturn(List.of(keyword));
        when(rrfFusion.fuse(List.of(List.of(original), List.of(hyde), List.of(keyword)), 5))
                .thenReturn(List.of(fused));
        RagRetrievalService service = createService(true);

        RagRetrievalResultDTO result = service.retrieveForPost(123L, "问题", 5);

        assertThat(result.originalDocs()).containsExactly(original);
        assertThat(result.hydeDocs()).containsExactly(hyde);
        assertThat(result.keywordDocs()).containsExactly(keyword);
        assertThat(result.fusedDocs()).containsExactly(fused);
        verify(indexService).ensureIndexed(123L);
    }

    @Test
    void bm25RouteCanBeDisabled() {
        Document original = document("1#0");
        when(hydeService.generateHypotheticalAnswer("问题")).thenReturn(null);
        when(vectorRetrievalService.search(null, "问题", 5)).thenReturn(List.of(original));
        RagRetrievalService service = createService(false);

        RagRetrievalResultDTO result = service.retrieveGlobal("问题", 5);

        assertThat(result.keywordDocs()).isEmpty();
        assertThat(result.fusedDocs()).containsExactly(original);
        verifyNoInteractions(indexService, bm25RetrievalService, rrfFusion);
    }

    @Test
    void indexingFailureStopsBeforeRetrieval() {
        doThrow(new BusinessException(ErrorCode.RAG_INDEX_FAILED))
                .when(indexService).ensureIndexed(123L);
        RagRetrievalService service = createService(true);

        assertThatThrownBy(() -> service.retrieveForPost(123L, "问题", 5))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(vectorRetrievalService, bm25RetrievalService, hydeService, rrfFusion);
    }

    @Test
    void hydeFailureFallsBackToOriginalAndBm25Question() {
        Document original = document("1#0");
        Document keyword = document("1#2");
        when(hydeService.generateHypotheticalAnswer("问题")).thenReturn(null);
        when(vectorRetrievalService.search(null, "问题", 5)).thenReturn(List.of(original));
        when(bm25RetrievalService.search(null, "问题", 5)).thenReturn(List.of(keyword));
        when(rrfFusion.fuse(List.of(List.of(original), List.of(keyword)), 5))
                .thenReturn(List.of(keyword, original));
        RagRetrievalService service = createService(true);

        RagRetrievalResultDTO result = service.retrieveGlobal("问题", 5);

        assertThat(result.hydeDocs()).isEmpty();
        assertThat(result.fusedDocs()).containsExactly(keyword, original);
        verify(vectorRetrievalService, never()).search(null, null, 5);
    }

    private RagRetrievalService createService(boolean bm25Enabled) {
        RagRetrievalService service = new RagRetrievalService(
                indexService,
                vectorRetrievalService,
                bm25RetrievalService,
                hydeService,
                rrfFusion
        );
        ReflectionTestUtils.setField(service, "bm25Enabled", bm25Enabled);
        return service;
    }

    private Document document(String chunkId) {
        return new Document("正文 " + chunkId, Map.of("postId", "1", "chunkId", chunkId));
    }
}
