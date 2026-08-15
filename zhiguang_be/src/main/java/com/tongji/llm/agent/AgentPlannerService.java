package com.tongji.llm.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.llm.agent.state.QuestionType;
import com.tongji.llm.agent.state.RagAgentPlan;
import com.tongji.llm.agent.state.RetrievalMode;
import com.tongji.llm.config.RagLlmProperties;
import com.tongji.llm.config.RagPromptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * Agent 的事前计划层。
 *
 * <p>这里不写大量“你好/天气/区别/关系”之类的关键词规则，而是把可用工具交给 LLM，
 * 让它输出结构化计划：问题类型、检索模式、需要打开哪些工具。代码只负责解析 JSON
 * 和失败降级，避免语义分类规则越写越多。</p>
 */
public class AgentPlannerService {
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final RagLlmProperties ragLlmProperties;
    private final RagPromptService ragPromptService;

    public RagAgentPlan plan(String question, int requestedTopK) {
        if (!StringUtils.hasText(question)) {
            return directPlan(requestedTopK, "Empty or blank question.");
        }

        String system = ragPromptService.getSystemPrompt(RagPromptService.KEY_PLANNER);
        String user = "Question:\n" + question.trim() + "\nRequested topK: " + requestedTopK;

        try {
            String content = chatClient
                    .prompt()
                    .system(system)
                    .user(user)
                    .options(OpenAiChatOptions.builder()
                            .model(ragLlmProperties.plannerModel())
                            .temperature(0.0)
                            .responseFormat(ResponseFormat.builder()
                                    .type(ResponseFormat.Type.JSON_OBJECT)
                                    .build())
                            .build())
                    .call()
                    .content();
            return parse(content, requestedTopK);
        } catch (Exception e) {
            log.warn("RAG agent planner failed, fallback to hybrid plan: {}", e.getMessage());
            return RagAgentPlan.defaultHybrid(requestedTopK, "Planner failed, fallback to hybrid retrieval.");
        }
    }

    RagAgentPlan parse(String content, int requestedTopK) throws Exception {
        if (!StringUtils.hasText(content)) {
            return RagAgentPlan.defaultHybrid(requestedTopK, "Planner returned empty content.");
        }
        JsonNode root = objectMapper.readTree(stripCodeFence(content.trim()));
        QuestionType questionType = enumValue(root.get("questionType"), QuestionType.class, QuestionType.NORMAL_QA);
        RetrievalMode retrievalMode = enumValue(root.get("retrievalMode"), RetrievalMode.class, RetrievalMode.HYBRID);
        return new RagAgentPlan(
                questionType,
                retrievalMode,
                bool(root.get("needDirectAnswer"), questionType == QuestionType.CHAT),
                bool(root.get("needKeywordSearch"), retrievalMode != RetrievalMode.NONE),
                bool(root.get("needVectorSearch"), retrievalMode == RetrievalMode.HYBRID || retrievalMode == RetrievalMode.GRAPH_AUGMENTED_HYBRID),
                bool(root.get("needHyde"), retrievalMode == RetrievalMode.HYBRID || retrievalMode == RetrievalMode.GRAPH_AUGMENTED_HYBRID),
                bool(root.get("needGraphTrace"), retrievalMode == RetrievalMode.GRAPH_AUGMENTED_HYBRID),
                bool(root.get("needRerank"), retrievalMode != RetrievalMode.NONE),
                integer(root.get("initialTopK"), requestedTopK),
                text(root.get("reason"), "LLM planner result.")
        );
    }

    private RagAgentPlan directPlan(int topK, String reason) {
        return new RagAgentPlan(
                QuestionType.CHAT,
                RetrievalMode.NONE,
                true,
                false,
                false,
                false,
                false,
                false,
                topK,
                reason
        );
    }

    private boolean bool(JsonNode node, boolean fallback) {
        return node == null || node.isNull() ? fallback : node.asBoolean(fallback);
    }

    private int integer(JsonNode node, int fallback) {
        return node == null || node.isNull() ? fallback : node.asInt(fallback);
    }

    private String text(JsonNode node, String fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        String value = node.asText();
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private <E extends Enum<E>> E enumValue(JsonNode node, Class<E> enumType, E fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        try {
            return Enum.valueOf(enumType, node.asText("").trim().toUpperCase());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String stripCodeFence(String content) {
        if (!content.startsWith("```")) {
            return content;
        }
        String stripped = content.replaceFirst("^```[a-zA-Z]*\\s*", "");
        return stripped.replaceFirst("\\s*```$", "").trim();
    }
}
