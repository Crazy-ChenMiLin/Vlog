package com.tongji.auth.api;

import com.tongji.auth.api.dto.AuthResponse;
import com.tongji.auth.api.dto.CampusLoginUrlResponse;
import com.tongji.auth.model.ClientInfo;
import com.tongji.auth.service.CampusOAuthService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 校园账号（CQUT-Auth OIDC）登录 API 控制器。
 * <p>
 * 两个接口：
 * - GET /campus/login-url → 返回校园认证授权页 URL，前端跳转；
 * - GET /campus/callback?code=xxx&state=xxx → 用 code 换 token → 发 JWT。
 * <p>
 * 前端流程：
 * 1. 调 GET /campus/login-url → 拿 loginUrl → window.location.href 跳校园认证授权页；
 * 2. 用户授权后回跳 redirect_uri（/callback/campus?code=xxx&state=xxx）；
 * 3. 前端回调页取 code + state → 调 GET /campus/callback → 拿 JWT → 存 localStorage。
 */
@RestController
@RequestMapping("/api/v1/auth/campus")
@RequiredArgsConstructor
public class CampusOAuthController {

    private final CampusOAuthService campusOAuthService;

    /**
     * 获取校园认证授权页 URL。
     *
     * @return 引导响应，code=10001 + loginUrl。
     */
    @GetMapping("/login-url")
    public CampusLoginUrlResponse getLoginUrl() {
        return campusOAuthService.getLoginUrl();
    }

    /**
     * 校园认证回调：用授权码 + state 换 token → 发 JWT。
     *
     * @param code        校园认证回调带来的授权码。
     * @param state       校园认证回调带回的 state。
     * @param httpRequest 用于解析客户端信息。
     * @return 认证响应（与验证码登录格式一致）。
     */
    @GetMapping("/callback")
    public AuthResponse callback(@RequestParam String code,
                                 @RequestParam String state,
                                 HttpServletRequest httpRequest) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少 code 参数");
        }
        if (!StringUtils.hasText(state)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少 state 参数");
        }
        return campusOAuthService.callback(code, state, resolveClient(httpRequest));
    }

    private ClientInfo resolveClient(HttpServletRequest request) {
        String ip = extractClientIp(request);
        String ua = request.getHeader("User-Agent");
        return new ClientInfo(ip, ua);
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
