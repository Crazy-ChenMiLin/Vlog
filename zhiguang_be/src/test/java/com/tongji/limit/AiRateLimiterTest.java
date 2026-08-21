package com.tongji.limit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RSemaphore;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiRateLimiterTest {

    @Mock
    private RedissonClient redisson;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private RRateLimiter globalLimiter;
    @Mock
    private RSemaphore streamSemaphore;

    @Test
    void initReplacesTheExistingRedisRateWithTheCurrentNacosValue() {
        RagRateLimitProperties properties = new RagRateLimitProperties();
        properties.setGlobalQps(23);
        properties.setPerUserWindowMs(60_000);
        properties.setPerUserMaxReq(50);
        when(redisson.getRateLimiter("rag:chat:limiter:global")).thenReturn(globalLimiter);
        when(redisson.getSemaphore("rag:chat:semaphore")).thenReturn(streamSemaphore);

        new AiRateLimiter(redisson, redis, properties).init();

        verify(globalLimiter).setRate(RateType.OVERALL, 23, 1, RateIntervalUnit.SECONDS);
        verify(streamSemaphore).trySetPermits(80);
    }
}
