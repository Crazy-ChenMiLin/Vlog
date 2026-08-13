package com.tongji.limit;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * AI 接口限流器：三道闸门保护 RAG chat 接口。
 * <p>
 * ① 全局令牌桶（Redisson RRateLimiter，12 QPS）—— 分布式共享，限总速率<br>
 * ② 信号量（Java Semaphore，80）—— 单机，限同时并发数<br>
 * ③ 每用户滑动窗口（Redis ZSet + Lua，10 次/分钟）—— 分布式共享，防个人刷
 * <p>
 * 顺序：便宜的先查，拿不到直接拒绝。第三道失败要手动释放第二道的信号量。
 */
@Component
public class AiRateLimiter {

    private final RedissonClient redisson;
    private final StringRedisTemplate redis;
    private final RRateLimiter globalLimiter;
    private final Semaphore streamSem = new Semaphore(80);

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

    public AiRateLimiter(RedissonClient redisson, StringRedisTemplate redis) {
        this.redisson = redisson;
        this.redis = redis;
        // ① 分布式令牌桶：12 QPS（多实例共享一个 Redis 桶）
        this.globalLimiter = redisson.getRateLimiter("rag:chat:limiter:global");
        globalLimiter.trySetRate(RateType.OVERALL, 12, 1, RateIntervalUnit.SECONDS);
        this.slidingScript = new DefaultRedisScript<>(SLIDING_WINDOW_LUA, Long.class);
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
                    "60000", "10", member
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
