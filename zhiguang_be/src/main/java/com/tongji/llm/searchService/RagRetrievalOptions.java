package com.tongji.llm.searchService;

import com.tongji.llm.graphService.model.GraphContext;

/**
 * Agent 调检索层时使用的工具开关。
 *
 * <p>旧 retrieveGlobal/retrieveForPost 方法仍按 application 配置运行；这个 options 入口
 * 是为了让 RagMainAgent 的 Plan 真正控制向量、HyDE、BM25 和 Graph 是否参与本轮检索。</p>
 */
public record RagRetrievalOptions(
        boolean useVector,
        boolean useHyde,
        boolean useBm25,
        boolean useGraph,
        GraphContext graphContext
) {
    public RagRetrievalOptions {
        graphContext = graphContext == null ? GraphContext.empty() : graphContext;
    }

    public static RagRetrievalOptions defaults(boolean bm25Enabled, boolean graphEnabled) {
        return new RagRetrievalOptions(true, true, bm25Enabled, graphEnabled, GraphContext.empty());
    }
}
