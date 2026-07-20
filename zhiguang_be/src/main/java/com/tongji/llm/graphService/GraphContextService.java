package com.tongji.llm.graphService;

import com.tongji.llm.graphService.model.GraphContext;
import com.tongji.llm.graphService.model.GraphEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 图谱上下文服务：RAG 检索前的关系型查询增强入口。
 *
 * <p>职责：拿到用户原始问题后，先识别其中提及的知识概念（实体），
 * 再从 Neo4j 知识图谱中拉取这些概念之间的关系，
 * 最终组装成 {@link GraphContext}，供上游用于两处增强：
 * <ul>
 *   <li>BM25 查询词扩展 —— {@link GraphContext#keywordQuery(String)}</li>
 *   <li>HyDE 假设答案聚焦 —— {@link GraphContext#relationSummary()}</li>
 * </ul>
 *
 * <p>设计要点：对图谱不可用做了降级处理，查询异常时不会阻塞主检索链路，
 * 仅退化为「仅实体别名」的上下文，保证系统可用性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphContextService {
    private final GraphEntityMatchService entityMatchService;
    private final GraphRelationQueryService relationQueryService;

    /**
     * 根据问题构建图谱上下文。
     *
     * @param question 用户原始问题
     * @return 图谱上下文；若未识别到任何概念则返回 {@link GraphContext#empty()}
     */
    public GraphContext build(String question) {
        // 1. 实体识别：从问题中匹配已知知识概念（含别名）
        List<GraphEntity> entities = entityMatchService.match(question);
        // 没匹配到任何概念，图谱增强无从谈起，直接返回空上下文
        if (entities.isEmpty()) {
            return GraphContext.empty();
        }
        try {
            // 2. 关系查询：用匹配到的概念去图谱里捞出关联关系并扩展术语
            return relationQueryService.query(entities);
        } catch (Exception e) {
            // 3. 兜底：图谱连接/查询异常时降级为「仅实体别名」，保证主链路不中断
            log.warn("GraphContext unavailable, fallback to normal RAG: {}", e.getMessage());
            return new GraphContext(entities, List.of(), List.of(),
                    entities.stream()
                            .flatMap(entity -> entity.aliases().stream())
                            .distinct()
                            .toList());
        }
    }
}
