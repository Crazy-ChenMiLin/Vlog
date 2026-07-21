package com.tongji.llm.graphService;

import com.tongji.llm.graphService.model.GraphContext;
import com.tongji.llm.graphService.model.GraphEntity;
import com.tongji.llm.graphService.model.GraphQueryUnderstanding;
import com.tongji.llm.graphService.model.GraphRelation;
import lombok.RequiredArgsConstructor;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GraphRelationQueryService {
    private final Driver driver;

    public GraphContext query(List<GraphEntity> matchedEntities) {
        return query(matchedEntities, GraphQueryUnderstanding.empty());
    }

    public GraphContext query(List<GraphEntity> matchedEntities, GraphQueryUnderstanding understanding) {
        if (matchedEntities == null || matchedEntities.isEmpty()) {
            return GraphContext.empty();
        }
        understanding = understanding == null ? GraphQueryUnderstanding.empty() : understanding;

        List<String> names = matchedEntities.stream()
                .map(GraphEntity::name)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (names.isEmpty()) {
            return GraphContext.empty();
        }

        String cypher = """
                MATCH (source:Concept)-[r:RELATED]->(target:Concept)
                WHERE source.name IN $names
                   OR target.name IN $names
                RETURN source.name AS source,
                       r.type AS type,
                       target.name AS target,
                       r.description AS description
                LIMIT 40
                """;

        LinkedHashSet<GraphRelation> relations = new LinkedHashSet<>();
        LinkedHashSet<String> parents = new LinkedHashSet<>();
        LinkedHashSet<String> terms = new LinkedHashSet<>(names);

        try (Session session = driver.session()) {
            session.executeRead(tx -> {
                var result = tx.run(cypher, Map.of("names", names));
                while (result.hasNext()) {
                    Record record = result.next();
                    GraphRelation relation = new GraphRelation(
                            record.get("source").asString(null),
                            record.get("type").asString(null),
                            record.get("target").asString(null),
                            record.get("description").asString(null)
                    );
                    if (StringUtils.hasText(relation.source()) && StringUtils.hasText(relation.target())) {
                        relations.add(relation);
                        terms.add(relation.source());
                        terms.add(relation.target());
                        if ("PART_OF".equals(relation.type()) && names.contains(relation.source())) {
                            parents.add(relation.target());
                        }
                    }
                }
                return null;
            });
        }

        matchedEntities.stream()
                .flatMap(entity -> entity.aliases().stream())
                .filter(StringUtils::hasText)
                .forEach(terms::add);

        return new GraphContext(
                matchedEntities,
                sortByIntent(relations, understanding),
                List.copyOf(parents),
                List.copyOf(terms),
                understanding
        );
    }

    private List<GraphRelation> sortByIntent(LinkedHashSet<GraphRelation> relations, GraphQueryUnderstanding understanding) {
        return relations.stream()
                .sorted(Comparator.comparingInt(relation -> relationPriority(relation, understanding.relationIntent())))
                .toList();
    }

    private int relationPriority(GraphRelation relation, String relationIntent) {
        if (!StringUtils.hasText(relationIntent) || "UNKNOWN".equals(relationIntent)) {
            return 10;
        }
        String type = relation.type() == null ? "" : relation.type().toUpperCase();
        String description = relation.description() == null ? "" : relation.description().toUpperCase();
        String haystack = type + " " + description;
        return switch (relationIntent) {
            case "COMPARE" -> containsAny(haystack, "COMPARE", "DIFFER", "CONTRAST", "CONFUSE", "SIMILAR",
                    "对比", "区别", "不同", "混淆", "相似") ? 0 : 10;
            case "CAUSE" -> containsAny(haystack, "CAUSE", "AFFECT", "IMPACT", "LEAD", "TRIGGER",
                    "导致", "影响", "触发", "原因", "因果") ? 0 : 10;
            case "PART_OF" -> containsAny(haystack, "PART_OF", "BELONG", "INCLUDE", "PARENT",
                    "属于", "包含", "父概念", "分类") ? 0 : 10;
            case "SOLUTION" -> containsAny(haystack, "SOLUTION", "SOLVE", "MITIGATE", "PREVENT",
                    "解决", "缓解", "预防", "方案") ? 0 : 10;
            case "RELATED" -> 5;
            default -> 10;
        };
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
