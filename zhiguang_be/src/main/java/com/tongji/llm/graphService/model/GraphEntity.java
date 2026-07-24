package com.tongji.llm.graphService.model;

import java.util.List;

/**
 * 图谱概念实体。
 *
 * @param name    规范概念名，需要与 Neo4j 中 Concept.name 对齐
 * @param aliases 该概念在用户问题或文档中可能出现的别名/口语化说法
 */
public record GraphEntity(
        String name,
        List<String> aliases
) {
    public GraphEntity {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
}
