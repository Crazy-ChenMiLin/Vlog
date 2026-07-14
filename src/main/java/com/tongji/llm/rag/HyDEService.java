package com.tongji.llm.rag;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * HyDE 查询转换服务：生成一段接近知识库正文的假设性答案，仅用于向量检索。
 */
@Service
@RequiredArgsConstructor
public class HyDEService {
    private static final Logger log = LoggerFactory.getLogger(HyDEService.class);

    private final ChatClient chatClient;

    /**
     * 生成用于检索的假设性答案。生成失败时返回 null，由调用方退回原问题。
     */
    public String generateHypotheticalAnswer(String question) {
        if (!StringUtils.hasText(question)) {
            return null;
        }

        String system = "你是知识库检索查询转换器。根据用户问题生成一段可能出现在知识库正文中的中文答案，"
                + "用于语义检索。只输出2到3句陈述性正文，不要解释任务，不要添加标题、引用或来源，不要向用户提问。";
        String user = "用户问题：" + question.trim() + "\n\n请直接输出用于检索的假设性答案。";

        try {
            String answer = chatClient
                    .prompt()
                    .system(system)
                    .user(user)
                    .options(OpenAiChatOptions.builder()
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
