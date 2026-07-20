package com.tongji.llm.graphService;

import com.tongji.llm.graphService.model.GraphContext;
import com.tongji.llm.graphService.model.GraphEntity;
import com.tongji.llm.graphService.model.GraphRelation;
import lombok.RequiredArgsConstructor;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 关系查询服务：根据已识别的概念，从 Neo4j 知识图谱中查询概念间关系并做术语扩展。
 *
 * <p>这是图谱增强链路的第二步（实体识别之后）。执行一段 Cypher，
 * 把「源或目标在已识别概念集合中」的所有 RELATED 关系捞出来，由此：
 * <ul>
 *   <li>收集关系两端的概念名，作为召回用的扩展术语（expandedTerms）；</li>
 *   <li>识别 PART_OF 关系，把被包含概念的父概念（parentConcepts）单独拎出来，
 *       用于把查询提升到更上位的语义层级。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class GraphRelationQueryService {
    private final Driver driver;

    /**
     * 查询与给定概念相关的图谱关系，并组装成 {@link GraphContext}。
     *
     * @param matchedEntities 上游实体识别得到的概念列表
     * @return 含关系、父概念、扩展术语的图谱上下文；输入为空时返回 {@link GraphContext#empty()}
     */
    public GraphContext query(List<GraphEntity> matchedEntities) {
        // 防御：空输入直接返回空上下文
        if (matchedEntities == null || matchedEntities.isEmpty()) {
            return GraphContext.empty();
        }
        // 抽取概念名去重（与图谱 Concept 节点的 name 字段对齐）
        List<String> names = matchedEntities.stream()
                .map(GraphEntity::name)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (names.isEmpty()) {
            return GraphContext.empty();
        }

        // Cypher：捞出一端属于已识别概念的 RELATED 关系，最多 40 条，避免结果爆炸
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

        // LinkedHashSet 用于保序去重：同一条关系/术语只保留首次出现
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
                    // 仅保留两端都有效的有效关系，避免脏数据污染上下文
                    if (StringUtils.hasText(relation.source()) && StringUtils.hasText(relation.target())) {
                        relations.add(relation);
                        // 关系两端的概念都纳入扩展术语，扩大召回面
                        terms.add(relation.source());
                        terms.add(relation.target());
                        // PART_OF 表示 source 隶属于 target（如「缓存击穿」PART_OF「Redis 缓存问题」），
                        // 把父概念单独收集，便于在关键词查询时把语义提升到更上位的范畴
                        if ("PART_OF".equals(relation.type()) && names.contains(relation.source())) {
                            parents.add(relation.target());
                        }
                    }
                }
                return null;
            });
        }

        // 把已识别概念的别名也并入扩展术语，进一步丰富 BM25 查询词
        matchedEntities.stream()
                .flatMap(entity -> entity.aliases().stream())
                .filter(StringUtils::hasText)
                .forEach(terms::add);

        return new GraphContext(
                matchedEntities,
                List.copyOf(relations),
                List.copyOf(parents),
                List.copyOf(terms)
        );
    }
}
