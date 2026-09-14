package com.tongji.counter.service.impl;

import com.tongji.counter.schema.UserCounterKeys;
import com.tongji.counter.service.UserCounterService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户维度计数服务实现。
 *
 * <p>职责：</p>
 * - 异步维护关注/粉丝/发文/获赞/获收藏计数（SDS）。
 */
@Service
public class UserCounterServiceImpl implements UserCounterService {
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> incrScript;

    public UserCounterServiceImpl(StringRedisTemplate redis) {
        this.redis = redis;
        this.incrScript = new DefaultRedisScript<>();
        this.incrScript.setResultType(Long.class);
        // 用户维度计数原子折叠（1 基坐标）
        this.incrScript.setScriptText(INCR_FIELD_LUA);
    }

    /** 增量更新关注数 */
    @Override
    public void incrementFollowings(long userId, int delta) {
        String key = UserCounterKeys.sdsKey(userId);
        redis.execute(incrScript, List.of(key), "5", "4", "1", String.valueOf(delta));
    }

    /** 增量更新粉丝数 */
    @Override
    public void incrementFollowers(long userId, int delta) {
        String key = UserCounterKeys.sdsKey(userId);
        redis.execute(incrScript, List.of(key), "5", "4", "2", String.valueOf(delta));
    }

    /** 增量更新发文数 */
    @Override
    public void incrementPosts(long userId, int delta) {
        String key = UserCounterKeys.sdsKey(userId);
        redis.execute(incrScript, List.of(key), "5", "4", "3", String.valueOf(delta));
    }

    /** 增量更新获赞数（作者维度） */
    @Override
    public void incrementLikesReceived(long userId, int delta) {
        String key = UserCounterKeys.sdsKey(userId);
        redis.execute(incrScript, List.of(key), "5", "4", "4", String.valueOf(delta));
    }

    /** 增量更新获收藏数（作者维度） */
    @Override
    public void incrementFavsReceived(long userId, int delta) {
        String key = UserCounterKeys.sdsKey(userId);
        redis.execute(incrScript, List.of(key), "5", "4", "5", String.valueOf(delta));
    }

    private static final String INCR_FIELD_LUA = """
            
            local cntKey = KEYS[1]
            local schemaLen = tonumber(ARGV[1])
            local fieldSize = tonumber(ARGV[2])
            local idx = tonumber(ARGV[3])
            local delta = tonumber(ARGV[4])
            local function read32be(s, off)
              local b = {string.byte(s, off+1, off+4)}
              local n = 0
              for i=1,4 do n = n * 256 + b[i] end
              return n
            end
            local function write32be(n)
              local t = {}
              for i=4,1,-1 do t[i] = n % 256; n = math.floor(n/256) end
              return string.char(unpack(t))
            end
            local cnt = redis.call('GET', cntKey)
            if not cnt then cnt = string.rep(string.char(0), schemaLen * fieldSize) end
            local off = (idx - 1) * fieldSize
            local v = read32be(cnt, off) + delta
            if v < 0 then v = 0 end
            local seg = write32be(v)
            cnt = string.sub(cnt, 1, off) .. seg .. string.sub(cnt, off+fieldSize+1)
            redis.call('SET', cntKey, cnt)
            return 1
            """;

}

