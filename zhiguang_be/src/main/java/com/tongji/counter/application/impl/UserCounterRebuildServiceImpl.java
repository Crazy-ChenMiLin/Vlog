package com.tongji.counter.application.impl;

import com.tongji.counter.application.UserCounterRebuildService;
import com.tongji.counter.schema.UserCounterKeys;
import com.tongji.counter.service.CounterReadService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.relation.mapper.RelationMapper;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 汇总关系、知文和内容计数数据，重建用户维度计数快照。
 */
@Service
public class UserCounterRebuildServiceImpl implements UserCounterRebuildService {
    private static final int FIELD_SIZE = 4;
    private static final int FIELD_COUNT = 5;

    private final StringRedisTemplate redis;
    private final KnowPostMapper knowPostMapper;
    private final RelationMapper relationMapper;
    private final CounterReadService counterReadService;

    public UserCounterRebuildServiceImpl(StringRedisTemplate redis,
                                         KnowPostMapper knowPostMapper,
                                         RelationMapper relationMapper,
                                         CounterReadService counterReadService) {
        this.redis = redis;
        this.knowPostMapper = knowPostMapper;
        this.relationMapper = relationMapper;
        this.counterReadService = counterReadService;
    }

    @Override
    public void rebuildAllCounters(long userId) {
        byte[] counters = new byte[FIELD_COUNT * FIELD_SIZE];
        long followings = relationMapper.countFollowingActive(userId);
        long followers = relationMapper.countFollowerActive(userId);

        List<String> postIds = knowPostMapper.listMyPublishedIds(userId).stream()
                .map(String::valueOf)
                .toList();

        long likesReceived = 0L;
        long favsReceived = 0L;
        if (!postIds.isEmpty()) {
            Map<String, Map<String, Long>> counts = counterReadService.getCountsBatch(
                    "knowpost", postIds, List.of("like", "fav"));
            for (String postId : postIds) {
                Map<String, Long> postCounts = counts.getOrDefault(postId, Map.of());
                likesReceived += postCounts.getOrDefault("like", 0L);
                favsReceived += postCounts.getOrDefault("fav", 0L);
            }
        }

        write32be(counters, 0, followings);
        write32be(counters, FIELD_SIZE, followers);
        write32be(counters, 2 * FIELD_SIZE, postIds.size());
        write32be(counters, 3 * FIELD_SIZE, likesReceived);
        write32be(counters, 4 * FIELD_SIZE, favsReceived);

        String key = UserCounterKeys.sdsKey(userId);
        redis.execute((RedisCallback<Void>) connection -> {
            connection.stringCommands().set(key.getBytes(StandardCharsets.UTF_8), counters);
            return null;
        });
    }

    private static void write32be(byte[] buffer, int offset, long value) {
        long bounded = Math.max(0, Math.min(value, 0xFFFF_FFFFL));
        buffer[offset] = (byte) ((bounded >>> 24) & 0xFF);
        buffer[offset + 1] = (byte) ((bounded >>> 16) & 0xFF);
        buffer[offset + 2] = (byte) ((bounded >>> 8) & 0xFF);
        buffer[offset + 3] = (byte) (bounded & 0xFF);
    }
}
