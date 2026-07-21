package com.tongji.llm.searchService;

import com.tongji.llm.DTO.RagRetrievalDebugDTO;
import com.tongji.llm.DTO.RagRetrievalResultDTO;
import com.tongji.llm.debugservice.RagDebugService;
import com.tongji.llm.enhanceService.RerankService;
import com.tongji.llm.graphService.model.GraphContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagDebugServiceTest {

    @Mock
    private RagRetrievalService retrievalService;
    @Mock
    private RerankService rerankService;

    @Test
    void globalDebugResultIncludesScopeAndSourcePostId() {
        Document document = new Document("检索到的正文", Map.of(
                "postId", "456",
                "chunkId", "456#0",
                "title", "测试知文",
                "position", 0
        ));
        when(retrievalService.retrieveGlobal("问题", 5))
                .thenReturn(new RagRetrievalResultDTO(
                        "假设答案", 0.30, List.of(document), List.of(), List.of(), List.of(document)));
        when(rerankService.rerank(anyString(), eq(List.of(document)), eq(1), org.mockito.ArgumentMatchers.any(GraphContext.class)))
                .thenReturn(List.of(document));
        RagDebugService service = new RagDebugService(retrievalService, rerankService);

        RagRetrievalDebugDTO result = service.debugGlobalRetrieval("问题", 5);

        assertThat(result.scope()).isEqualTo("global");
        assertThat(result.postId()).isNull();
        assertThat(result.fusedResults()).singleElement().satisfies(chunk -> {
            assertThat(chunk.postId()).isEqualTo("456");
            assertThat(chunk.chunkId()).isEqualTo("456#0");
        });
        assertThat(result.keywordResults()).isEmpty();
        assertThat(result.graphContext().matchedEntities()).isEmpty();
        assertThat(result.rerankedResults()).hasSize(1);
        assertThat(result.answerResults()).hasSize(1);
    }
}
