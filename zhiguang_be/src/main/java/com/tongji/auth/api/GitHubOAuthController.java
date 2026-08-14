package com.tongji.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import com.tongji.auth.api.dto.AuthResponse;
import com.tongji.auth.api.dto.GitHubLoginUrlResponse;
import com.tongji.auth.model.ClientInfo;
import com.tongji.auth.service.GitHubOAuthService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GitHub OAuth 登录 API 控制器。
 * <p>
 * 两个接口：
 * - GET /github/login-url → 返回 GitHub 授权页 URL，前端跳转；
 * - GET /github/callback?code=xxx → 用 code 换 token → 发 JWT，返回 AuthResponse。
 * <p>
 * 前端流程：
 * 1. 调 GET /github/login-url → 拿 loginUrl → window.location.href 跳 GitHub 授权页；
 * 2. 用户在 GitHub 点 Authorize → GitHub 跳回 redirect_uri（http://47.108.66.230/callback?code=xxx）；
 * 3. 前端回调页取 code → 调 GET /github/callback?code=xxx → 拿 JWT → 存 localStorage。
 */
@RestController
@RequestMapping("/api/v1/auth/github")
@RequiredArgsConstructor
public class GitHubOAuthController {

    private final GitHubOAuthService githubOAuthService;

    /**
     * 获取 GitHub 授权页 URL。
     *
     * @return 引导响应，code=10001 + loginUrl。
     */
    @GetMapping("/login-url")
    public GitHubLoginUrlResponse getLoginUrl() {
        return githubOAuthService.getLoginUrl();
    }

    /**
     * GitHub 回调：用授权码换 token → 发 JWT。
     *
     * @param code       GitHub 回调带来的授权码。
     * @param httpRequest 用于解析客户端信息。
     * @return 认证响应（与验证码登录格式一致）。
     */
    @GetMapping("/callback")
    public AuthResponse callback(@RequestParam String code, HttpServletRequest httpRequest) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少 code 参数");
        }
        return githubOAuthService.callback(code, resolveClient(httpRequest));
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
