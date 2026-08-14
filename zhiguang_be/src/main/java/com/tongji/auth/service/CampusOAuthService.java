package com.tongji.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.auth.api.dto.AuthResponse;
import com.tongji.auth.api.dto.AuthUserResponse;
import com.tongji.auth.api.dto.CampusLoginUrlResponse;
import com.tongji.auth.api.dto.CampusTokenResponse;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 校园账号（CQUT-Auth OIDC）登录业务服务。
 * <p>
 * 职责：
 * - {@link #getLoginUrl()} 生成 PKCE 参数并返回校园认证授权页地址；
 * - {@link #callback(String, String, ClientInfo)} 用 code + verifier 换 token → 校验 id_token → 查/建用户 → 发 JWT。
 * <p>
 * 流程遵循 OIDC Authorization Code + PKCE S256，Web 客户端同时使用 client_secret_basic 认证。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampusOAuthService {

    private static final String PKCE_KEY_PREFIX = "campus:pkce:";
    private static final long PKCE_TTL_MINUTES = 5;
    private static final int PKCE_VERIFIER_LENGTH = 128;
    private static final String CODE_CHALLENGE_METHOD = "S256";

    private final UserService userService;
    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokenStore;
    private final LoginLogService loginLogService;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Qualifier("campusIdTokenDecoder")
    private final JwtDecoder campusIdTokenDecoder;

    @Value("${CAMPUS_CLIENT_ID:${campus.client-id:}}")
    private String clientId;

    @Value("${CAMPUS_CLIENT_SECRET:${campus.client-secret:}}")
    private String clientSecret;

    @Value("${CAMPUS_REDIRECT_URI:${campus.redirect-uri:http://47.108.66.230/callback/campus}}")
    private String redirectUri;

    @Value("${CAMPUS_AUTHORIZATION_ENDPOINT:${campus.authorization-endpoint:https://oidc.ciallichannel.com/auth}}")
    private String authorizationEndpoint;

    @Value("${CAMPUS_TOKEN_ENDPOINT:${campus.token-endpoint:https://oidc.ciallichannel.com/token}}")
    private String tokenEndpoint;

    @Value("${CAMPUS_SCOPES:${campus.scopes:openid profile}}")
    private String scopes;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 拼接校园认证授权页 URL，前端拿到后 window.location.href 跳转。
     * <p>
     * 同时生成 PKCE verifier 暂存 Redis，key 为 state，callback 时取出使用。
     *
     * @return 引导响应，code=10001 + loginUrl。
     */
    public CampusLoginUrlResponse getLoginUrl() {
        String state = generateRandomState();
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);

        stringRedisTemplate.opsForValue().set(
                PKCE_KEY_PREFIX + state,
                codeVerifier,
                PKCE_TTL_MINUTES,
                TimeUnit.MINUTES
        );

        String url = authorizationEndpoint
                + "?client_id=" + urlEncode(clientId)
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&response_type=code"
                + "&scope=" + urlEncode(scopes)
                + "&state=" + urlEncode(state)
                + "&code_challenge=" + urlEncode(codeChallenge)
                + "&code_challenge_method=" + CODE_CHALLENGE_METHOD;

        return new CampusLoginUrlResponse(10001, "需要校园账号授权", url);
    }

    /**
     * 校园认证回调核心流程：用 code + verifier 换 token → 校验 id_token → 查/建用户 → 发 JWT。
     *
     * @param code       回调带来的授权码（一次性）。
     * @param state      回调带回的 state，用于换取 PKCE verifier。
     * @param clientInfo 客户端信息（IP/UA），用于审计。
     * @return 认证响应，包含用户信息与令牌对。
     * @throws BusinessException 当 code/state 无效或 OIDC 调用失败时抛出。
     */
    public AuthResponse callback(String code, String state, ClientInfo clientInfo) {
        String codeVerifier = consumeCodeVerifier(state);
        CampusTokenResponse tokenResponse = exchangeCodeForToken(code, codeVerifier);
        Jwt idToken = verifyAndDecodeIdToken(tokenResponse.idToken());

        String campusId = Objects.toString(idToken.getClaim("sub"), null);
        if (campusId == null || campusId.isBlank()) {
            log.warn("Campus id_token missing sub claim");
            throw new BusinessException(ErrorCode.CAMPUS_OAUTH_FAILED);
        }

        String name = Objects.toString(idToken.getClaim("name"), null);
        String email = Objects.toString(idToken.getClaim("email"), null);
        String preferredUsername = Objects.toString(idToken.getClaim("preferred_username"), null);

        User user = userService.findOrCreateByCampusId(campusId);
        boolean needUpdate = false;

        if (user.getNickname() == null || user.getNickname().startsWith("知光用户")) {
            if (name != null && !name.isBlank()) {
                user.setNickname(name);
                needUpdate = true;
            } else if (preferredUsername != null && !preferredUsername.isBlank()) {
                user.setNickname(preferredUsername);
                needUpdate = true;
            }
        }
        if (user.getEmail() == null && email != null && !email.isBlank()) {
            user.setEmail(email);
            needUpdate = true;
        }
        if (needUpdate) {
            userService.updateProfile(user);
        }

        TokenPair tokenPair = jwtService.issueTokenPair(user);
        storeRefreshToken(user.getId(), tokenPair);
        loginLogService.record(user.getId(), preferredUsername != null ? preferredUsername : campusId,
                "CAMPUS", clientInfo.ip(), clientInfo.userAgent(), "SUCCESS");

        return new AuthResponse(mapUser(user), mapToken(tokenPair));
    }

    /**
     * 用授权码 + PKCE verifier 调 Token 端点换 id_token。
     */
    private CampusTokenResponse exchangeCodeForToken(String code, String codeVerifier) {
        try {
            String body = "grant_type=authorization_code"
                    + "&code=" + urlEncode(code)
                    + "&redirect_uri=" + urlEncode(redirectUri)
                    + "&code_verifier=" + urlEncode(codeVerifier);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenEndpoint))
                    .header("Authorization", "Basic " + base64BasicAuth(clientId, clientSecret))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            CampusTokenResponse tokenResponse = objectMapper.readValue(response.body(), CampusTokenResponse.class);

            if (tokenResponse.error() != null || tokenResponse.idToken() == null) {
                log.warn("Campus token exchange failed: error={}, description={}",
                        tokenResponse.error(), tokenResponse.errorDescription());
                throw new BusinessException(ErrorCode.CAMPUS_OAUTH_FAILED);
            }
            return tokenResponse;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Campus token exchange error", e);
            throw new BusinessException(ErrorCode.CAMPUS_OAUTH_FAILED);
        }
    }

    /**
     * 校验并解析 id_token（RS256，使用 CQUT-Auth JWKS）。
     */
    private Jwt verifyAndDecodeIdToken(String idToken) {
        try {
            return campusIdTokenDecoder.decode(idToken);
        } catch (Exception e) {
            log.error("Campus id_token decode error", e);
            throw new BusinessException(ErrorCode.CAMPUS_OAUTH_FAILED);
        }
    }

    /**
     * 根据 state 从 Redis 取出并删除 PKCE verifier（一次性使用）。
     */
    private String consumeCodeVerifier(String state) {
        if (state == null || state.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少 state 参数");
        }
        String key = PKCE_KEY_PREFIX + state;
        String verifier = stringRedisTemplate.opsForValue().get(key);
        if (verifier == null) {
            throw new BusinessException(ErrorCode.CAMPUS_OAUTH_FAILED, "授权状态已过期或无效");
        }
        stringRedisTemplate.delete(key);
        return verifier;
    }

    private String generateRandomState() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return base64UrlEncode(bytes);
    }

    private String generateCodeVerifier() {
        byte[] bytes = new byte[PKCE_VERIFIER_LENGTH];
        secureRandom.nextBytes(bytes);
        return base64UrlEncode(bytes);
    }

    private String generateCodeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return base64UrlEncode(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PKCE challenge", e);
        }
    }

    private String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String base64BasicAuth(String clientId, String clientSecret) {
        String credentials = clientId + ":" + clientSecret;
        return Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
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
