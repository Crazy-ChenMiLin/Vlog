package com.tongji.llm.graphService.model;

import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 图谱线索包。
 *
 * <p>它不是最终答案，而是 Neo4j 查询和问题理解后的结构化 trace：
 * 命中的实体、相关关系、父概念、扩展检索词，以及 LLM 对问题的理解结果。
 * 后续 BM25、HyDE、rerank 和最终回答 prompt 都会复用这个对象。</p>
 */
public record GraphContext(
        List<GraphEntity> matchedEntities,
        List<GraphRelation> relations,
        List<String> parentConcepts,
        List<String> expandedTerms,
        GraphQueryUnderstanding understanding
) {
    public GraphContext(
            List<GraphEntity> matchedEntities,
            List<GraphRelation> relations,
            List<String> parentConcepts,
            List<String> expandedTerms) {
        this(matchedEntities, relations, parentConcepts, expandedTerms, GraphQueryUnderstanding.empty());
    }

    public GraphContext {
        matchedEntities = matchedEntities == null ? List.of() : List.copyOf(matchedEntities);
        relations = relations == null ? List.of() : List.copyOf(relations);
        parentConcepts = parentConcepts == null ? List.of() : List.copyOf(parentConcepts);
        expandedTerms = expandedTerms == null ? List.of() : List.copyOf(expandedTerms);
        understanding = understanding == null ? GraphQueryUnderstanding.empty() : understanding;
    }

    public static GraphContext empty() {
        return new GraphContext(List.of(), List.of(), List.of(), List.of(), GraphQueryUnderstanding.empty());
    }

    /**
     * 空 trace 表示图谱链路没有给主检索提供任何额外线索。
     */
    public boolean isEmpty() {
        return matchedEntities.isEmpty()
                && relations.isEmpty()
                && parentConcepts.isEmpty()
                && expandedTerms.isEmpty()
                && understanding.isEmpty();
    }

    public List<GraphEntity> llmEntities() {
        return understanding.entities();
    }

    public String relationIntent() {
        return understanding.relationIntent();
    }

    public String questionType() {
        return understanding.questionType();
    }

    /**
     * 生成 BM25 使用的扩展查询：原问题 + 图谱扩展词 + LLM 实体 + 父概念。
     */
    public String keywordQuery(String question) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (StringUtils.hasText(question)) {
            terms.add(question.trim());
        }
        terms.addAll(expandedTerms);
        understanding.entities().stream()
                .map(GraphEntity::name)
                .filter(StringUtils::hasText)
                .forEach(terms::add);
        parentConcepts.stream()
                .filter(StringUtils::hasText)
                .forEach(terms::add);
        return String.join(" ", terms);
    }

    /**
     * 生成给 HyDE/最终回答使用的关系摘要，只取前几条，避免 prompt 被图谱信息淹没。
     */
    public String relationSummary() {
        if (relations.isEmpty()) {
            return "";
        }
        String summary = relations.stream()
                .map(relation -> relation.source() + " -" + relation.type() + "-> " + relation.target()
                        + (StringUtils.hasText(relation.description()) ? " (" + relation.description() + ")" : ""))
                .distinct()
                .limit(8)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        if (StringUtils.hasText(relationIntent()) && !"UNKNOWN".equals(relationIntent())) {
            return "relationIntent=" + relationIntent() + "\n" + summary;
        }
        return summary;
    }
}
