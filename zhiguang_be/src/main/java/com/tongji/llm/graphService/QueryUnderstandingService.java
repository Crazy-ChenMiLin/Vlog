package com.tongji.llm.graphService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.llm.config.RagLlmProperties;
import com.tongji.llm.config.RagConfig;
import com.tongji.llm.config.RagPromptService;
import com.tongji.llm.graphService.model.GraphEntity;
import com.tongji.llm.graphService.model.GraphQueryUnderstanding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 大模型问题理解层。
 *
 * <p>它只负责从问题中抽取图谱检索需要的结构化信号，不负责回答问题，也不直接查 Neo4j。
 * 输出结果会和词典匹配结果合并，作为后续 Neo4j 查询和 rerank 的辅助依据。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryUnderstandingService {
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final RagLlmProperties ragLlmProperties;
    private final RagConfig ragConfig;
    private final RagPromptService ragPromptService;

    /**
     * 抽取实体、关系意图和问题类型。
     *
     * <p>失败时返回空理解结果，让链路自然退回到词典匹配，避免大模型波动影响主检索可用性。</p>
     */
    public GraphQueryUnderstanding understand(String question) {
        if (!ragConfig.getGraph().isUnderstandingEnabled() || !StringUtils.hasText(question)) {
            return GraphQueryUnderstanding.empty();
        }

        String system = ragPromptService.getSystemPrompt(RagPromptService.KEY_GRAPH_UNDERSTANDING);
        String user = "Question:\n" + question.trim();

        try {
            String content = chatClient
                    .prompt()
                    .system(system)
                    .user(user)
                    .options(OpenAiChatOptions.builder()
                            .model(ragLlmProperties.graphModel())
                            .temperature(0.0)
                            .responseFormat(ResponseFormat.builder()
                                    .type(ResponseFormat.Type.JSON_OBJECT)
                                    .build())
                            .build())
                    .call()
                    .content();
            return parse(content);
        } catch (Exception e) {
            log.warn("Graph query understanding failed, fallback to dictionary matching: {}", e.getMessage());
            return GraphQueryUnderstanding.empty();
        }
    }

    private GraphQueryUnderstanding parse(String content) throws Exception {
        if (!StringUtils.hasText(content)) {
            return GraphQueryUnderstanding.empty();
        }
        // 兼容模型偶尔把 JSON 包在 ```json 代码块里的情况。
        JsonNode root = objectMapper.readTree(stripCodeFence(content.trim()));
        return new GraphQueryUnderstanding(
                parseEntities(root.get("entities")),
                text(root.get("relationIntent"), "UNKNOWN"),
                text(root.get("questionType"), "UNKNOWN")
        );
    }

    private List<GraphEntity> parseEntities(JsonNode entitiesNode) {
        if (entitiesNode == null || !entitiesNode.isArray()) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode node : entitiesNode) {
            String name = node.asText(null);
            if (StringUtils.hasText(name)) {
                names.add(name.trim());
            }
        }
        List<GraphEntity> result = new ArrayList<>();
        for (String name : names) {
            // LLM 产出的实体暂时只有规范名，别名用自身兜底；词典实体会在编排层补充更完整的 aliases。
            result.add(new GraphEntity(name, List.of(name)));
        }
        return result;
    }

    private String text(JsonNode node, String fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        String value = node.asText();
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String stripCodeFence(String content) {
        if (!content.startsWith("```")) {
            return content;
        }
        String stripped = content.replaceFirst("^```[a-zA-Z]*\\s*", "");
        return stripped.replaceFirst("\\s*```$", "").trim();
    }
}
