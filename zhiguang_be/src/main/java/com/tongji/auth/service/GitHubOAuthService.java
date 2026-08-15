package com.tongji.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.auth.api.dto.AuthResponse;
import com.tongji.auth.api.dto.AuthUserResponse;
import com.tongji.auth.api.dto.GitHubLoginUrlResponse;
import com.tongji.auth.api.dto.GitHubTokenResponse;
import com.tongji.auth.api.dto.GitHubUser;
import com.tongji.auth.api.dto.TokenResponse;
import com.tongji.auth.audit.LoginLogService;
import com.tongji.auth.model.ClientInfo;
import com.tongji.auth.token.JwtService;
import com.tongji.auth.token.RefreshTokenStore;
import com.tongji.auth.token.TokenPair;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.user.domain.User;
import com.tongji.user.service.UserService;

/**
 * GitHub OAuth 登录业务服务。
 * <p>
 * 职责：
 * - {@link #getLoginUrl()} 返回 GitHub 授权页地址，前端跳转用；
 * - {@link #callback(String, ClientInfo)} 用 code 换 access_token → 拿用户信息 → 查/建用户 → 发 JWT。
 * <p>
 * 与 CAS 的区别：GitHub 是后端主动调 GitHub API 换 token（CAS 是被动验票），
 * 不需要第三方依赖，用 Java 内置 HttpClient 即可。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubOAuthService {

    private final UserService userService;
    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokenStore;
    private final LoginLogService loginLogService;
    private final ObjectMapper objectMapper;

    @Value("${GITHUB_CLIENT_ID:${github.client-id:}}")
    private String clientId;

    @Value("${GITHUB_CLIENT_SECRET:${github.client-secret:}}")
    private String clientSecret;

    @Value("${GITHUB_REDIRECT_URI:${github.redirect-uri:http://47.108.66.230/callback}}")
    private String redirectUri;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Duration GITHUB_API_TIMEOUT = Duration.ofSeconds(8);

    /**
     * 拼接 GitHub 授权页 URL，前端拿到后 window.location.href 跳转。
     *
     * @return 引导响应，code=10001 + loginUrl。
     */
    public GitHubLoginUrlResponse getLoginUrl() {
        String url = "https://github.com/login/oauth/authorize"
                + "?client_id=" + clientId
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode("read:user user:email", StandardCharsets.UTF_8);
        return new GitHubLoginUrlResponse(10001, "需要 GitHub 授权", url);
    }

    /**
     * GitHub 回调核心流程：用 code 换 access_token → 拿用户信息 → 查/建用户 → 发 JWT。
     *
     * @param code       GitHub 回调带来的授权码（一次性）。
     * @param clientInfo 客户端信息（IP/UA），用于审计。
     * @return 认证响应，包含用户信息与令牌对。
     * @throws BusinessException 当 code 无效或 GitHub API 调用失败时抛出。
     */
    public AuthResponse callback(String code, ClientInfo clientInfo) {
        // 1. 用 code 换 access_token（后端主动调 GitHub API）
        String accessToken = exchangeCodeForToken(code);

        //2. 去github的api-url申请拿用户信息
        GitHubUser githubUser = fetchUserInfo(accessToken);

        // 3. GitHub 用户 ID → 查/建本地用户
        String githubId = String.valueOf(githubUser.id());
        User user = userService.findOrCreateByGithubId(githubId);

        // 4. 新用户同步 GitHub 头像和昵称（仅当本地为空时）
        boolean needUpdate = false;
        if (user.getAvatar() == null && githubUser.avatarUrl() != null) {
            user.setAvatar(githubUser.avatarUrl());
            needUpdate = true;
        }
        if (user.getNickname() == null || user.getNickname().startsWith("知光用户")) {
            if (githubUser.name() != null) {
                user.setNickname(githubUser.name());
                needUpdate = true;
            } else if (githubUser.login() != null) {
                user.setNickname(githubUser.login());
                needUpdate = true;
            }
        }
        if (user.getEmail() == null && githubUser.email() != null) {
            user.setEmail(githubUser.email());
            needUpdate = true;
        }
        if (needUpdate) {
            userService.updateProfile(user);
        }

        // 5. 发 JWT（完全复用现有逻辑，令牌内容与验证码登录一致）
        TokenPair tokenPair = jwtService.issueTokenPair(user);
        storeRefreshToken(user.getId(), tokenPair);
        loginLogService.record(user.getId(), githubUser.login(), "GITHUB",
                clientInfo.ip(), clientInfo.userAgent(), "SUCCESS");

        return new AuthResponse(mapUser(user), mapToken(tokenPair));
    }

    /**
     * 用授权码调 GitHub API 换 access_token。
     */
    private String exchangeCodeForToken(String code) {
        try {
            String body = "client_id=" + clientId
                    + "&client_secret=" + clientSecret
                    + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                    + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://github.com/login/oauth/access_token"))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(GITHUB_API_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            GitHubTokenResponse tokenResponse = objectMapper.readValue(response.body(), GitHubTokenResponse.class);

            if (tokenResponse.error() != null || tokenResponse.accessToken() == null) {
                log.warn("GitHub token exchange failed: error={}, description={}",
                        tokenResponse.error(), tokenResponse.errorDescription());
                throw new BusinessException(ErrorCode.GITHUB_OAUTH_FAILED);
            }
            return tokenResponse.accessToken();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("GitHub token exchange error", e);
            //code违法时
            throw new BusinessException(ErrorCode.GITHUB_OAUTH_FAILED);
        }
    }

    /**
     * 用 access_token 调 GitHub API 拿用户信息。
     */
    private GitHubUser fetchUserInfo(String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/user"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .header("User-Agent", "zhiguang")
                    .timeout(GITHUB_API_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("GitHub user API returned status {}", response.statusCode());
                throw new BusinessException(ErrorCode.GITHUB_OAUTH_FAILED);
            }

            return objectMapper.readValue(response.body(), GitHubUser.class);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("GitHub user API error", e);
            throw new BusinessException(ErrorCode.GITHUB_OAUTH_FAILED);
        }
    }

    private void storeRefreshToken(Long userId, TokenPair tokenPair) {
        Duration ttl = Duration.between(Instant.now(), tokenPair.refreshTokenExpiresAt());
        if (ttl.isNegative()) {
            ttl = Duration.ZERO;
        }
        refreshTokenStore.storeToken(userId, tokenPair.refreshTokenId(), ttl);
    }

    private AuthUserResponse mapUser(User user) {
        return new AuthUserResponse(
                user.getId(),
                user.getNickname(),
                user.getAvatar(),
                user.getPhone(),
                user.getZgId(),
                user.getBirthday(),
                user.getSchool(),
                user.getBio(),
                user.getGender(),
                user.getTagsJson()
        );
    }

    private TokenResponse mapToken(TokenPair tokenPair) {
        return new TokenResponse(
                tokenPair.accessToken(),
                tokenPair.accessTokenExpiresAt(),
                tokenPair.refreshToken(),
                tokenPair.refreshTokenExpiresAt()
        );
    }
}
