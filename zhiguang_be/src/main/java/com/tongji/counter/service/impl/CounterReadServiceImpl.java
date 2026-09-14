package com.tongji.counter.service.impl;

import com.tongji.counter.schema.CounterKeys;
import com.tongji.counter.schema.CounterSchema;
import com.tongji.counter.schema.BitmapShard;
import com.tongji.counter.service.CounterReadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.redisson.api.RedissonClient;
import org.redisson.api.RLock;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RBucket;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 内容实体计数读服务实现（位图事实 + SDS 汇总）。
 *
 * <p>职责：</p>
 * - 读取汇总计数（SDS），异常时基于位图分片重建；
 * - 批量读取优化与“是否点赞/收藏”判定。
 */
@Slf4j
@Service
public class CounterReadServiceImpl implements CounterReadService {

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    @Value("${counter.rebuild.lock.ttl-ms:5000}")
    private long lockTtlMs;
    @Value("${counter.rebuild.rate.permits:3}")
    private int ratePermits;
    @Value("${counter.rebuild.rate.window-seconds:10}")
    private int rateWindowSeconds;
    @Value("${counter.rebuild.backoff.base-ms:500}")
    private long backoffBaseMs;
    @Value("${counter.rebuild.backoff.max-ms:30000}")
    private long backoffMaxMs;

    public CounterReadServiceImpl(StringRedisTemplate redis, RedissonClient redisson) {
        this.redis = redis;
        this.redisson = redisson;
    }

    /**
     * 获取实体计数汇总（SDS）。
     * 若缺失或结构异常则触发基于位图的事实重建，并清理对应聚合字段。
     */
    @Override
    public Map<String, Long> getCounts(String entityType, String entityId, List<String> metrics) {
        String sdsKey = CounterKeys.sdsKey(entityType, entityId);
        int expectedLen = CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE;
        // SDS 固定结构：按大端 32 位编码
        byte[] raw = getRaw(sdsKey);
        boolean needRebuild = (raw == null || raw.length != expectedLen);

        Map<String, Long> result = new LinkedHashMap<>();

        if (needRebuild) {
            log.info("计数结构不存在，需要重建");
            // 限流与指数退避：避免在热点实体上触发重建风暴
            if (inBackoff(entityType, entityId)) {
                for (String m : metrics) {
                    result.put(m, 0L);
                }
                return result;
            }

            if (!allowedByRateLimiter(entityType, entityId)) {
                escalateBackoff(entityType, entityId);
                for (String m : metrics) {
                    result.put(m, 0L);
                }
                return result;
            }

            String lockKey = String.format("lock:sds-rebuild:%s:%s", entityType, entityId);

            RLock lock = redisson.getLock(lockKey);
            boolean locked = false;

            try {
                // 使用 Redisson 看门狗机制：不指定租期，自动续约（由 Redisson 的 lockWatchdogTimeout 控制）
                locked = lock.tryLock(0L, TimeUnit.MILLISECONDS);
                if (!locked) {
                    escalateBackoff(entityType, entityId);
                    for (String m : metrics) {
                        result.put(m, 0L);
                    }
                    return result;
                }
                // 依据位图分片统计真实计数（仅由持锁者执行重建）
                byte[] newSds = new byte[expectedLen];
                List<String> rebuildFields = new ArrayList<>();
                for (String m : metrics) {
                    Integer idx = CounterSchema.NAME_TO_IDX.get(m);
                    if (idx == null) {
                        continue;
                    }
                    //bitcountShardsPipelined：管道化 BITCOUNT 汇总，按分片求和
                    long sum = bitCountShardsPipelined(m, entityType, entityId);
                    writeInt32BE(newSds, idx * CounterSchema.FIELD_SIZE, sum);
                    result.put(m, sum);
                    rebuildFields.add(String.valueOf(idx));
                }
                // 回写SDS并清理聚合桶，避免重复加算
                setRaw(sdsKey, newSds);
                if (!rebuildFields.isEmpty()) {
                    String aggKey = CounterKeys.aggKey(entityType, entityId);
                    redis.opsForHash().delete(aggKey, rebuildFields.toArray());
                }
                resetBackoff(entityType, entityId);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                escalateBackoff(entityType, entityId);
                for (String m : metrics) {
                    result.put(m, 0L);
                }
                return result;
            } finally {
                if (locked) {
                    try {
                        lock.unlock();
                    } catch (Exception ignore) {}
                }
            }
        } else {
            for (String m : metrics) {
                Integer idx = CounterSchema.NAME_TO_IDX.get(m);
                if (idx == null) {
                    continue;
                }

                int off = idx * CounterSchema.FIELD_SIZE;
                long val = readInt32BE(raw, off); // 大端读取单段 32 位值
                result.put(m, val);
            }
        }
        return result;
    }

