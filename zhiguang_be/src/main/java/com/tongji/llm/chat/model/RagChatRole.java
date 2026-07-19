package com.tongji.llm.chat.model;

import java.util.Arrays;
import java.util.Optional;

public enum RagChatRole {
    USER("user", "用户"),
    ASSISTANT("assistant", "助手");

    private final String value;
    private final String displayName;

    RagChatRole(String value, String displayName) {
        this.value = value;
        this.displayName = displayName;
    }

    public String value() {
        return value;
    }

    public String displayName() {
        return displayName;
    }

    public boolean is(String value) {
        return this.value.equals(value);
    }

    public static boolean isSupported(String value) {
        return fromValue(value).isPresent();
    }

    public static Optional<RagChatRole> fromValue(String value) {
        return Arrays.stream(values())
                .filter(role -> role.is(value))
                .findFirst();
    }
}
