package com.tongji.auth.api.dto;

/**
 * CAS 登录引导响应。
 * <p>
 * 当前端调用 /casLogin 但未带 ticket 时返回此对象，前端根据 code=10001
 * 跳转到 {@link #casLoginUrl()} 指向的学校 CAS 登录页。
 */
public record CasLoginUrlResponse(
        int code,
        String message,
        String casLoginUrl
) {
}
