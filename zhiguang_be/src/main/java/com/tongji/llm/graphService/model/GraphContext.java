package com.tongji.llm.graphService.model;

import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 图谱上下文：一次图谱增强查询的结果载体，供 RAG 检索阶段复用。
 *
 * <p>记录本次问题识别到的概念、概念间关系、父概念以及扩展术语，
 * 并通过 {@link #keywordQuery(String)} 与 {@link #relationSummary()} 两个方法，
 * 分别服务于 BM25 查询词扩展与 HyDE 假设答案聚焦。
 *
 * <p>作为 record，构造时对四个集合做了不可变拷贝与空值保护，保证线程安全。
 *
 * @param matchedEntities 上游实体识别命中的概念列表
 * @param relations       从图谱中查出的概念间关系
 * @param parentConcepts  通过 PART_OF 识别出的父概念（更上位的语义范畴）
 * @param expandedTerms   用于召回扩展的术语集合（关系两端概念 + 别名）
 */
public record GraphContext(
        List<GraphEntity> matchedEntities,
        List<GraphRelation> relations,
        List<String> parentConcepts,
        List<String> expandedTerms
) {
    public GraphContext {
        matchedEntities = matchedEntities == null ? List.of() : List.copyOf(matchedEntities);
        relations = relations == null ? List.of() : List.copyOf(relations);
        parentConcepts = parentConcepts == null ? List.of() : List.copyOf(parentConcepts);
        expandedTerms = expandedTerms == null ? List.of() : List.copyOf(expandedTerms);
    }

    /** 空上下文：未启用图谱或未能识别任何概念时使用。 */
    public static GraphContext empty() {
        return new GraphContext(List.of(), List.of(), List.of(), List.of());
    }

    /** 是否完全为空（四个维度均无内容）。 */
    public boolean isEmpty() {
        return matchedEntities.isEmpty() && relations.isEmpty() && parentConcepts.isEmpty() && expandedTerms.isEmpty();
    }

    /**
     * 生成用于 BM25 检索的扩展查询串。
     * 以原始问题为首，拼接图谱扩展术语与父概念，用空格连接，
     * 让关键词检索能覆盖到问题字面之外但语义相关的概念。
     *
     * @param question 用户原始问题
     * @return 空格分隔的查询词串
     */
    public String keywordQuery(String question) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (StringUtils.hasText(question)) {
            terms.add(question.trim());
        }
        terms.addAll(expandedTerms);
        parentConcepts.stream()
                .filter(StringUtils::hasText)
                .forEach(terms::add);
        return String.join(" ", terms);
    }

    /**
     * 生成关系摘要文本，用于聚焦 HyDE 假设答案的生成。
     * 把关系渲染成「源 -类型-> 目标（描述）」的易读形式，最多取 8 条。
     *
     * @return 多行关系摘要；无关系时返回空串
     */
    public String relationSummary() {
        if (relations.isEmpty()) {
            return "";
        }
        return relations.stream()
                .map(relation -> relation.source() + " -" + relation.type() + "-> " + relation.target()
                        + (StringUtils.hasText(relation.description()) ? "：" + relation.description() : ""))
                .distinct()
                .limit(8)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }
}
