package com.tongji.llm.external;

import org.springframework.util.StringUtils;

/**
 * 仅包含链接和摘要的外部知识结果；外部正文不会持久化，也不会传给回答模型。
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
