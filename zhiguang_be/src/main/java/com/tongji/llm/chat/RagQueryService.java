package com.tongji.llm.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.llm.agent.RagMainAgent;
import com.tongji.llm.agent.state.RagAgentState;
import com.tongji.llm.agent.state.RagAgentStepTrace;
import com.tongji.llm.chat.dto.AgentStepEventDTO;
import com.tongji.llm.chat.model.RagChatRole;
import com.tongji.llm.chat.model.RagChatScope;
import com.tongji.llm.config.RagLlmProperties;
import com.tongji.llm.config.RagPromptService;
import com.tongji.llm.enhanceService.QueryRewriteService;
import com.tongji.llm.graphService.model.GraphContext;
import com.tongji.llm.memoryService.RagConversationMemoryService;
import com.tongji.llm.memoryService.model.RagConversation;
import com.tongji.llm.memoryService.model.RagMessage;
import com.tongji.llm.observability.assembler.RagRuntimeTranscriptAssembler;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptDTO;
import com.tongji.llm.observability.service.RagTranscriptRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeoutException;

@Service
@Slf4j
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
    private final RagPromptService ragPromptService;
    private final RagTranscriptRecorder transcriptRecorder;

    public Flux<String> streamPostAnswerFlux(long postId, String question, int topK) {
        RagAgentState state = ragMainAgent.run("post", postId, question, question, topK);
        return recordTranscriptOnComplete("post", state, streamAnswerInternal(state, question, EMPTY_POST_MESSAGE));
    }

    public Flux<String> streamPostAnswerFlux(long postId, String question, int topK, String evalRunId) {
        RagAgentState state = ragMainAgent.run("post", postId, question, question, topK, evalRunId);
        return recordTranscriptOnComplete("post", state, streamAnswerInternal(state, question, EMPTY_POST_MESSAGE));
    }

    public Flux<String> streamGlobalAnswerFlux(String question, int topK) {
        RagAgentState state = ragMainAgent.run("global", null, question, question, topK);
        return recordTranscriptOnComplete("global", state, streamAnswerInternal(state, question, EMPTY_GLOBAL_MESSAGE));
    }

    public Flux<String> streamGlobalAnswerFlux(String question, int topK, String evalRunId) {
        RagAgentState state = ragMainAgent.run("global", null, question, question, topK, evalRunId);
        return recordTranscriptOnComplete("global", state, streamAnswerInternal(state, question, EMPTY_GLOBAL_MESSAGE));
    }

    /**
     * 仅供内部 Benchmark 调用：执行一次全库 RAG，并返回同一次执行的完整 Transcript。
     *
     * <p>它不会走 HTTP，也不会重新请求 debug 接口。普通前端仍使用原有的流式方法。
     */
    public Mono<RagTranscriptDTO> generateGlobalTranscript(String question, int topK, String evalRunId) {
        RagAgentState state = ragMainAgent.run("global", null, question, question, topK, evalRunId);
        return streamAnswerInternal(state, question, EMPTY_GLOBAL_MESSAGE)
                .reduceWith(StringBuilder::new, StringBuilder::append)
                .map(answer -> {
                    String finalAnswer = answer.toString();
                    transcriptRecorder.recordCompleted("global", state, finalAnswer);
                    return RagRuntimeTranscriptAssembler.assemble("global", state, finalAnswer);
                });
    }

    public Flux<ServerSentEvent<String>> streamChatAnswerFlux(
            long userId,
            Long conversationId,
            String scope,
            Long postId,
            String originalQuestion,
            int topK) {
        return Flux.<ServerSentEvent<String>>create(sink -> {
                    AtomicBoolean cancelled = new AtomicBoolean(false);
                    sink.onCancel(() -> cancelled.set(true));
                    Schedulers.boundedElastic().schedule(() -> produceChatStream(
                            sink,
                            cancelled,
                            userId,
                            conversationId,
                            scope,
                            postId,
                            originalQuestion,
                            topK
                    ));
                })
                // With the initial Flux returned immediately, this protects each silent gap in the workflow.
                .timeout(Duration.ofSeconds(60))
                .onErrorResume(TimeoutException.class, e ->
                        Flux.just(errorEvent("408", "请求超时，请重试")))
                .onErrorResume(e ->
                        Flux.just(errorEvent("500", e.getMessage() == null ? "处理失败" : e.getMessage().replace("\"", "'"))));
    }

    private void produceChatStream(
            FluxSink<ServerSentEvent<String>> sink,
            AtomicBoolean cancelled,
            long userId,
            Long conversationId,
            String scope,
            Long postId,
            String originalQuestion,
            int topK) {
        try {
        log.info("streamChat [1/6] resolveConversation start, userId={}, conversationId={}", userId, conversationId);
        RagConversation conversation = memoryService.resolveConversation(conversationId, userId, scope, postId);
        emit(sink, cancelled, ServerSentEvent.<String>builder()
                .event("meta")
                .data("{\"conversationId\":\"" + conversation.getId() + "\"}")
                .build());
        log.info("streamChat [2/6] loadRecentMessages start, conversationId={}", conversation.getId());
        List<RagMessage> recentMessages = memoryService.loadRecentMessages(
                userId,
                conversation.getId(),
                RagConversationMemoryService.DEFAULT_HISTORY_LIMIT
        );
        log.info("streamChat [3/6] rewrite start, recentCount={}", recentMessages == null ? 0 : recentMessages.size());
        String standaloneQuestion = queryRewriteService.rewrite(originalQuestion, recentMessages);
        log.info("streamChat [4/6] ragMainAgent.run start, standalone={}", standaloneQuestion);
        RagAgentState state = ragMainAgent.run(
                RagChatScope.POST.is(scope) ? "post" : "global",
                postId,
                originalQuestion,
                standaloneQuestion,
                topK,
                null,
                (agentState, step) -> emit(sink, cancelled, agentStepEvent(agentState, step))
        );
        log.info("streamChat [5/6] appendMessage start, state.steps={}", state.steps() == null ? 0 : state.steps().size());
        memoryService.appendMessage(userId, conversation.getId(), RagChatRole.USER, originalQuestion);
        log.info("streamChat [6/6] answer stream subscribed");
        StringBuilder assistantAnswer = new StringBuilder();
        String emptyMsg = emptyResultMessage(scope);
        streamAnswerInternal(
                state,
                originalQuestion,
                standaloneQuestion,
                recentMessages,
                emptyMsg
        )
                .subscribe(
                        chunk -> {
                            assistantAnswer.append(chunk);
                            emit(sink, cancelled, ServerSentEvent.<String>builder()
                                    .event("message")
                                    .data(chunk)
                                    .build());
                        },
                        sink::error,
                        () -> {
                            if (!assistantAnswer.isEmpty()) {
                        memoryService.appendMessage(
                                userId,
                                conversation.getId(),
                                RagChatRole.ASSISTANT,
                                assistantAnswer.toString()
                        );
                            }
                            transcriptRecorder.recordCompleted(scope, state, assistantAnswer.toString());
                            emit(sink, cancelled, ServerSentEvent.<String>builder()
                                    .event("done")
                                    .data("{}")
                                    .build());
                            sink.complete();
                        }
                );
        } catch (Exception e) {
            sink.error(e);
        }
    }

    private Flux<String> recordTranscriptOnComplete(
            String scope,
            RagAgentState state,
            Flux<String> answerStream) {
        return Flux.defer(() -> {
            StringBuilder fullAnswer = new StringBuilder();
            return answerStream
                    .doOnNext(fullAnswer::append)
                    .doOnComplete(() -> transcriptRecorder.recordCompleted(scope, state, fullAnswer.toString()));
        });
    }

    private void emit(
            FluxSink<ServerSentEvent<String>> sink,
            AtomicBoolean cancelled,
            ServerSentEvent<String> event) {
        if (!cancelled.get()) {
            sink.next(event);
        }
    }

    private ServerSentEvent<String> agentStepEvent(RagAgentState state, RagAgentStepTrace step) {
        return ServerSentEvent.<String>builder()
                .event("agent_step")
                .data(toJson(AgentStepEventDTO.from(state.traceId(), step)))
                .build();
    }

    private ServerSentEvent<String> errorEvent(String code, String message) {
        return ServerSentEvent.<String>builder()
                .event("error")
                .data("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}")
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
        String system = ragPromptService.getSystemPrompt(RagPromptService.KEY_FINAL_ANSWER);
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
        String system = ragPromptService.getSystemPrompt(RagPromptService.KEY_FINAL_ANSWER_WITH_HISTORY);
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
