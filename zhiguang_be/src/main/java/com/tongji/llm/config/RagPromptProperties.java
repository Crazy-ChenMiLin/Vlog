package com.tongji.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Nacos AI Prompt 客户端读取配置。
 *
 * <p>对应 application.yml 的 {@code rag.prompt} 段。账号密码默认复用 Nacos 的
 * {@code NACOS_USERNAME}/{@code NACOS_PASSWORD} 环境变量（默认 nacos/nacos）。</p>
 */
@Component
@ConfigurationProperties(prefix = "rag.prompt")
public class RagPromptProperties {

    /** Nacos 服务地址，如 100.83.242.114:8848。 */
    private String serverAddr = "100.83.242.114:8848";

    /** Nacos 用户名。 */
    private String username = "nacos";

    /** Nacos 密码。 */
    private String password = "nacos";

    /** prompt 本地缓存有效期（秒）。过期后下次调用重新拉取，实现准实时热更新。 */
    private long cacheTtlSeconds = 60;

    public String getServerAddr() {
        return serverAddr;
    }

    public void setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public long getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(long cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }
}
