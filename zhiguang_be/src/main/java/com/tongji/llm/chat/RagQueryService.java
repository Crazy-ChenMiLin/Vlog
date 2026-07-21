package com.tongji.llm.chat;

import com.tongji.llm.DTO.RagRetrievalResultDTO;
import com.tongji.llm.DTO.RagRetrievalResultRankDTO;
import com.tongji.llm.chat.model.RagChatRole;
import com.tongji.llm.chat.model.RagChatScope;
import com.tongji.llm.enhanceService.QueryRewriteService;
import com.tongji.llm.enhanceService.RerankService;
import com.tongji.llm.graphService.model.GraphContext;
import com.tongji.llm.memoryService.RagConversationMemoryService;
import com.tongji.llm.memoryService.model.RagConversation;
import com.tongji.llm.memoryService.model.RagMessage;
import com.tongji.llm.searchService.RagRetrievalService;
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
    private final ChatClient chatClient;
    private final RagRetrievalService retrievalService;
    private final RagConversationMemoryService memoryService;
    private final QueryRewriteService queryRewriteService;
    private final RerankService rerankService;

    public Flux<String> streamPostAnswerFlux(long postId, String question, int topK) {
        RagRetrievalResultDTO retrieval = retrievalService.retrieveForPost(postId, question, topK);
        RagRetrievalResultRankDTO ranked = rankRetrieval(question, retrieval, topK);
        return streamAnswerInternal(
                ranked.answerDocs(),
                retrieval.graphContext(),
                question,
                "未找到与问题相关的当前文章内容，请换一种问法后再试。"
        );
    }

    public Flux<String> streamGlobalAnswerFlux(String question, int topK) {
        RagRetrievalResultDTO retrieval = retrievalService.retrieveGlobal(question, topK);
        RagRetrievalResultRankDTO ranked = rankRetrieval(question, retrieval, topK);
        return streamAnswerInternal(
                ranked.answerDocs(),
                retrieval.graphContext(),
                question,
                "未找到与问题相关的知识库内容，请换一种问法后再试。"
        );
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
        RagRetrievalResultDTO retrieval = RagChatScope.POST.is(scope)
                ? retrievalService.retrieveForPost(postId, standaloneQuestion, topK)
                : retrievalService.retrieveGlobal(standaloneQuestion, topK);
        RagRetrievalResultRankDTO ranked = rankRetrieval(standaloneQuestion, retrieval, topK);

        memoryService.appendMessage(userId, conversation.getId(), RagChatRole.USER, originalQuestion);
        StringBuilder assistantAnswer = new StringBuilder();

        Flux<ServerSentEvent<String>> meta = Flux.just(ServerSentEvent.<String>builder()
                .event("meta")
                .data("{\"conversationId\":\"" + conversation.getId() + "\"}")
                .build());
        Flux<ServerSentEvent<String>> answer = streamAnswerInternal(
                ranked.answerDocs(),
                retrieval.graphContext(),
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
        return Flux.concat(meta, answer, done);
    }

    private RagRetrievalResultRankDTO rankRetrieval(String standaloneQuestion, RagRetrievalResultDTO retrieval, int topK) {
        List<Document> rerankedDocs = rerankService.rerank(
                standaloneQuestion,
                retrieval.fusedDocs(),
                topK,
                retrieval.graphContext()
        );
        if (rerankedDocs == null) {
            rerankedDocs = retrieval.fusedDocs().stream().limit(topK).toList();
        }
        return new RagRetrievalResultRankDTO(retrieval, rerankedDocs, rerankedDocs);
    }

    private Flux<String> streamAnswerInternal(
            List<Document> answerDocs,
            GraphContext graphContext,
            String question,
            String emptyResultMessage) {
        List<String> contexts = answerDocs.stream()
                .map(Document::getText)
                .filter(StringUtils::hasText)
                .toList();
        if (contexts.isEmpty()) {
            return Flux.just(emptyResultMessage);
        }

        String context = String.join("\n\n---\n\n", contexts);
        String system = "你是中文知识助手。只能依据提供的知识库上下文和 Neo4j graph trace 回答；无法确定时请说明不确定。";
        String user = "问题：\n" + question
                + graphTrace(graphContext)
                + "\n\n知识库上下文如下（可能不完整）：\n"
                + context
                + "\n\n请基于以上材料作答。";

        return chatClient
                .prompt()
                .system(system)
                .user(user)
                .options(OpenAiChatOptions.builder()
                        .temperature(0.2)
                        .build())
                .stream()
                .content();
    }

    private Flux<String> streamAnswerInternal(
            List<Document> answerDocs,
            GraphContext graphContext,
            String originalQuestion,
            String standaloneQuestion,
            List<RagMessage> recentMessages,
            String emptyResultMessage) {
        List<String> contexts = answerDocs.stream()
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
                + graphTrace(graphContext)
                + "\n\n知识库上下文如下（可能不完整）：\n" + context
                + "\n\n请基于以上材料回答用户当前原始问题。";

        return chatClient
                .prompt()
                .system(system)
                .user(user)
                .options(OpenAiChatOptions.builder()
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
        return RagChatScope.POST.is(scope)
                ? "未找到与问题相关的当前文章内容，请换一种问法后再试。"
                : "未找到与问题相关的知识库内容，请换一种问法后再试。";
    }
}
