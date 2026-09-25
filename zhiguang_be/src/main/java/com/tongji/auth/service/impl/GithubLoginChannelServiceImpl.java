package com.tongji.auth.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.auth.api.dto.GitHubTokenResponse;
import com.tongji.auth.api.dto.GitHubUser;
import com.tongji.auth.model.LoginCommand;
import com.tongji.auth.model.LoginSuccess;
import com.tongji.auth.service.OAuthChannelService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.user.domain.User;
import com.tongji.user.service.UserService;
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

/**
 * GitHub OAuth 登录渠道实现（OAuthChannelService 的实现类）。
 * <p>
 * 独门逻辑：生成授权链接 → 用 code 换 access_token → 拉 GitHub 用户 →
 * 查/建用户并同步资料。签发令牌、记录成功日志等公共逻辑由 {@link com.tongji.auth.service.AuthService} 统一处理。
 */
@Slf4j
@Service
public class GithubLoginChannelServiceImpl implements OAuthChannelService {

    private final UserService userService;
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

    public GithubLoginChannelServiceImpl(UserService userService, ObjectMapper objectMapper) {
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getType() {
        return "github";
    }

    @Override
    public String getLoginUrl() {
        return "https://github.com/login/oauth/authorize"
                + "?client_id=" + clientId
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode("read:user user:email", StandardCharsets.UTF_8);
    }

    @Override
    public LoginSuccess authenticate(LoginCommand cmd) {
        // 1. 用 code 换 access_token（后端主动调 GitHub API）
        String accessToken = exchangeCodeForToken(cmd.oauthCode());

        // 2. 去 github 的 api 申请拿用户信息
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

        return new LoginSuccess(user, githubUser.login(), "GITHUB");
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
            // code 无效或网络异常时，保留原始异常作为 cause，避免上层只剩一句文案
            throw new BusinessException(ErrorCode.GITHUB_OAUTH_FAILED, "GitHub 授权失败，请重试", e);
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
            throw new BusinessException(ErrorCode.GITHUB_OAUTH_FAILED, "GitHub 用户信息获取失败，请重试", e);
        }
    }
}
