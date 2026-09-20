package com.tongji.agent.internal;

/**
 * Minimal read-only view of a post for the Agent runtime (M5 get_post).
 */
public record AgentPostView(
        Long id,
        String title,
        String content,
        String authorNickname,
        String tags
) {}
