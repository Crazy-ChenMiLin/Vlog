package com.tongji.llm.rag;

import com.tongji.llm.DTO.RagRetrievalResultDTO;
import com.tongji.llm.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 使用检索结果构造提示词，并通过大模型流式生成最终回答。
 */
@Service
@RequiredArgsConstructor
public class RagQueryService {
    private final ChatClient chatClient;
    private final RagRetrievalService retrievalService;
    private final RagProperties ragProperties;

    public Flux<String> streamPostAnswerFlux(long postId, String question, int topK) {
        RagRetrievalResultDTO retrieval = retrievalService.retrieveForPost(postId, question, topK);
        return streamAnswerInternal(retrieval, question,
                "未找到与问题相关的当前文章内容，请换一种问法后再试。");
    }

    public Flux<String> streamGlobalAnswerFlux(String question, int topK) {
        RagRetrievalResultDTO retrieval = retrievalService.retrieveGlobal(question, topK);
        return streamAnswerInternal(retrieval, question,
                "未找到与问题相关的知识库内容，请换一种问法后再试。");
    }

    private Flux<String> streamAnswerInternal(
            RagRetrievalResultDTO retrieval,
            String question,
            String emptyResultMessage) {
        List<String> contexts = retrieval.fusedDocs().stream()
                .map(Document::getText)
                .filter(StringUtils::hasText)
                .toList();
        if (contexts.isEmpty()) {
            return Flux.just(emptyResultMessage);
        }

        String context = String.join("\n\n---\n\n", contexts);
        String system = "你是中文知识助手。只能依据提供的知文上下文回答；无法确定的请说明不确定。";
        String user = "问题：" + question + "\n\n上下文如下（可能不完整）：\n"
                + context + "\n\n请基于以上上下文作答。";

        return chatClient
                .prompt()
                .system(system)
                .user(user)
                .options(OpenAiChatOptions.builder()
                        .temperature(0.2)
                        .maxCompletionTokens(ragProperties.getAnswer().getMaxTokens())
                        .build())
                .stream()
                .content();
    }
}
