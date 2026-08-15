package com.tongji.llm.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

/**
 * 从 Nacos AI Prompt 管理读取 RAG 的 system prompt。
 *
 * <p>Nacos 是 prompt 的<b>唯一来源</b>，代码里不再保留任何本地副本。读取链路：
 * {@code /nacos/v1/auth/login} 拿 token → {@code /nacos/v3/client/ai/prompt} 按 promptKey 拉模板。
 * 结果按 {@link RagPromptProperties#getCacheTtlSeconds()} 缓存，过期后自动重拉（准实时热更新）。
 * 拉不到直接抛异常，让问题显式暴露，不做静默降级。</p>
 */
@Slf4j
@Service
public class RagPromptService {

    public static final String KEY_PLANNER = "rag-planner-system";
    public static final String KEY_EVIDENCE = "rag-evidence-system";
    public static final String KEY_FINAL_ANSWER = "rag-final-answer-system";
    public static final String KEY_FINAL_ANSWER_WITH_HISTORY = "rag-final-answer-with-history-system";
    public static final String KEY_REWRITE = "rag-rewrite-system";
    public static final String KEY_HYDE = "rag-hyde-system";
    public static final String KEY_GRAPH_UNDERSTANDING = "rag-graph-understanding-system";
    public static final String KEY_DIRECT_ANSWER = "rag-direct-answer-system";

    private final RagPromptProperties props;
    private final RestTemplate http = new RestTemplate();
    private final Map<String, CachedPrompt> cache = new ConcurrentHashMap<>();

    private volatile String accessToken;
    private volatile long tokenExpireAt;

    public RagPromptService(RagPromptProperties props) {
        this.props = props;
    }

    /**
     * 取某个节点的 system prompt，唯一来源 Nacos。拉取失败直接抛异常，暴露问题。
     */
    public String getSystemPrompt(String key) {
        String template = load(key);
        if (!StringUtils.hasText(template)) {
            throw new IllegalStateException("RAG prompt '" + key
                    + "' unavailable from Nacos (" + props.getServerAddr() + ")");
        }
        return template;
    }

    private String load(String key) {
        CachedPrompt cached = cache.get(key);
        if (cached != null && System.currentTimeMillis() < cached.expireAt) {
            return cached.template;
        }
        try {
            String token = getAccessToken();
            String url = "http://" + props.getServerAddr()
                    + "/nacos/v3/client/ai/prompt?promptKey=" + key
                    + "&accessToken=" + token;
            ResponseEntity<Map> resp = http.getForEntity(url, Map.class);
            Object data = resp.getBody() == null ? null : resp.getBody().get("data");
            if (data instanceof Map) {
                Object template = ((Map<?, ?>) data).get("template");
                if (template != null && StringUtils.hasText(template.toString())) {
                    long expireAt = System.currentTimeMillis() + props.getCacheTtlSeconds() * 1000;
                    cache.put(key, new CachedPrompt(template.toString(), expireAt));
                    return template.toString();
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("Load RAG prompt '{}' from Nacos failed: {}", key, e.getMessage());
            return null;
        }
    }

    private String getAccessToken() {
        if (accessToken != null && System.currentTimeMillis() < tokenExpireAt - 60_000) {
            return accessToken;
        }
        synchronized (this) {
            if (accessToken != null && System.currentTimeMillis() < tokenExpireAt - 60_000) {
                return accessToken;
            }
            try {
                String url = "http://" + props.getServerAddr() + "/nacos/v1/auth/login";
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add("username", props.getUsername());
                form.add("password", props.getPassword());
                ResponseEntity<Map> resp = http.postForEntity(url, new HttpEntity<>(form, headers), Map.class);
                Map<?, ?> body = resp.getBody();
                if (body != null && body.get("accessToken") != null) {
                    accessToken = body.get("accessToken").toString();
                    long ttlSeconds = 18_000;
                    Object ttl = body.get("tokenTtl");
                    if (ttl instanceof Number) {
                        ttlSeconds = ((Number) ttl).longValue();
                    }
                    tokenExpireAt = System.currentTimeMillis() + ttlSeconds * 1000;
                    return accessToken;
                }
            } catch (Exception e) {
                log.warn("Nacos login failed: {}", e.getMessage());
            }
            return null;
        }
    }

    private record CachedPrompt(String template, long expireAt) {
    }
}
