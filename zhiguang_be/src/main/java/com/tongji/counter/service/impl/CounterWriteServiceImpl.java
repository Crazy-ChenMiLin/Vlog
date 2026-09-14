package com.tongji.counter.service.impl;

import com.tongji.counter.event.CounterEvent;
import com.tongji.counter.event.CounterEventProducer;
import com.tongji.counter.schema.BitmapShard;
import com.tongji.counter.schema.CounterKeys;
import com.tongji.counter.schema.CounterSchema;
import com.tongji.counter.service.CounterWriteService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 内容实体计数写服务实现：维护点赞/收藏事实，并发布增量事件。
 */
@Service
public class CounterWriteServiceImpl implements CounterWriteService {
    private final StringRedisTemplate redis;
    private final CounterEventProducer eventProducer;
    private final ApplicationEventPublisher eventPublisher;
    private final DefaultRedisScript<Long> toggleScript;

    public CounterWriteServiceImpl(StringRedisTemplate redis,
                                   CounterEventProducer eventProducer,
                                   ApplicationEventPublisher eventPublisher) {
        this.redis = redis;
        this.eventProducer = eventProducer;
        this.eventPublisher = eventPublisher;
        this.toggleScript = new DefaultRedisScript<>(TOGGLE_LUA, Long.class);
    }

    @Override
    public boolean like(String entityType, String entityId, long userId) {
        return toggle(entityType, entityId, userId, "like", CounterSchema.IDX_LIKE, true);
    }

    @Override
    public boolean unlike(String entityType, String entityId, long userId) {
        return toggle(entityType, entityId, userId, "like", CounterSchema.IDX_LIKE, false);
    }

    @Override
    public boolean fav(String entityType, String entityId, long userId) {
        return toggle(entityType, entityId, userId, "fav", CounterSchema.IDX_FAV, true);
    }

    @Override
    public boolean unfav(String entityType, String entityId, long userId) {
        return toggle(entityType, entityId, userId, "fav", CounterSchema.IDX_FAV, false);
    }

    @Override
    public void initZeroCountsIfAbsent(String entityType, String entityId, List<String> metrics) {
        if (entityType == null || entityType.isBlank() || entityId == null || entityId.isBlank()
                || metrics == null || metrics.isEmpty()) {
            return;
        }

        boolean hasSupportedMetric = metrics.stream().anyMatch(CounterSchema.NAME_TO_IDX::containsKey);
        if (!hasSupportedMetric) {
            return;
        }

        byte[] zeroSds = new byte[CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE];
        String sdsKey = CounterKeys.sdsKey(entityType, entityId);
        redis.execute((RedisCallback<Boolean>) connection ->
                connection.stringCommands().setNX(sdsKey.getBytes(StandardCharsets.UTF_8), zeroSds));
    }

    private boolean toggle(String entityType, String entityId, long userId,
                           String metric, int metricIndex, boolean add) {
        long chunk = BitmapShard.chunkOf(userId);
        long bit = BitmapShard.bitOf(userId);
        String bitmapKey = CounterKeys.bitmapKey(metric, entityType, entityId, chunk);
        Long changed = redis.execute(toggleScript, List.of(bitmapKey),
                String.valueOf(bit), add ? "add" : "remove");
        boolean stateChanged = Long.valueOf(1L).equals(changed);
        if (stateChanged) {
            int delta = add ? 1 : -1;
            CounterEvent event = CounterEvent.of(entityType, entityId, metric, metricIndex, userId, delta);
            eventProducer.publish(event);
            eventPublisher.publishEvent(event);
        }
        return stateChanged;
    }

    private static final String TOGGLE_LUA = """
            local bmKey = KEYS[1]
            local offset = tonumber(ARGV[1])
            local op = ARGV[2]
            local prev = redis.call('GETBIT', bmKey, offset)
            if op == 'add' then
              if prev == 1 then return 0 end
              redis.call('SETBIT', bmKey, offset, 1)
              return 1
            elseif op == 'remove' then
              if prev == 0 then return 0 end
              redis.call('SETBIT', bmKey, offset, 0)
              return 1
            end
            return -1
            """;
}
