package com.tongji.limit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * RAG 限流参数（由 Nacos 的 {@code rag.rate-limit.*} 配置绑定）。
 * <p>
 * 字段不写默认值，仅作占位：运行时由 Nacos 配置中心绑定；配合 {@code @RefreshScope}，
 * 配置变更后热更新。</p>
 */
@Component
@RefreshScope
@ConfigurationProperties(prefix = "rag.rate-limit")
public class RagRateLimitProperties {

    /** ① 全局令牌桶 QPS（分布式共享） */
    private long globalQps;

    /** ③ 每用户滑动窗口：窗口时长（毫秒） */
    private long perUserWindowMs;

    /** ③ 每用户滑动窗口：窗口内最大次数 */
    private int perUserMaxReq;

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
