package com.tongji.llm.enhanceService;

import com.tongji.llm.config.RagLlmProperties;
import com.tongji.llm.config.RagPromptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * HyDE 查询转换服务：生成一段接近知识库正文的假设性答案，仅用于向量检索。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HyDEService {
    private final ChatClient chatClient;
    private final RagLlmProperties ragLlmProperties;
    private final RagPromptService ragPromptService;

    /**
     * 生成用于检索的假设性答案。生成失败时返回 null，由调用方退回原问题。
     */
    public String generateHypotheticalAnswer(String question) {
        if (!StringUtils.hasText(question)) {
            return null;
        }

        String system = ragPromptService.getSystemPrompt(RagPromptService.KEY_HYDE);
        String user = "用户问题：" + question.trim() + "\n\n请直接输出用于检索的假设性答案。";

        try {
            String answer = chatClient
                    .prompt()
                    .system(system)
                    .user(user)
                    .options(OpenAiChatOptions.builder()
                            .model(ragLlmProperties.hydeModel())
                            .temperature(0.3)
                            .build())
                    .call()
                    .content();
            return StringUtils.hasText(answer) ? answer.trim() : null;
        } catch (Exception e) {
            log.warn("HyDE generation failed, fallback to original question: {}", e.getMessage());
            return null;
        }
    }
}
