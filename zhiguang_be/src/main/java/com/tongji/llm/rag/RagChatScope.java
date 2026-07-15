package com.tongji.llm.rag;

public final class RagChatScope {
    public static final String GLOBAL = "global";
    public static final String POST = "post";

    private RagChatScope() {
    }

    public static boolean isSupported(String scope) {
        return GLOBAL.equals(scope) || POST.equals(scope);
    }
}
