package com.tongji.llm.graphService.model;

import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;

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
