package com.tongji.llm.graphService.model;

import java.util.List;

/**
 * 知识概念实体：图谱增强链路中从问题里识别出的一个概念。
 *
 * @param name    规范化概念名，需与 Neo4j 中 Concept 节点的 name 对齐
 * @param aliases 该概念在用户问题中可能出现的各种表述（同义词 / 口语化说法）
 */
public record GraphEntity(
        String name,
        List<String> aliases
) {
    public GraphEntity {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
}
