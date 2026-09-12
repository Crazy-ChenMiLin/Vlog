package com.tongji.llm.graphService;

import com.tongji.llm.graphService.model.GraphEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用「概念—别名」词典从用户问题中匹配知识图谱实体。
 *
 * <p>词典匹配是图谱增强链路的稳定兜底：无外部依赖且便于针对业务术语调优，
 * 但召回能力受词典覆盖范围限制。关系意图和复杂语义由
 * {@link QueryUnderstandingService} 补充。</p>
 *
 * <p>当前采用大小写不敏感的子串匹配，因此别名不宜过短，以免产生误召回。</p>
 */
@Service
public class QueryEntityMatchService {
    /**
     * 概念别名词典：key 为规范化概念名（需与 Neo4j 中 Concept.name 对齐），
     * value 为该概念在用户问题中可能出现的各种表述。
     * 使用 LinkedHashMap 保证输出顺序与词典定义顺序一致。
     */
    private static final Map<String, List<String>> CONCEPT_ALIASES = new LinkedHashMap<>();

    static {
        CONCEPT_ALIASES.put("缓存命中", List.of("缓存命中", "命中率", "命中"));
        CONCEPT_ALIASES.put("缓存穿透", List.of("缓存穿透", "穿透", "cache penetration"));
        CONCEPT_ALIASES.put("缓存击穿", List.of("缓存击穿", "击穿", "热点 key 失效", "cache breakdown"));
        CONCEPT_ALIASES.put("缓存雪崩", List.of("缓存雪崩", "雪崩", "大量 key 同时过期", "cache avalanche"));
        CONCEPT_ALIASES.put("布隆过滤器", List.of("布隆过滤器", "布隆", "Bloom Filter"));
        CONCEPT_ALIASES.put("缓存空值", List.of("缓存空值", "空值缓存", "缓存 null"));
        CONCEPT_ALIASES.put("互斥锁", List.of("互斥锁", "分布式锁", "mutex"));
        CONCEPT_ALIASES.put("随机过期时间", List.of("随机过期时间", "随机过期", "过期时间随机", "随机 TTL"));
        CONCEPT_ALIASES.put("热点 key", List.of("热点 key", "热点key", "热 key"));
        CONCEPT_ALIASES.put("Redis 缓存问题", List.of("Redis 缓存问题", "缓存三大问题", "Redis 缓存"));
        CONCEPT_ALIASES.put("Redis", List.of("Redis"));
        CONCEPT_ALIASES.put("JWT", List.of("JWT", "Json Web Token"));
        CONCEPT_ALIASES.put("Access Token", List.of("Access Token", "access token", "访问令牌"));
        CONCEPT_ALIASES.put("Refresh Token", List.of("Refresh Token", "refresh token", "刷新令牌"));
        CONCEPT_ALIASES.put("RedisRefreshTokenStore", List.of("RedisRefreshTokenStore", "refresh token store"));
        CONCEPT_ALIASES.put("JwtService", List.of("JwtService"));
    }

    /**
     * 按别名做大小写不敏感的子串匹配。
     *
     * @param question 用户原始问题
     * @return 命中的概念及其完整别名；问题为空或没有命中时返回空列表
     */
    public List<GraphEntity> match(String question) {
        if (!StringUtils.hasText(question)) {
            return List.of();
        }
        String normalized = question.trim().toLowerCase();
        List<GraphEntity> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : CONCEPT_ALIASES.entrySet()) {
            boolean matched = entry.getValue().stream()
                    .filter(StringUtils::hasText)
                    .anyMatch(alias -> normalized.contains(alias.toLowerCase()));
            if (matched) {
                result.add(new GraphEntity(entry.getKey(), entry.getValue()));
            }
        }
        return result;
    }
}
