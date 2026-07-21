package com.tongji.llm.searchService;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.llm.DTO.RagRetrievalResultDTO;
import com.tongji.llm.chat.RagQueryService;
import com.tongji.llm.enhanceService.QueryRewriteService;
import com.tongji.llm.enhanceService.RerankService;
import com.tongji.llm.graphService.model.GraphContext;
import com.tongji.llm.memoryService.RagConversationMemoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagQueryServiceTest {

    @Mock
    private ChatClient chatClient;
    @Mock
    private RagRetrievalService retrievalService;
    @Mock
    private RagConversationMemoryService memoryService;
    @Mock
    private QueryRewriteService queryRewriteService;
    @Mock
    private RerankService rerankService;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.StreamResponseSpec streamResponseSpec;

    @Test
    void emptyRetrievalReturnsFriendlyMessageWithoutCallingLlm() {
        when(retrievalService.retrieveForPost(123L, "问题", 5))
                .thenReturn(new RagRetrievalResultDTO(null, 0.30, List.of(), List.of(), List.of(), List.of()));
        RagQueryService service = createService();

        List<String> result = service.streamPostAnswerFlux(123L, "问题", 5)
                .collectList()
                .block();

        assertThat(result).containsExactly("未找到与问题相关的当前文章内容，请换一种问法后再试。");
        verifyNoInteractions(chatClient);
    }

    @Test
    void emptyGlobalRetrievalReturnsKnowledgeBaseMessage() {
        when(retrievalService.retrieveGlobal("问题", 5))
                .thenReturn(new RagRetrievalResultDTO(null, 0.30, List.of(), List.of(), List.of(), List.of()));
        RagQueryService service = createService();

        List<String> result = service.streamGlobalAnswerFlux("问题", 5)
                .collectList()
                .block();

        assertThat(result).containsExactly("未找到与问题相关的知识库内容，请换一种问法后再试。");
        verify(retrievalService).retrieveGlobal("问题", 5);
        verifyNoInteractions(chatClient);
    }

    @Test
    void retrievalFailureStopsBeforeCallingLlm() {
        when(retrievalService.retrieveForPost(123L, "问题", 5))
                .thenThrow(new BusinessException(ErrorCode.RAG_RETRIEVAL_FAILED));
        RagQueryService service = createService();

        assertThatThrownBy(() -> service.streamPostAnswerFlux(123L, "问题", 5))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(chatClient);
    }

    @Test
    void answerOptionsDoNotSendMaxTokensForGatewayCompatibility() {
        Document document = new Document("可用于回答的上下文");
        when(retrievalService.retrieveGlobal("问题", 5))
                .thenReturn(new RagRetrievalResultDTO(
                        null, 0.30, List.of(document), List.of(), List.of(), List.of(document)));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(OpenAiChatOptions.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.just("回答"));
        when(rerankService.rerank(anyString(), eq(List.of(document)), eq(5), org.mockito.ArgumentMatchers.any(GraphContext.class)))
                .thenReturn(List.of(document));
        RagQueryService service = new RagQueryService(chatClient, retrievalService, memoryService, queryRewriteService, rerankService);

        assertThat(service.streamGlobalAnswerFlux("问题", 5).collectList().block())
                .containsExactly("回答");

        var optionsCaptor = org.mockito.ArgumentCaptor.forClass(OpenAiChatOptions.class);
        verify(requestSpec).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getMaxCompletionTokens()).isNull();
        assertThat(optionsCaptor.getValue().getMaxTokens()).isNull();
        verify(requestSpec, times(1)).stream();
    }

    private RagQueryService createService() {
        return new RagQueryService(chatClient, retrievalService, memoryService, queryRewriteService, rerankService);
    }
}
