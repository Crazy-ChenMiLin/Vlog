package com.tongji.llm.enhanceService;

import com.tongji.llm.chat.model.RagChatRole;
import com.tongji.llm.config.RagLlmProperties;
import com.tongji.llm.config.RagPromptService;
import com.tongji.llm.memoryService.model.RagMessage;
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
    private final RagLlmProperties ragLlmProperties;
    private final RagPromptService ragPromptService;

    public String rewrite(String originalQuestion, List<RagMessage> recentMessages) {
        if (!StringUtils.hasText(originalQuestion) || recentMessages == null || recentMessages.isEmpty()) {
            return originalQuestion;
        }

        String system = ragPromptService.getSystemPrompt(RagPromptService.KEY_REWRITE);
        String user = "最近对话：\n" + formatHistory(recentMessages)
                + "\n\n用户当前问题：\n" + originalQuestion.trim()
                + "\n\n请输出独立检索问题：";

        try {
            String standaloneQuestion = chatClient
                    .prompt()
                    .system(system)
                    .user(user)
                    .options(OpenAiChatOptions.builder()
                            .model(ragLlmProperties.rewriteModel())
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
            String role = RagChatRole.fromValue(message.getRole())
                    .map(RagChatRole::displayName)
                    .orElse("未知");
            builder.append(role).append("：")
                    .append(message.getContent())
                    .append('\n');
        }
        return builder.toString();
    }
}
