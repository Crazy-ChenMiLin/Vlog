package com.tongji.auth.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.auth.api.dto.CampusTokenResponse;
import com.tongji.auth.model.LoginCommand;
import com.tongji.auth.model.LoginSuccess;
import com.tongji.auth.service.OAuthChannelService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.user.domain.User;
import com.tongji.user.service.UserService;
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
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 校园账号（CQUT-Auth OIDC）登录渠道实现（OAuthChannelService 的实现类）。
 * <p>
 * 独门逻辑：PKCE 生成授权链接 → 用 code + verifier 换 token → 校验 id_token →
 * 查/建用户并同步资料。签发令牌、记录成功日志等公共逻辑由 {@link com.tongji.auth.service.AuthService} 统一处理。
 */
@Slf4j
@Service
public class CampusLoginChannelServiceImpl implements OAuthChannelService {

    private static final String PKCE_KEY_PREFIX = "campus:pkce:";
    private static final long PKCE_TTL_MINUTES = 5;
    // RFC 7636 allows a verifier of at most 128 characters. 96 random bytes
    // produce exactly 128 unpadded Base64URL characters (128 bytes would
    // produce 171 characters and is rejected by the campus OIDC server).
    private static final int PKCE_VERIFIER_BYTE_LENGTH = 96;
    private static final String CODE_CHALLENGE_METHOD = "S256";

    private final UserService userService;
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

    public CampusLoginChannelServiceImpl(UserService userService,
                                         ObjectMapper objectMapper,
                                         StringRedisTemplate stringRedisTemplate,
                                         @Qualifier("campusIdTokenDecoder") JwtDecoder campusIdTokenDecoder) {
        this.userService = userService;
        this.objectMapper = objectMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.campusIdTokenDecoder = campusIdTokenDecoder;
    }

    @Override
    public String getType() {
        return "campus";
    }

    @Override
    public String getLoginUrl() {
        String state = generateRandomState();
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);

        stringRedisTemplate.opsForValue().set(
                PKCE_KEY_PREFIX + state,
                codeVerifier,
                PKCE_TTL_MINUTES,
                TimeUnit.MINUTES
        );

        return authorizationEndpoint
                + "?client_id=" + urlEncode(clientId)
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&response_type=code"
                + "&scope=" + urlEncode(scopes)
                + "&state=" + urlEncode(state)
                + "&code_challenge=" + urlEncode(codeChallenge)
                + "&code_challenge_method=" + CODE_CHALLENGE_METHOD;
    }

    @Override
    public LoginSuccess authenticate(LoginCommand cmd) {
        String codeVerifier = consumeCodeVerifier(cmd.state());
        CampusTokenResponse tokenResponse = exchangeCodeForToken(cmd.oauthCode(), codeVerifier);
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

        return new LoginSuccess(user, preferredUsername != null ? preferredUsername : campusId, "CAMPUS");
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
            throw new BusinessException(ErrorCode.CAMPUS_OAUTH_FAILED, "校园账号授权失败，请重试", e);
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
            throw new BusinessException(ErrorCode.CAMPUS_OAUTH_FAILED, "校园账号登录信息解析失败，请重试", e);
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
        byte[] bytes = new byte[PKCE_VERIFIER_BYTE_LENGTH];
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
}
