package com.tongji.counter.api;

import com.tongji.counter.api.dto.CounterWriteRequest;
import com.tongji.counter.service.CounterReadService;
import com.tongji.counter.service.CounterWriteService;
import com.tongji.auth.token.JwtService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 计数写接口：点赞/取消点赞、收藏/取消收藏。
 *
 * <p>所有接口基于登录用户，返回操作是否改变状态以及当前状态值。</p>
 */
@RestController
@RequestMapping("/api/v1/action")
@RequiredArgsConstructor
public class CounterWriteController {

    private final CounterWriteService counterWriteService;
    private final CounterReadService counterReadService;
    private final JwtService jwtService;

    /**
     * 点赞操作。
     */
    @PostMapping("/like")
    public ResponseEntity<Map<String, Object>> like(@Valid @RequestBody CounterWriteRequest req,
                                                    @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        boolean changed = counterWriteService.like(req.getEntityType(), req.getEntityId(), uid);
        return ResponseEntity.ok(Map.of(
                "changed", changed, // 标识这次操作是否改变状态（避免重复点击）
                "liked", counterReadService.isLiked(req.getEntityType(), req.getEntityId(), uid)
        ));
    }

    /**
     * 取消点赞操作。
     */
    @PostMapping("/unlike")
    public ResponseEntity<Map<String, Object>> unlike(@Valid @RequestBody CounterWriteRequest req,
                                                      @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        boolean changed = counterWriteService.unlike(req.getEntityType(), req.getEntityId(), uid);
        return ResponseEntity.ok(Map.of(
                "changed", changed, // 状态是否发生变化
                "liked", counterReadService.isLiked(req.getEntityType(), req.getEntityId(), uid)
        ));
    }

    /**
     * 收藏操作。
     */
    @PostMapping("/fav")
    public ResponseEntity<Map<String, Object>> fav(@Valid @RequestBody CounterWriteRequest req,
                                                   @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        boolean changed = counterWriteService.fav(req.getEntityType(), req.getEntityId(), uid);
        return ResponseEntity.ok(Map.of(
                "changed", changed, // 状态是否发生变化
                "faved", counterReadService.isFaved(req.getEntityType(), req.getEntityId(), uid)
        ));
    }

    /**
     * 取消收藏操作。
     */
    @PostMapping("/unfav")
    public ResponseEntity<Map<String, Object>> unfav(@Valid @RequestBody CounterWriteRequest req,
                                                     @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        boolean changed = counterWriteService.unfav(req.getEntityType(), req.getEntityId(), uid);
        return ResponseEntity.ok(Map.of(
                "changed", changed, // 状态是否发生变化
                "faved", counterReadService.isFaved(req.getEntityType(), req.getEntityId(), uid)
        ));
    }
}
