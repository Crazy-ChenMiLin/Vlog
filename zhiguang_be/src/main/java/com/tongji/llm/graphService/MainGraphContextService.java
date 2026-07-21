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

@Slf4j
@Service
@RequiredArgsConstructor
public class MainGraphContextService {
    private final GraphEntityMatchService entityMatchService;
    private final GraphQueryUnderstandingService understandingService;
    private final GraphRelationQueryService relationQueryService;

    public GraphContext build(String question) {
        List<GraphEntity> dictionaryEntities = entityMatchService.match(question);
        GraphQueryUnderstanding understanding = understandingService.understand(question);
        List<GraphEntity> mergedEntities = mergeEntities(dictionaryEntities, understanding.entities());

        if (mergedEntities.isEmpty()) {
            return understanding.isEmpty()
                    ? GraphContext.empty()
                    : new GraphContext(List.of(), List.of(), List.of(), List.of(), understanding);
        }

        try {
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