    /**
     * 批量获取实体计数（管道批量 GET 降低 RTT）。
     * 缺失或结构异常（长度不符）时按零返回，保证接口稳定。
     * @param entityType 实体类型
     * @param entityIds 实体ID列表
     * @param metrics 指标名列表
     * @return 每个实体的指标计数映射
     */
    @Override
    public Map<String, Map<String, Long>> getCountsBatch(String entityType, List<String> entityIds, List<String> metrics) {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        if (entityIds == null || entityIds.isEmpty() || metrics == null || metrics.isEmpty()) {
            return out;
        }

        List<String> keys = new ArrayList<>(entityIds.size());
        for (String eid : entityIds) {
            keys.add(CounterKeys.sdsKey(entityType, eid));
        }

        // 管道批量 GET：将多个 SDS 读取合并到一次往返
        List<Object> raws = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (String k : keys) {
                connection.stringCommands().get(k.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });

        int expectedLen = CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE;
        for (int i = 0; i < entityIds.size(); i++) {
            String eid = entityIds.get(i);
            Object rawObj = i < raws.size() ? raws.get(i) : null;
            byte[] raw = (rawObj instanceof byte[]) ? (byte[]) rawObj : null;

            Map<String, Long> m = new LinkedHashMap<>();
            if (raw != null && raw.length == expectedLen) {
                for (String name : metrics) {
                    Integer idx = CounterSchema.NAME_TO_IDX.get(name);
                    if (idx == null) continue;
                    int off = idx * CounterSchema.FIELD_SIZE;
                    long val = readInt32BE(raw, off);
                    m.put(name, val);
                }
            } else {
                for (String name : metrics) {
                    m.put(name, 0L); // 缺失或异常结构时补零，避免接口失败与重建风暴
                }
            }
            out.put(eid, m);
        }
        return out;
    }

    /**
     * 是否点赞判定：基于分片位图在分片内做位测试。
     * 毫秒级读取，不依赖计数快照。
     */
    @Override
    public boolean isLiked(String entityType, String entityId, long userId) {
        long chunk = BitmapShard.chunkOf(userId);
        long bit = BitmapShard.bitOf(userId);
        return getBit(CounterKeys.bitmapKey("like", entityType, entityId, chunk), bit);
    }

    /**
     * 是否收藏判定：同点赞，基于分片位图位测试。
     */
    @Override
    public boolean isFaved(String entityType, String entityId, long userId) {
        long chunk = BitmapShard.chunkOf(userId);
        long bit = BitmapShard.bitOf(userId);
        return getBit(CounterKeys.bitmapKey("fav", entityType, entityId, chunk), bit);
    }

    /**
     * 读取位图某偏移位（GETBIT）。
     * @param key 位图分片键
     * @param offset 分片内位偏移
     * @return 位是否为 1
     */
    private boolean getBit(String key, long offset) {
        Boolean bit = redis.execute((RedisCallback<Boolean>) connection ->
                connection.stringCommands().getBit(key.getBytes(StandardCharsets.UTF_8), offset));
        return Boolean.TRUE.equals(bit);
    }

