package com.tongji.limit;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RSemaphore;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.Collections;
import java.util.UUID;

/**
 * AI 接口限流器：三道闸门保护 RAG chat 接口。
 * <p>
 * ① 全局令牌桶（Redisson RRateLimiter，QPS 由 Nacos 配置）—— 分布式共享，限总速率<br>
 * ② 信号量（Redisson RSemaphore，80）—— 分布式，限同时并发数（写死，不放 Nacos）<br>
 * ③ 每用户滑动窗口（Redis ZSet + Lua，窗口与次数由 Nacos 配置）—— 分布式共享，防个人刷
 * <p>
 * 顺序：便宜的先查，拿不到直接拒绝。第三道失败要手动释放第二道的信号量。
 */
@Component
@RefreshScope
@Slf4j
public class AiRateLimiter {

    private final RedissonClient redisson;
    private final StringRedisTemplate redis;
    private final RRateLimiter globalLimiter;
    private final RSemaphore streamSem;
    private final RagRateLimitProperties properties;

    /**
     * 滑动窗口 Lua 脚本：原子执行 4 步
     * <p>
     * ZREMRANGEBYSCORE 删旧 → ZCARD 数当前 → 没超就 ZADD → EXPIRE 防泄漏
     */
    private static final String SLIDING_WINDOW_LUA = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local windowMs = tonumber(ARGV[2])
            local maxReq = tonumber(ARGV[3])
            local member = ARGV[4]
            redis.call('ZREMRANGEBYSCORE', key, 0, now - windowMs)
            local count = redis.call('ZCARD', key)
            if count >= maxReq then return 0 end
            redis.call('ZADD', key, now, member)
            redis.call('EXPIRE', key, math.floor(windowMs / 1000) + 10)
            return 1
            """;
    private final DefaultRedisScript<Long> slidingScript;

    public AiRateLimiter(RedissonClient redisson, StringRedisTemplate redis, RagRateLimitProperties properties) {
        this.redisson = redisson;
        this.redis = redis;
        this.properties = properties;
        this.globalLimiter = redisson.getRateLimiter("rag:chat:limiter:global");
        this.streamSem = redisson.getSemaphore("rag:chat:semaphore");
        this.slidingScript = new DefaultRedisScript<>(SLIDING_WINDOW_LUA, Long.class);
    }

    /**
     * 初始化全局令牌桶速率。
     * <p>构造器执行时 {@code properties} 尚未绑定 Nacos 值，所以用 {@code @PostConstruct} 延迟设置；
     * 配合 {@code @RefreshScope}，配置刷新后 Bean 重建，会重新执行本方法。</p>
     */
    @PostConstruct
    public void init() {
        // setRate intentionally replaces a previous Redis value. trySetRate
        // would leave an old value in place after a Nacos refresh.
        globalLimiter.setRate(RateType.OVERALL, properties.getGlobalQps(), 1, RateIntervalUnit.SECONDS);
        streamSem.trySetPermits(80);
        log.info("rag_rate_limit_config_applied globalQps={}, perUserWindowMs={}, perUserMaxReq={}",
                properties.getGlobalQps(),
                properties.getPerUserWindowMs(),
                properties.getPerUserMaxReq());
    }

    /**
     * 三道闸门检查：全局令牌桶 → 信号量 → 每用户滑动窗口
     *
     * @param userId 当前用户 ID（从 JWT 提取）
     * @return null = 全通过，非 null = 拒绝原因
     */
    public String tryAcquire(long userId) {
        // ① 全局令牌桶
        if (!globalLimiter.tryAcquire(1)) {
            return "rate limited";
        }
        // ② 信号量
        if (!streamSem.tryAcquire()) {
            return "concurrent limit";
        }
        // ③ 每用户滑动窗口
        try {
            String key = "rag:chat:sliding:" + userId;
            String member = System.currentTimeMillis() + ":" + UUID.randomUUID();
            Long allowed = redis.execute(
                    slidingScript,
                    Collections.singletonList(key),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(properties.getPerUserWindowMs()),
                    String.valueOf(properties.getPerUserMaxReq()),
                    member
            );
            if (allowed == null || allowed == 0L) {
                streamSem.release();   // 第三道失败，释放第二道
                return "per-user limit";
            }
            return null;   // 全通过
        } catch (Exception e) {
            streamSem.release();       // 异常也要释放
            return "sliding window error";
        }
    }

    /**
     * 释放信号量。在 Flux 的 doFinally 中调用，覆盖完成/报错/取消所有路径。
     */
    public void release() {
        streamSem.release();
    }
}
