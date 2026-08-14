package com.tongji.auth.api.dto;

/**
 * 校园账号（CQUT-Auth OIDC）登录引导响应。
 * <p>
 * 前端调 /campus/login-url 拿到此响应后，用 loginUrl 跳转到校园认证授权页。
 */
public record CampusLoginUrlResponse(
        int code,
        String message,
        String loginUrl
) {
}
