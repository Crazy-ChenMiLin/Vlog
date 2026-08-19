package com.tongji.llm.external;

import java.util.List;

/**
 * Provider boundary for external, read-only knowledge discovery.
 *
 * <p>This is intentionally shaped like a future MCP tool: the RAG workflow
 * asks for evidence links and does not know the provider's HTTP API, allow-list,
 * or result format. A later MCP transport can expose this contract unchanged.</p>
 */
public interface ExternalKnowledgeProvider {

    boolean supports(String question);

    List<ExternalKnowledgeResource> findResources(String question, int maxResults);
}
