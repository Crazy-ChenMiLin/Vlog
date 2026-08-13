package com.tongji.auth.service;

import lombok.RequiredArgsConstructor;
import org.apereo.cas.client.validation.Assertion;
import org.apereo.cas.client.validation.Cas30ServiceTicketValidator;
import org.apereo.cas.client.validation.TicketValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.tongji.auth.api.dto.AuthResponse;
import com.tongji.auth.api.dto.AuthUserResponse;
import com.tongji.auth.api.dto.CasLoginUrlResponse;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * CAS 单点登录业务服务。
 * <p>
 * 职责：
 * - {@link #getCasLoginUrl()} 返回学校 CAS 登录页地址，前端跳转用；
 * - {@link #casLogin(String, ClientInfo)} 验票 → 查/建用户 → 发 JWT（复用现有 JwtService）。
 * <p>
 * 设计决策：不走 Spring Security 的 AuthenticationManager/Provider 模式，
 * 因为 Provider 帮做的是"建 SecurityContext/Session"，而知光是 JWT 无状态，
 * 验完票直接发 JWT 即可，硬套 Provider 只外面包抽象层无实际意义。
 */
@Service
@RequiredArgsConstructor
public class CasService {

    private final Cas30ServiceTicketValidator casTicketValidator;
    private final UserService userService;
    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokenStore;
    private final LoginLogService loginLogService;

    @Value("${cas.server-url}")
    private String casServerUrl;

    @Value("${cas.login-url}")
    private String casLoginUrl;

    @Value("${cas.service-callback}")
    private String serviceCallback;

    /**
     * 拼接学校 CAS 登录页 URL（含 service 回调参数），前端拿到后 window.location.href 跳转。
     * <p>
     * 注意：登录页地址与验票地址路径可能不同（如学校在登录路径中插入应用码），
     * 因此 login-url 单独配置，不拼 server-url + "/login"。
     *
     * @return 引导响应，code=10001 + casLoginUrl。
     */
    public CasLoginUrlResponse getCasLoginUrl() {
        String url = casLoginUrl + "?service="
                + URLEncoder.encode(serviceCallback, StandardCharsets.UTF_8);
        return new CasLoginUrlResponse(10001, "需要学校统一登录", url);
    }

    /**
     * CAS 登录核心流程：验票 → 查/建用户 → 发 JWT。
     * <p>
     * 验票由 {@link Cas30ServiceTicketValidator} 真正打学校 CAS 服务器；
     * 发 JWT 完全复用 {@link JwtService#issueTokenPair}，令牌内容与验证码登录一致。
     *
     * @param ticket     CAS 一次性票据（前端从学校回跳 URL 参数中取出）。
     * @param clientInfo 客户端信息（IP/UA），用于审计。
     * @return 认证响应，包含用户信息与令牌对。
     * @throws BusinessException 当票据无效或已过期时抛出。
     */
    public AuthResponse casLogin(String ticket, ClientInfo clientInfo) {
        // 1. 验票（真正打学校 CAS 服务器）
        Assertion assertion;
        try {
            assertion = casTicketValidator.validate(ticket, serviceCallback);
        } catch (TicketValidationException e) {
            throw new BusinessException(ErrorCode.CAS_TICKET_INVALID);
        }
        String studentId = assertion.getPrincipal().getName();

        // 2. 学号 → 查/建本地用户（首次登录自动注册）
        User user = userService.findOrCreateByCasId(studentId);

        // 3. 发 JWT（完全复用现有逻辑，令牌内容与验证码登录一样）
        TokenPair tokenPair = jwtService.issueTokenPair(user);
        storeRefreshToken(user.getId(), tokenPair);
        loginLogService.record(user.getId(), studentId, "CAS",
                clientInfo.ip(), clientInfo.userAgent(), "SUCCESS");

        return new AuthResponse(mapUser(user), mapToken(tokenPair));
    }

    /**
     * 存储刷新令牌白名单记录（与 AuthService 逻辑一致，因 AuthService 中为 private 故在此复写）。
     */
    private void storeRefreshToken(Long userId, TokenPair tokenPair) {
        Duration ttl = Duration.between(Instant.now(), tokenPair.refreshTokenExpiresAt());
        if (ttl.isNegative()) {
            ttl = Duration.ZERO;
        }
        refreshTokenStore.storeToken(userId, tokenPair.refreshTokenId(), ttl);
    }

    /**
     * 映射用户实体到响应对象（字段映射与 AuthService 一致）。
     */
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

    /**
     * 映射令牌对到响应对象。
     */
    private TokenResponse mapToken(TokenPair tokenPair) {
        return new TokenResponse(
                tokenPair.accessToken(),
                tokenPair.accessTokenExpiresAt(),
                tokenPair.refreshToken(),
                tokenPair.refreshTokenExpiresAt()
        );
    }
}
