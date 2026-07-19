package com.tongji.llm.chat.model;

import java.util.Arrays;
import java.util.Optional;

public enum RagChatScope {
    GLOBAL("global", "全库问答"),
    POST("post", "单篇文章问答");

    private final String value;
    private final String defaultTitle;

    RagChatScope(String value, String defaultTitle) {
        this.value = value;
        this.defaultTitle = defaultTitle;
    }

    public String value() {
        return value;
    }

    public String defaultTitle() {
        return defaultTitle;
    }

    public boolean is(String value) {
        return this.value.equals(value);
    }

    public static boolean isSupported(String value) {
        return fromValue(value).isPresent();
    }

    public static Optional<RagChatScope> fromValue(String value) {
        return Arrays.stream(values())
                .filter(scope -> scope.is(value))
                .findFirst();
    }
}
