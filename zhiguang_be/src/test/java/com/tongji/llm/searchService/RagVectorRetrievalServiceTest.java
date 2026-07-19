package com.tongji.llm.searchService;

import com.tongji.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagVectorRetrievalServiceTest {
    @Mock
    private VectorStore vectorStore;

    @Test
    void postSearchUsesPostFilterAndCosineThreshold() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("正文")));
        RagVectorRetrievalService service = new RagVectorRetrievalService(vectorStore);

        List<Document> result = service.search(123L, "问题", 5);

        assertThat(result).hasSize(1);
        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getQuery()).isEqualTo("问题");
        assertThat(requestCaptor.getValue().getTopK()).isEqualTo(5);
        assertThat(requestCaptor.getValue().getSimilarityThreshold()).isEqualTo(0.30);
        assertThat(requestCaptor.getValue().hasFilterExpression()).isTrue();
    }

    @Test
    void globalSearchDoesNotUsePostFilter() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        RagVectorRetrievalService service = new RagVectorRetrievalService(vectorStore);

        service.search(null, "问题", 5);

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(requestCaptor.capture());
        assertThat(requestCaptor.getValue().hasFilterExpression()).isFalse();
    }

    @Test
    void vectorStoreFailureIsMappedToBusinessError() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("es unavailable"));
        RagVectorRetrievalService service = new RagVectorRetrievalService(vectorStore);

        assertThatThrownBy(() -> service.search(123L, "问题", 5))
                .isInstanceOf(BusinessException.class)
                .hasMessage("知识检索暂时不可用");
    }
}
