package com.tongji.auth.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GitHub access_token 接口响应。
 * <p>
 * 成功时返回 access_token；失败时返回 error + error_description。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        String scope,
        String error,
        @JsonProperty("error_description") String errorDescription
) {
}
