package com.tongji.llm.rag;

import com.tongji.llm.DTO.RagRetrievalResultDTO;
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

    public Flux<String> streamAnswerFlux(long postId, String question, int topK, int maxTokens) {
        RagRetrievalResultDTO retrieval = retrievalService.retrieve(postId, question, topK);
        List<String> contexts = retrieval.fusedDocs().stream()
                .map(Document::getText)
                .filter(StringUtils::hasText)
                .toList();
        if (contexts.isEmpty()) {
            return Flux.just("未找到与问题相关的当前文章内容，请换一种问法后再试。");
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
                        .build())
                .stream()
                .content();
    }
}
