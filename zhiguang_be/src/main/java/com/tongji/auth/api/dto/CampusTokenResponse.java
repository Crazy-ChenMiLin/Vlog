package com.tongji.auth.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CQUT-Auth Token 端点响应。
 * <p>
 * 包含 access_token、id_token、refresh_token 等标准 OIDC 字段。
 */
public record CampusTokenResponse(
        @JsonProperty("access_token")
        String accessToken,
        @JsonProperty("id_token")
        String idToken,
        @JsonProperty("refresh_token")
        String refreshToken,
        @JsonProperty("token_type")
        String tokenType,
        @JsonProperty("expires_in")
        Long expiresIn,
        String scope,
        String error,
        @JsonProperty("error_description")
        String errorDescription
) {
}
