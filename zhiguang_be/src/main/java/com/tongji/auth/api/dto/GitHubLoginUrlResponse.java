package com.tongji.auth.api.dto;

/**
 * GitHub 登录引导响应。
 * <p>
 * 前端调 /github/login-url 拿到此响应后，用 loginUrl 跳转到 GitHub 授权页。
 */
public record GitHubLoginUrlResponse(
        int code,
        String message,
        String loginUrl
) {
}
