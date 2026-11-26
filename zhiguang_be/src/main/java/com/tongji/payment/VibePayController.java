package com.tongji.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.tongji.auth.tokenservice.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/payment")
@RequiredArgsConstructor
public class VibePayController {
    private final VibePayService vibePayService;
    private final JwtService jwtService;

    @GetMapping("/product")
    public JsonNode product(@AuthenticationPrincipal Jwt jwt) {
        requireAccess(jwt);
        return vibePayService.product();
    }

    @GetMapping("/consult")
    public JsonNode consult(@AuthenticationPrincipal Jwt jwt) {
        requireAccess(jwt);
        return vibePayService.consult(jwtService.extractUserId(jwt));
    }

    private void requireAccess(Jwt jwt) {
        if (jwt == null || !"access".equals(jwt.getClaimAsString("token_type"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
    }
}
