package com.tongji.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent runtime settings (v5 §19).
 *
 * <p>The Pithagoras webhook blocks until the whole Agent turn finishes, so the
 * Java side must impose its own, shorter timeout — 120s by default — and never
 * wait forever.</p>
 */
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /** Base URL of the Pithagoras webhook, e.g. http://127.0.0.1:4180 */
    private String pithagorasBaseUrl = "http://127.0.0.1:4180";
    /** Shared service secret sent as X-Portal-Secret. Java is the only caller (v5 §16.1). */
    private String pithagorasSecret = "";
    /** Hard cap on one Agent turn. */
    private int timeoutSeconds = 120;
    /** Bounded worker pool — a hot post must not create unbounded threads. */
    private int poolSize = 4;
    private int queueCapacity = 100;
    /** Fixed account backing Agent comments (comments.user_id is a FK). */
    private long commentUserId = 999999999L;

    public String getPithagorasBaseUrl() { return pithagorasBaseUrl; }
    public void setPithagorasBaseUrl(String v) { this.pithagorasBaseUrl = v; }
    public String getPithagorasSecret() { return pithagorasSecret; }
    public void setPithagorasSecret(String v) { this.pithagorasSecret = v; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
    public int getPoolSize() { return poolSize; }
    public void setPoolSize(int v) { this.poolSize = v; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int v) { this.queueCapacity = v; }
    public long getCommentUserId() { return commentUserId; }
    public void setCommentUserId(long v) { this.commentUserId = v; }
}
