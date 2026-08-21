package com.tongji.limit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * RAG 限流参数（由 Nacos 的 {@code rag.rate-limit.*} 配置绑定）。
 * <p>
 * 运行时可由 Nacos 覆盖；保留安全默认值，避免配置中心漏配时将每用户上限绑定为 0，
 * 从而拒绝所有问答请求。</p>
 */
@Component
@RefreshScope
@ConfigurationProperties(prefix = "rag.rate-limit")
public class RagRateLimitProperties {

    /** ① 全局令牌桶 QPS（分布式共享） */
    private long globalQps = 10;

    /** ③ 每用户滑动窗口：窗口时长（毫秒） */
    private long perUserWindowMs = 60_000;

    /** ③ 每用户滑动窗口：窗口内最大次数 */
    private int perUserMaxReq = 10;

    public long getGlobalQps() {
        return globalQps;
    }

    public void setGlobalQps(long globalQps) {
        this.globalQps = globalQps;
    }

    public long getPerUserWindowMs() {
        return perUserWindowMs;
    }

    public void setPerUserWindowMs(long perUserWindowMs) {
        this.perUserWindowMs = perUserWindowMs;
    }

    public int getPerUserMaxReq() {
        return perUserMaxReq;
    }

    public void setPerUserMaxReq(int perUserMaxReq) {
        this.perUserMaxReq = perUserMaxReq;
    }
}
