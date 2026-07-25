package com.tongji.llm.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.llm.agent.model.EvidenceAction;
import com.tongji.llm.agent.model.EvidenceResult;
import com.tongji.llm.graphService.model.GraphContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * Agent 的事后证据检查层。
 *
 * <p>Planner 回答“准备怎么查”，EvidenceCheck 回答“查完这些证据够不够”。
 * 它只输出 sufficient/score/reason/suggestedAction，不直接生成最终答案。
 * MainAgent 根据 suggestedAction 决定是否执行一次 topK 扩展。</p>
 */
public class EvidenceCheckService {
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public EvidenceResult check(String question, List<Document> docs, GraphContext graphContext, int topK, int retryCount) {
        if (docs == null || docs.isEmpty()) {
            return new EvidenceResult(false, 0.0, "No retrieved chunks.", EvidenceAction.EXPAND_TOP_K);
        }

        String system = """
                You judge whether retrieved RAG evidence is enough to answer a Chinese user question.
                Return JSON only. Do not answer the question.
                Schema:
                {
                  "sufficient": true,
                  "score": 0.0,
                  "reason": "short Chinese reason",
                  "suggestedAction": "NONE|EXPAND_TOP_K|ANSWER_WITH_LIMITATION"
                }
                Use EXPAND_TOP_K only if more candidates may help. Use ANSWER_WITH_LIMITATION if evidence is still weak but retry is already used.
                """;
        String user = "Question:\n" + question
                + "\n\nCurrent topK: " + topK
                + "\nRetry count: " + retryCount
                + "\n\nNeo4j graph trace:\n" + graphSummary(graphContext)
                + "\n\nRetrieved chunks:\n" + chunkSummary(docs);

        try {
            String content = chatClient
                    .prompt()
                    .system(system)
                    .user(user)
                    .options(OpenAiChatOptions.builder()
                            .temperature(0.0)
                            .build())
                    .call()
                    .content();
            EvidenceResult result = parse(content);
            if (!result.sufficient() && retryCount > 0 && result.suggestedAction() == EvidenceAction.EXPAND_TOP_K) {
                return new EvidenceResult(false, result.score(), result.reason(), EvidenceAction.ANSWER_WITH_LIMITATION);
            }
            return result;
        } catch (Exception e) {
            log.warn("RAG evidence check failed, answer with limitation: {}", e.getMessage());
            return EvidenceResult.limited("Evidence check failed, answer with available chunks.");
        }
    }

    EvidenceResult parse(String content) throws Exception {
        if (!StringUtils.hasText(content)) {
            return EvidenceResult.limited("Evidence check returned empty content.");
        }
        JsonNode root = objectMapper.readTree(stripCodeFence(content.trim()));
        return new EvidenceResult(
                bool(root.get("sufficient"), false),
                number(root.get("score"), 0.0),
                text(root.get("reason"), "Evidence check result."),
                enumValue(root.get("suggestedAction"), EvidenceAction.class, EvidenceAction.ANSWER_WITH_LIMITATION)
        );
    }

    private String graphSummary(GraphContext graphContext) {
        if (graphContext == null || graphContext.isEmpty() || !StringUtils.hasText(graphContext.relationSummary())) {
            return "(none)";
        }
        return graphContext.relationSummary();
    }

    private String chunkSummary(List<Document> docs) {
        return docs.stream()
                .limit(10)
                .map(this::oneChunk)
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private String oneChunk(Document document) {
        Object title = document.getMetadata().get("title");
        Object chunkId = document.getMetadata().get("chunkId");
        String text = document.getText() == null ? "" : document.getText();
        String preview = text.length() <= 500 ? text : text.substring(0, 500);
        return "chunkId=" + chunkId + "\ntitle=" + title + "\ntext=" + preview;
    }

    private boolean bool(JsonNode node, boolean fallback) {
        return node == null || node.isNull() ? fallback : node.asBoolean(fallback);
    }

    private double number(JsonNode node, double fallback) {
        return node == null || node.isNull() ? fallback : node.asDouble(fallback);
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
