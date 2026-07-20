package com.tongji.llm.graphService;

import com.tongji.llm.graphService.model.GraphEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体（概念）识别服务：用「概念-别名」词典做关键词匹配，从问题中抽取知识概念。
 *
 * <p>这是图谱增强链路的第一步。采用轻量的词典匹配而非 NER 模型：
 * 把核心概念及其同义表述（中英文、口语化说法）列成白名单，命中即认为问题涉及该概念。
 * 优点是零外部依赖、可控、便于针对业务术语调优；代价是召回上限受词典覆盖度约束。
 *
 * <p>匹配为「子串包含」语义（如「击穿」可命中「缓存击穿」），因此对别名列表的取值
 * 需要人工把控，避免过短别名造成误召回（例如「命中」可能误命中非缓存场景）。
 */
@Service
public class GraphEntityMatchService {
    /**
     * 概念别名词典：key 为规范化概念名（需与 Neo4j 中 Concept.name 对齐），
     * value 为该概念在用户问题中可能出现的各种表述。
     * 使用 LinkedHashMap 保证遍历顺序稳定（先定义的概念优先匹配）。
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
     * 从问题文本中匹配知识概念。
     *
     * @param question 用户原始问题
     * @return 命中的概念列表（含其全部别名）；空文本或无机概念命中时返回空列表
     */
    public List<GraphEntity> match(String question) {
        // 空问题直接返回，避免无意义遍历
        if (!StringUtils.hasText(question)) {
            return List.of();
        }
        // 统一小写并去首尾空白，做大小写不敏感的子串匹配
        String normalized = question.trim().toLowerCase();
        List<GraphEntity> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : CONCEPT_ALIASES.entrySet()) {
            // 任一别名作为子串出现在问题中，即判定该概念命中
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
