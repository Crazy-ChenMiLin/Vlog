package com.tongji.auth.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GitHub /user 接口返回的用户信息。
 * <p>
 * 只取登录所需字段：id（唯一标识）、login（用户名）、name（昵称）、avatar_url、email。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubUser(
        Long id,
        String login,
        String name,
        @JsonProperty("avatar_url") String avatarUrl,
        String email
) {
}
