package com.tongji.llm.graphService;

import com.tongji.llm.graphService.model.GraphContext;
import com.tongji.llm.graphService.model.GraphEntity;
import com.tongji.llm.graphService.model.GraphQueryUnderstanding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图谱上下文主编排层。
 *
 * <p>这层不直接承担“识别实体”或“查询 Neo4j”的细节，而是把三件事串起来：
 * 词典实体匹配、大模型问题理解、Neo4j 关系查询。返回的 {@link GraphContext}
 * 是 RAG 后续检索、HyDE、rerank 和最终回答阶段共同使用的图谱线索包。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MainService {
    private final QueryEntityMatchService queryEntityMatchService;
    private final QueryUnderstandingService understandingService;
    private final GraphRelationQueryService relationQueryService;

    /**
     * 根据用户问题构建图谱线索。
     *
     * <p>实体来源采用“词典 + LLM”混合策略：词典结果稳定可控，LLM 结果补充关系型问法中的
     * 隐含实体和意图。Neo4j 不可用时降级为“仅实体/别名扩展”，保证主 RAG 链路不中断。</p>
     */
    public GraphContext build(String question) {
        List<GraphEntity> dictionaryEntities = queryEntityMatchService.match(question);
        GraphQueryUnderstanding understanding = understandingService.understand(question);
        List<GraphEntity> mergedEntities = mergeEntities(dictionaryEntities, understanding.entities());

        // LLM 可能只识别出问题类型/关系意图但没有实体，此时保留理解结果供 debug 观察。
        if (mergedEntities.isEmpty()) {
            return understanding.isEmpty()
                    ? GraphContext.empty()
                    : new GraphContext(List.of(), List.of(), List.of(), List.of(), understanding);
        }

        try {
            //graphRelationQueryService.query() 内部可能抛出 Neo4j 连接异常，降级为仅实体/别名扩展。
            return relationQueryService.query(mergedEntities, understanding);
        } catch (Exception e) {
            log.warn("GraphContext unavailable, fallback to entity-only trace: {}", e.getMessage());
            return new GraphContext(
                    mergedEntities,
                    List.of(),
                    List.of(),
                    mergedEntities.stream()
                            .flatMap(entity -> entity.aliases().stream())
                            .filter(StringUtils::hasText)
                            .distinct()
                            .toList(),
                    understanding
            );
        }
    }

    /**
     * 按实体规范名去重；词典结果先放入，优先保留更完整的 aliases。
     */
    private List<GraphEntity> mergeEntities(List<GraphEntity> dictionaryEntities, List<GraphEntity> llmEntities) {
        Map<String, GraphEntity> merged = new LinkedHashMap<>();
        addEntities(merged, dictionaryEntities);
        addEntities(merged, llmEntities);
        return List.copyOf(merged.values());
    }

    private void addEntities(Map<String, GraphEntity> merged, List<GraphEntity> entities) {
        if (entities == null) {
            return;
        }
        for (GraphEntity entity : entities) {
            if (entity != null && StringUtils.hasText(entity.name())) {
                merged.putIfAbsent(entity.name().trim(), entity);
            }
        }
    }
}
