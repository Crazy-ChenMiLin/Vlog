package com.tongji.llm.graphService.model;

/**
 * 图谱关系：知识图谱中两个概念之间的一条 RELATED 边。
 *
 * @param source      关系起点概念名
 * @param type        关系类型（如 RELATED、PART_OF 等）
 * @param target      关系终点概念名
 * @param description 关系描述（可为空）
 */
public record GraphRelation(
        String source,
        String type,
        String target,
        String description
) {
}
