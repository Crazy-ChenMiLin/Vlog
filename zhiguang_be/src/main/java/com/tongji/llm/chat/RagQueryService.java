package com.tongji.llm.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.llm.agent.RagMainAgent;
import com.tongji.llm.agent.state.RagAgentState;
import com.tongji.llm.chat.dto.AgentStepEventDTO;
import com.tongji.llm.chat.model.RagChatRole;
import com.tongji.llm.chat.model.RagChatScope;
import com.tongji.llm.config.RagLlmProperties;
import com.tongji.llm.enhanceService.QueryRewriteService;
import com.tongji.llm.graphService.model.GraphContext;
import com.tongji.llm.memoryService.RagConversationMemoryService;
import com.tongji.llm.memoryService.model.RagConversation;
import com.tongji.llm.memoryService.model.RagMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RagQueryService {
    private static final String EMPTY_POST_MESSAGE =
            "\u672a\u627e\u5230\u4e0e\u95ee\u9898\u76f8\u5173\u7684\u5f53\u524d\u6587\u7ae0\u5185\u5bb9\uff0c\u8bf7\u6362\u4e00\u79cd\u95ee\u6cd5\u540e\u518d\u8bd5\u3002";
    private static final String EMPTY_GLOBAL_MESSAGE =
            "\u672a\u627e\u5230\u4e0e\u95ee\u9898\u76f8\u5173\u7684\u77e5\u8bc6\u5e93\u5185\u5bb9\uff0c\u8bf7\u6362\u4e00\u79cd\u95ee\u6cd5\u540e\u518d\u8bd5\u3002";

    private final ChatClient chatClient;
    private final RagMainAgent ragMainAgent;
    private final RagConversationMemoryService memoryService;
    private final QueryRewriteService queryRewriteService;
    private final ObjectMapper objectMapper;
    private final RagLlmProperties ragLlmProperties;

    public Flux<String> streamPostAnswerFlux(long postId, String question, int topK) {
        RagAgentState state = ragMainAgent.run("post", postId, question, question, topK);
        return streamAnswerInternal(state, question, EMPTY_POST_MESSAGE);
    }

    public Flux<String> streamPostAnswerFlux(long postId, String question, int topK, String evalRunId) {
        RagAgentState state = ragMainAgent.run("post", postId, question, question, topK, evalRunId);
        return streamAnswerInternal(state, question, EMPTY_POST_MESSAGE);
    }

    public Flux<String> streamGlobalAnswerFlux(String question, int topK) {
        RagAgentState state = ragMainAgent.run("global", null, question, question, topK);
        return streamAnswerInternal(state, question, EMPTY_GLOBAL_MESSAGE);
    }

    public Flux<String> streamGlobalAnswerFlux(String question, int topK, String evalRunId) {
        RagAgentState state = ragMainAgent.run("global", null, question, question, topK, evalRunId);
        return streamAnswerInternal(state, question, EMPTY_GLOBAL_MESSAGE);
    }

    public Flux<ServerSentEvent<String>> streamChatAnswerFlux(
            long userId,
            Long conversationId,
            String scope,
            Long postId,
            String originalQuestion,
            int topK) {
        RagConversation conversation = memoryService.resolveConversation(conversationId, userId, scope, postId);
        List<RagMessage> recentMessages = memoryService.loadRecentMessages(
                userId,
                conversation.getId(),
                RagConversationMemoryService.DEFAULT_HISTORY_LIMIT
        );
        String standaloneQuestion = queryRewriteService.rewrite(originalQuestion, recentMessages);
        RagAgentState state = ragMainAgent.run(
                RagChatScope.POST.is(scope) ? "post" : "global",
                postId,
                originalQuestion,
                standaloneQuestion,
                topK
        );

        memoryService.appendMessage(userId, conversation.getId(), RagChatRole.USER, originalQuestion);
        StringBuilder assistantAnswer = new StringBuilder();

        Flux<ServerSentEvent<String>> meta = Flux.just(ServerSentEvent.<String>builder()
                .event("meta")
                .data("{\"conversationId\":\"" + conversation.getId() + "\"}")
                .build());
        Flux<ServerSentEvent<AgentStepEventDTO>> agentSteps = agentStepEvents(state);
        Flux<ServerSentEvent<String>> answer = streamAnswerInternal(
                state,
                originalQuestion,
                standaloneQuestion,
                recentMessages,
                emptyResultMessage(scope)
        )
                .doOnNext(assistantAnswer::append)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .event("message")
                        .data(chunk)
                        .build())
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE && !assistantAnswer.isEmpty()) {
                        memoryService.appendMessage(
                                userId,
                                conversation.getId(),
                                RagChatRole.ASSISTANT,
                                assistantAnswer.toString()
                        );
                    }
                });
        Flux<ServerSentEvent<String>> done = Flux.just(ServerSentEvent.<String>builder()
                .event("done")
                .data("{}")
                .build());
        return Flux.concat(meta, agentSteps.map(this::stringEvent), answer, done);
    }

    private Flux<ServerSentEvent<AgentStepEventDTO>> agentStepEvents(RagAgentState state) {
        return Flux.fromIterable(state.steps())
                .map(step -> ServerSentEvent.<AgentStepEventDTO>builder()
                        .event("agent_step")
                        .data(AgentStepEventDTO.from(state.traceId(), step))
                        .build());
    }

    private ServerSentEvent<String> stringEvent(ServerSentEvent<AgentStepEventDTO> event) {
        AgentStepEventDTO data = event.data();
        return ServerSentEvent.<String>builder()
                .event(event.event())
                .data(toJson(data))
                .build();
    }

    private String toJson(AgentStepEventDTO event) {
        if (event == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return "{\"stepName\":\"agent_step\",\"title\":\"执行 Agent 步骤\",\"success\":false,\"summary\":\"serialize failed\"}";
        }
    }

    private Flux<String> streamAnswerInternal(
            RagAgentState state,
            String question,
            String emptyResultMessage) {
        if (StringUtils.hasText(state.finalAnswer())) {
            return Flux.just(state.finalAnswer());
        }

        List<String> contexts = state.answerDocs().stream()
                .map(Document::getText)
                .filter(StringUtils::hasText)
                .toList();
        if (contexts.isEmpty()) {
            return Flux.just(emptyResultMessage);
        }

        String context = String.join("\n\n---\n\n", contexts);
        String system = "你是中文知识助手。只能依据提供的知识库上下文和 Neo4j graph trace 回答；无法确定时请说明不确定。";
        String user = "问题：\n" + question
                + graphTrace(state.graphContext())
                + "\n\n知识库上下文如下（可能不完整）：\n"
                + context
                + "\n\n请基于以上材料作答。";

        return chatClient
                .prompt()
                .system(system)
                .user(user)
                .options(OpenAiChatOptions.builder()
                        .model(ragLlmProperties.finalAnswerModel())
                        .temperature(0.2)
                        .build())
                .stream()
                .content();
    }

    private Flux<String> streamAnswerInternal(
            RagAgentState state,
            String originalQuestion,
            String standaloneQuestion,
            List<RagMessage> recentMessages,
            String emptyResultMessage) {
        if (StringUtils.hasText(state.finalAnswer())) {
            return Flux.just(state.finalAnswer());
        }

        List<String> contexts = state.answerDocs().stream()
                .map(Document::getText)
                .filter(StringUtils::hasText)
                .toList();
        if (contexts.isEmpty()) {
            return Flux.just(emptyResultMessage);
        }

        String context = String.join("\n\n---\n\n", contexts);
        String system = """
                你是中文知识助手。对话历史只用于理解用户当前问题，改写问题只表示系统对当前问题的理解。
                最终答案必须基于提供的知识库上下文和 Neo4j graph trace；无法确定时请说明不确定。
                """;
        String user = "最近对话：\n" + formatHistory(recentMessages)
                + "\n\n用户当前原始问题：\n" + originalQuestion
                + "\n\n系统改写后的检索问题：\n" + standaloneQuestion
                + graphTrace(state.graphContext())
                + "\n\n知识库上下文如下（可能不完整）：\n" + context
                + "\n\n请基于以上材料回答用户当前原始问题。";

        return chatClient
                .prompt()
                .system(system)
                .user(user)
                .options(OpenAiChatOptions.builder()
                        .model(ragLlmProperties.finalAnswerModel())
                        .temperature(0.2)
                        .build())
                .stream()
                .content();
    }

    private String formatHistory(List<RagMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "（无）";
        }
        StringBuilder builder = new StringBuilder();
        for (RagMessage message : messages) {
            String role = RagChatRole.fromValue(message.getRole())
                    .map(RagChatRole::displayName)
                    .orElse("未知");
            builder.append(role).append("：")
                    .append(message.getContent())
                    .append('\n');
        }
        return builder.toString();
    }

    private String graphTrace(GraphContext graphContext) {
        if (graphContext == null || graphContext.isEmpty() || !StringUtils.hasText(graphContext.relationSummary())) {
            return "";
        }
        return "\n\nNeo4j graph trace:\n" + graphContext.relationSummary();
    }

    private String emptyResultMessage(String scope) {
        return RagChatScope.POST.is(scope) ? EMPTY_POST_MESSAGE : EMPTY_GLOBAL_MESSAGE;
    }
}
