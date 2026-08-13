package com.tongji.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import com.tongji.auth.api.dto.AuthResponse;
import com.tongji.auth.api.dto.CasLoginUrlResponse;
import com.tongji.auth.model.ClientInfo;
import com.tongji.auth.service.CasService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAS 单点登录 API 控制器。
 * <p>
 * 单接口两种行为（按 ticket 参数分流）：
 * - 没带 ticket → 返回 {@link CasLoginUrlResponse}（code=10001 + 学校登录 URL），前端跳转；
 * - 带了 ticket → 验票 → 发 JWT，返回 {@link AuthResponse}（与验证码登录格式一致）。
 * <p>
 * 前端流程：
 * 1. 调 GET /casLogin（不带 ticket）→ 拿到 casLoginUrl → window.location.href 跳学校登录页；
 * 2. 用户在学校页输学号密码 → 学校 302 回跳前端 /cas-callback?ticket=ST-xxx；
 * 3. 前端回调页取 ticket → 调 GET /casLogin?ticket=ST-xxx → 拿到 JWT → 存 localStorage。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class CasController {

    private final CasService casService;

    /**
     * CAS 登录入口。
     *
     * @param ticket     CAS 票据（可选）。未提供时返回登录引导；提供时验票发 JWT。
     * @param httpRequest 用于解析客户端信息（IP 与 User-Agent）。
     * @return 没带 ticket 返回 {@link CasLoginUrlResponse}；带了 ticket 返回 {@link AuthResponse}。
     */
    @GetMapping("/casLogin")
    public Object casLogin(@RequestParam(required = false) String ticket,
                           HttpServletRequest httpRequest) {
        if (!StringUtils.hasText(ticket)) {
            return casService.getCasLoginUrl();
        }
        return casService.casLogin(ticket, resolveClient(httpRequest));
    }

    /**
     * 从请求中解析客户端信息（与 AuthController 逻辑一致）。
     */
    private ClientInfo resolveClient(HttpServletRequest request) {
        String ip = extractClientIp(request);
        String ua = request.getHeader("User-Agent");
        return new ClientInfo(ip, ua);
    }

    /**
     * 提取客户端 IP 地址（优先代理头）。
     */
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
