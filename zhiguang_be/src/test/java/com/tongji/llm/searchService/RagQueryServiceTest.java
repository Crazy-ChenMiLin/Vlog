package com.tongji.llm.searchService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.llm.agent.RagMainAgent;
import com.tongji.llm.agent.model.RagAgentState;
import com.tongji.llm.agent.model.RagAgentStepTrace;
import com.tongji.llm.chat.RagQueryService;
import com.tongji.llm.chat.model.RagChatRole;
import com.tongji.llm.enhanceService.QueryRewriteService;
import com.tongji.llm.memoryService.RagConversationMemoryService;
import com.tongji.llm.memoryService.model.RagConversation;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagQueryServiceTest {

    @Mock
    private ChatClient chatClient;
    @Mock
    private RagMainAgent ragMainAgent;
    @Mock
    private RagConversationMemoryService memoryService;
    @Mock
    private QueryRewriteService queryRewriteService;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.StreamResponseSpec streamResponseSpec;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void emptyRetrievalReturnsFriendlyMessageWithoutCallingLlm() {
        when(ragMainAgent.run("post", 123L, "问题", "问题", 5))
                .thenReturn(state("问题", 5, List.of()));
        RagQueryService service = createService();

        List<String> result = service.streamPostAnswerFlux(123L, "问题", 5)
                .collectList()
                .block();

        assertThat(result).containsExactly("未找到与问题相关的当前文章内容，请换一种问法后再试。");
        verifyNoInteractions(chatClient);
    }

    @Test
    void emptyGlobalRetrievalReturnsKnowledgeBaseMessage() {
        when(ragMainAgent.run("global", null, "问题", "问题", 5))
                .thenReturn(state("问题", 5, List.of()));
        RagQueryService service = createService();

        List<String> result = service.streamGlobalAnswerFlux("问题", 5)
                .collectList()
                .block();

        assertThat(result).containsExactly("未找到与问题相关的知识库内容，请换一种问法后再试。");
        verify(ragMainAgent).run("global", null, "问题", "问题", 5);
        verifyNoInteractions(chatClient);
    }

    @Test
    void agentFailureStopsBeforeCallingAnswerLlm() {
        when(ragMainAgent.run("post", 123L, "问题", "问题", 5))
                .thenThrow(new BusinessException(ErrorCode.RAG_RETRIEVAL_FAILED));
        RagQueryService service = createService();

        assertThatThrownBy(() -> service.streamPostAnswerFlux(123L, "问题", 5))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(chatClient);
    }

    @Test
    void answerOptionsDoNotSendMaxTokensForGatewayCompatibility() {
        Document document = new Document("可用于回答的上下文");
        when(ragMainAgent.run("global", null, "问题", "问题", 5))
                .thenReturn(state("问题", 5, List.of(document)));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(OpenAiChatOptions.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.just("回答"));
        RagQueryService service = createService();

        assertThat(service.streamGlobalAnswerFlux("问题", 5).collectList().block())
                .containsExactly("回答");

        var optionsCaptor = org.mockito.ArgumentCaptor.forClass(OpenAiChatOptions.class);
        verify(requestSpec).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getMaxCompletionTokens()).isNull();
        assertThat(optionsCaptor.getValue().getMaxTokens()).isNull();
        verify(requestSpec, times(1)).stream();
    }

    @Test
    void chatStreamEmitsAgentStepBeforeAnswerAndStillStoresMessages() {
        RagConversation conversation = new RagConversation();
        conversation.setId(99L);
        when(memoryService.resolveConversation(1L, 7L, "global", null)).thenReturn(conversation);
        when(memoryService.loadRecentMessages(7L, 99L, RagConversationMemoryService.DEFAULT_HISTORY_LIMIT))
                .thenReturn(List.of());
        when(queryRewriteService.rewrite("原问题", List.of())).thenReturn("改写问题");

        RagAgentState state = state("改写问题", 5, List.of());
        state.addStep(new RagAgentStepTrace("plan", "PLANNER", true, 12, "NORMAL_QA/HYBRID"));
        state.finalAnswer("直接回答");
        when(ragMainAgent.run("global", null, "原问题", "改写问题", 5)).thenReturn(state);

        RagQueryService service = createService();
        List<String> events = service.streamChatAnswerFlux(7L, 1L, "global", null, "原问题", 5)
                .map(event -> event.event() + ":" + event.data())
                .collectList()
                .block();

        assertThat(events).containsExactly(
                "meta:{\"conversationId\":\"99\"}",
                "agent_step:{\"traceId\":\"" + state.traceId() + "\",\"stepName\":\"plan\",\"title\":\"理解问题并制定计划\",\"decision\":\"PLANNER\",\"success\":true,\"costMs\":12,\"summary\":\"NORMAL_QA/HYBRID\"}",
                "message:直接回答",
                "done:{}"
        );
        verify(memoryService).appendMessage(7L, 99L, RagChatRole.USER, "原问题");
        verify(memoryService).appendMessage(7L, 99L, RagChatRole.ASSISTANT, "直接回答");
    }

    private RagQueryService createService() {
        return new RagQueryService(chatClient, ragMainAgent, memoryService, queryRewriteService, objectMapper);
    }

    private RagAgentState state(String question, int topK, List<Document> answerDocs) {
        RagAgentState state = new RagAgentState(question, question, topK);
        state.answerDocs(answerDocs);
        return state;
    }
}
