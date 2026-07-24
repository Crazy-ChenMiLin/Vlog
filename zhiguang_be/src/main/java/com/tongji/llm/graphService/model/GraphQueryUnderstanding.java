package com.tongji.llm.graphService.model;

import org.springframework.util.StringUtils;

import java.util.List;

/**
 * LLM 对查询的结构化理解结果。
 *
 * @param entities       从问题里抽取出的实体候选
 * @param relationIntent 关系意图，如 COMPARE、CAUSE、PART_OF、SOLUTION
 * @param questionType   问题类型，如 RELATION、CONCEPT、SOLUTION、TEST
 */
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

    /**
     * UNKNOWN 表示“没有可靠信号”，不是错误状态。
     */
    public boolean isEmpty() {
        return entities.isEmpty()
                && "UNKNOWN".equals(relationIntent)
                && "UNKNOWN".equals(questionType);
    }

    private static String normalize(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase() : fallback;
    }
}
