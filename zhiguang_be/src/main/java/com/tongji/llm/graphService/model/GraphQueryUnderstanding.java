package com.tongji.llm.graphService.model;

import org.springframework.util.StringUtils;

import java.util.List;

public record GraphQueryUnderstanding(
        List<GraphEntity> entities,
        String relationIntent,
        String questionType
) {
    public GraphQueryUnderstanding {
        entities = entities == null ? List.of() : List.copyOf(entities);
        relationIntent = normalize(relationIntent, "UNKNOWN");
        questionType = normalize(questionType, "UNKNOWN");
    }

    public static GraphQueryUnderstanding empty() {
        return new GraphQueryUnderstanding(List.of(), "UNKNOWN", "UNKNOWN");
    }

    public boolean isEmpty() {
        return entities.isEmpty()
                && "UNKNOWN".equals(relationIntent)
                && "UNKNOWN".equals(questionType);
    }

    private static String normalize(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase() : fallback;
    }
}