    /**
     * 读取 SDS 原始字节（固定结构，长度=字段数×4）。
     */
    private byte[] getRaw(String key) {
        return redis.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 写入 SDS 原始字节（覆盖式写）。
     */
    private void setRaw(String key, byte[] val) {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.stringCommands().set(key.getBytes(StandardCharsets.UTF_8), val);
            return null;
        });
    }

    /**
     * 是否处于指数退避期：期间跳过重建并返回降级结果。
     */
    private boolean inBackoff(String entityType, String entityId) {
        String bKey = String.format("backoff:sds-rebuild:until:%s:%s", entityType, entityId);
        RBucket<Long> bucket = redisson.getBucket(bKey);
        Long until = bucket.get();

        return until != null && System.currentTimeMillis() < until;
    }

    /**
     * 增加退避级别并设置下次允许尝试的时间（指数递增，封顶）。
     */
    private void escalateBackoff(String entityType, String entityId) {
        String eKey = String.format("backoff:sds-rebuild:exp:%s:%s", entityType, entityId);
        String uKey = String.format("backoff:sds-rebuild:until:%s:%s", entityType, entityId);

        RBucket<Integer> expB = redisson.getBucket(eKey);
        RBucket<Long> untilB = redisson.getBucket(uKey);
        Integer exp = expB.get();

        int nextExp = Math.min(exp == null ? 0 : exp + 1, 10);
        long delay = Math.min(backoffBaseMs * (1L << nextExp), backoffMaxMs);
        long until = System.currentTimeMillis() + delay;

        // 设置过期时间，避免长时间残留
        expB.set(nextExp);
        untilB.set(until, Duration.ofMillis(delay + 1000));
    }

    /**
     * 重置退避状态（成功重建后）。
     */
    private void resetBackoff(String entityType, String entityId) {
        String eKey = String.format("backoff:sds-rebuild:exp:%s:%s", entityType, entityId);
        String uKey = String.format("backoff:sds-rebuild:until:%s:%s", entityType, entityId);

        try {
            redisson.getBucket(eKey).delete();
        } catch (Exception ignore) {}

        try {
            redisson.getBucket(uKey).delete();
        } catch (Exception ignore) {}
    }

    /**
     * 限流判断：单位窗口可重建次数，防止抖动与风暴。
     */
    private boolean allowedByRateLimiter(String entityType, String entityId) {
        String rlKey = String.format("rl:sds-rebuild:%s:%s", entityType, entityId);
        RRateLimiter limiter = redisson.getRateLimiter(rlKey);

        // 初始化速率（如已存在则忽略）
        limiter.trySetRate(RateType.OVERALL, ratePermits, Duration.ofSeconds(rateWindowSeconds));

        return limiter.tryAcquire(1);
    }

    /**
     * 以大端序读取 32 位无符号整型。
     */
    private static long readInt32BE(byte[] buf, int off) {
        long n = 0;
        for (int i = 0; i < 4; i++) {
            n = (n << 8) | (buf[off + i] & 0xFFL);
        }
        return n;
    }

    /**
     * 以大端序写入 32 位无符号整型（截断到 0~2^32-1）。
     */
    private static void writeInt32BE(byte[] buf, int off, long val) {
        long n = Math.max(0, Math.min(val, 0xFFFF_FFFFL));
        buf[off] = (byte) ((n >>> 24) & 0xFF);
        buf[off + 1] = (byte) ((n >>> 16) & 0xFF);
        buf[off + 2] = (byte) ((n >>> 8) & 0xFF);
        buf[off + 3] = (byte) (n & 0xFF);
    }

    /**
     * 基于位图分片进行管道化 BITCOUNT 汇总，用于按事实重建计数。
     * 说明：当前使用 KEYS 枚举分片（生产建议维护索引集合），结果按分片 BITCOUNT 求和。
     */
    private long bitCountShardsPipelined(String metric, String etype, String eid) {
        String pattern = String.format("bm:%s:%s:%s:*", metric, etype, eid);
        // 生产环境建议以索引集合替代 KEYS
        Set<String> keys = redis.keys(pattern); 
        if (keys.isEmpty()) return 0L;

        // 管道批量 BITCOUNT 汇总
        List<Object> res = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (String k : keys) {
                connection.stringCommands().bitCount(k.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        long sum = 0L;

        for (Object o : res) {
            if (o instanceof Number n) {
                sum += n.longValue();
            }
        }
        return sum;
    }

}
