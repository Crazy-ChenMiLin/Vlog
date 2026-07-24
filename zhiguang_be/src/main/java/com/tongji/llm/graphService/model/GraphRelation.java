package com.tongji.llm.graphService.model;

/**
 * Neo4j 中两个概念之间的一条关系。
 *
 * @param source      关系起点概念名
 * @param type        关系类型，如 RELATED、PART_OF、COMPARE_WITH
 * @param target      关系终点概念名
 * @param description 面向 RAG prompt 的关系说明，可为空
 */
public record GraphRelation(
        String source,
        String type,
        String target,
        String description
) {
}
