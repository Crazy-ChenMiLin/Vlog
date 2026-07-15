package com.tongji.llm.rag;

import com.tongji.llm.rag.model.RagMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriteService {
    private final ChatClient chatClient;

    public String rewrite(String originalQuestion, List<RagMessage> recentMessages) {
        if (!StringUtils.hasText(originalQuestion) || recentMessages == null || recentMessages.isEmpty()) {
            return originalQuestion;
        }

        String system = """
                你是 RAG 查询改写器。根据最近对话历史，把用户当前问题改写成一个独立、完整、适合向量检索的中文问题。
                只输出改写后的问题，不要回答问题，不要解释，不要添加编号。
                如果当前问题已经完整，原样或轻微补全后输出。
                """;
        String user = "最近对话：\n" + formatHistory(recentMessages)
                + "\n\n用户当前问题：\n" + originalQuestion.trim()
                + "\n\n请输出独立检索问题：";

        try {
            String standaloneQuestion = chatClient
                    .prompt()
                    .system(system)
                    .user(user)
                    .options(OpenAiChatOptions.builder()
                            .temperature(0.0)
                            .build())
                    .call()
                    .content();
            return StringUtils.hasText(standaloneQuestion) ? standaloneQuestion.trim() : originalQuestion;
        } catch (Exception e) {
            log.warn("Query rewrite failed, fallback to original question: {}", e.getMessage());
            return originalQuestion;
        }
    }

    private String formatHistory(List<RagMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (RagMessage message : messages) {
            String role = RagChatRole.USER.equals(message.getRole()) ? "用户" : "助手";
            builder.append(role).append("：")
                    .append(message.getContent())
                    .append('\n');
        }
        return builder.toString();
    }
}
