package com.tongji.llm.external;

import org.springframework.util.StringUtils;

/**
 * A link-only external knowledge result. The external document is deliberately
 * not persisted or passed to the answer model in this MVP.
 */
public record ExternalKnowledgeResource(
        String provider,
        String title,
        String repository,
        String path,
        String url,
        String summary
) {
    public ExternalKnowledgeResource {
        provider = text(provider);
        title = text(title);
        repository = text(repository);
        path = text(path);
        url = text(url);
        summary = text(summary);
    }

    private static String text(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }
}
