package com.tongji.llm.external;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Component
@RefreshScope
@ConfigurationProperties(prefix = "rag.external.github-official-go")
public class GithubOfficialGoProperties {
    private boolean enabled = true;
    /** Supplied only through GITHUB_EXTERNAL_SEARCH_TOKEN; never place it in YAML. */
    private String token;
    private String baseUrl = "https://api.github.com";
    private String repository = "golang/go";
    private int maxResults = 3;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = repository; }
    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int maxResults) { this.maxResults = Math.max(1, Math.min(5, maxResults)); }
}
