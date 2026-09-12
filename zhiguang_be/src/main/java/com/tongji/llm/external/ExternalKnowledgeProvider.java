package com.tongji.llm.external;

import java.util.List;

/**
 * 外部只读知识发现的统一接口。
 *
 * <p>RAG 流程只依赖证据链接，不感知提供器的 HTTP API、来源白名单或响应格式；
 * 该边界也便于后续通过 MCP 暴露同一能力。</p>
 */
public interface ExternalKnowledgeProvider {

    boolean supports(String question);

    List<ExternalKnowledgeResource> findResources(String question, int maxResults);
}
