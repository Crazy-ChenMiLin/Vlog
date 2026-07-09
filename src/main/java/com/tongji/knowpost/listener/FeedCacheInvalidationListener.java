package com.tongji.knowpost.listener;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tongji.counter.event.CounterEvent;
import com.github.benmanes.caffeine.cache.Cache;
import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.model.KnowPost;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * Feed 页面缓存失效与计数旁路更新监听器。
 *
 * <p>职责：</p>
 * - 监听点赞/收藏等计数事件（仅处理实体类型为 "knowpost"）；
 * - 根据“页面反向索引”（`feed:public:index:{eid}:{hour}`）定位受影响页面，
 *   同步更新本地 Caffeine 缓存与 Redis 页面 JSON（保持 TTL 不变）；
 * - 同步创作者收到的点赞/收藏用户维度计数（UserCounterService）。
 *
 * <p>设计要点：</p>
 * - preserveUserFlags=true 时仅更新本地缓存并保留用户态标志 liked/faved，
 *   写回 Redis 页面 JSON 时不携带用户态标志，避免污染共享缓存；
 * - 页面 JSON 写回前读取并沿用剩余 TTL，防止覆盖过期策略；
 * - 反向索引按小时维护，监听器会同时覆盖当前与上一个小时段的页面键。
 */
@Component
public class FeedCacheInvalidationListener {

    private final Cache<String, FeedPageResponse> feedPublicCache;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final com.tongji.counter.service.UserCounterService userCounterService;
    private final com.tongji.knowpost.mapper.KnowPostMapper knowPostMapper;

    public FeedCacheInvalidationListener(@Qualifier("feedPublicCache") Cache<String, FeedPageResponse> feedPublicCache,
                                         StringRedisTemplate redis,
                                         ObjectMapper objectMapper,
                                         com.tongji.counter.service.UserCounterService userCounterService,
                                         com.tongji.knowpost.mapper.KnowPostMapper knowPostMapper) {
        this.feedPublicCache = feedPublicCache;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.userCounterService = userCounterService;
        this.knowPostMapper = knowPostMapper;
    }

    /**
     * 监听计数事件并进行缓存更新。
     *
     * <p>流程：</p>
     * - 仅处理实体类型为 "knowpost" 的 like/fav 事件；
     * - 若可解析到内容的创作者 ID，则同步其“收到的点赞/收藏”计数；
     * - 通过最近两小时的反向索引集合定位受影响页面：
     *   - 更新本地 Caffeine 页缓存（保留 liked/faved 标志）；
     *   - 更新 Redis 页缓存（不携带用户态标志，保持 TTL）。
     * - 若某页面键在 Redis 未命中，则清理其索引引用，降低键空间噪音。
     */
    @EventListener
    public void onCounterChanged(CounterEvent event) {
        // ==================== 步骤1：过滤无效事件（只处理我们关心的） ====================
        // 1.1 判断：是不是【知文】的事件？不是 → 直接退出，不干活
        if (!"knowpost".equals(event.getEntityType())) {
            return;
        }
        // 1.2 获取事件类型：like=点赞，fav=收藏，view=浏览...
        String metric = event.getMetric();
        if ("like".equals(metric) || "fav".equals(metric)) {
            // ==================== 步骤2：拿到事件的核心数据 ====================
            // 2.1 被点赞的知文ID（比如 10086）
            String eid = event.getEntityId();
            int delta = event.getDelta();
            // ==================== 步骤3：更新【作者】的总计数 ====================
            try {
                // 3.2 根据知文ID，去数据库查这条知文的完整信息
                KnowPost post = knowPostMapper.findById(Long.valueOf(eid));
                // 3.3 如果知文存在 + 有作者
                if (post != null && post.getCreatorId() != null) {
                    // 3.4 拿到作者ID
                    long owner = post.getCreatorId();
                    // 3.5 点赞 → 作者的【总收到点赞数】+delta
                    if ("like".equals(metric)) {
                        userCounterService.incrementLikesReceived(owner, delta);
                    }
                    // 3.6 收藏 → 作者的【总收到收藏数】+delta
                    if ("fav".equals(metric)) {
                        userCounterService.incrementFavsReceived(owner, delta);
                    }
                }
            } catch (Exception ignored) {
            }
            // ==================== 步骤4：核心！反向索引 → 找到所有包含该知文的Feed页面 ====================
            // 4.1 计算当前小时（缓存按小时分桶，防止key太多）

            long hourSlot = System.currentTimeMillis() / 3600000L;
            // 4.2 新建集合：存放所有受影响的缓存页面Key
            Set<String> keys = new LinkedHashSet<>();
            // 4.3 查Redis：当前小时内，哪些Feed页面包含这条知文
            Set<String> cur = redis.opsForSet().members("feed:public:index:" + eid + ":" + hourSlot);
            if (cur != null) {
                keys.addAll(cur);
            }
            // 4.4 查Redis：上一小时内，哪些Feed页面包含这条知文（兼容跨小时缓存）
            Set<String> prev = redis.opsForSet().members("feed:public:index:" + eid + ":" + (hourSlot - 1));
            if (prev != null) {
                keys.addAll(prev);
            }
            // 4.5 没有任何缓存页面 → 直接结束
            if (keys.isEmpty()) {
                return;
            }
            // ==================== 步骤5：遍历所有受影响页面 → 更新两级缓存 ====================
            for (String key : keys) {
                // ========== 子步骤5.1：更新 L1 本地缓存（Caffeine） ==========
                // 5.1.1 从本地缓存获取这个页面的数据
                FeedPageResponse local = feedPublicCache.getIfPresent(key);
                if (local != null) {
                    FeedPageResponse updatedLocal = adjustPageCounts(local, eid, metric, delta, true);
                    // 5.1.4 把新数据放回本地缓存（替换旧数据）
                    feedPublicCache.put(key, updatedLocal);
                }

                // ========== 子步骤5.2：更新 L2 Redis 缓存 ==========
                // 5.2.1 从Redis获取这个页面的JSON字符串
                String cached = redis.opsForValue().get(key);
                if (cached != null) {
                    try {
                        // 5.2.3 【格式转换】JSON字符串 → FeedPageResponse对象（无反射！）
                        FeedPageResponse resp = objectMapper.readValue(cached, FeedPageResponse.class);
                        FeedPageResponse updated = adjustPageCounts(resp, eid, metric, delta, false);
                        // 5.2.5 写回Redis，保留原来的过期时间（不重置TTL）
                        //时间不重置
                        writePageJsonKeepingTtl(key, updated);
                    } catch (Exception ignored) {}
                } else {
                    redis.opsForSet().remove("feed:public:index:" + eid + ":" + hourSlot, key);
                }
            }
        }
    }

    /**
     * 调整页面快照中的目标内容计数。
     *
     * <p>行为：</p>
     * - 遍历页面 items，定位 id==eid 的项并更新 like/fav；
     * - preserveUserFlags=true：保留 liked/faved 标志用于本地缓存；
     * - preserveUserFlags=false：写回 Redis 页面 JSON 时不携带用户态标志；
     * - 返回新的页面响应快照。
     */
    private FeedPageResponse adjustPageCounts(FeedPageResponse page, String eid, String metric, int delta, boolean preserveUserFlags) {
        List<FeedItemResponse> items = new ArrayList<>(page.items().size());
        for (FeedItemResponse it : page.items()) {
                if (eid.equals(it.id())) {
                    Long like = it.likeCount();
                    Long fav = it.favoriteCount();

                    if ("like".equals(metric)) {
                        like = Math.max(0L, (like == null ? 0L : like) + delta);
                    }
                    if ("fav".equals(metric)) {
                        fav = Math.max(0L, (fav == null ? 0L : fav) + delta);
                    }

                    Boolean liked = preserveUserFlags ? it.liked() : null;
                    Boolean faved = preserveUserFlags ? it.faved() : null;

                    it = new FeedItemResponse(
                            it.id(),
                            it.title(),
                            it.description(),
                            it.coverImage(),
                            it.tags(),
                            it.authorAvatar(),
                            it.authorNickname(),
                            it.tagJson(),
                            like,
                            fav,
                            liked,
                            faved,
                            it.isTop()
                    );
                }
                items.add(it);
            }

        return new FeedPageResponse(items, page.page(), page.size(), page.hasMore());
    }

    /**
     * 写回页面 JSON 并保留原 TTL。
     *
     * <p>目的：</p>
     * - 保持页面缓存的过期策略一致，避免因覆盖写导致 TTL 重置；
     * - 若键未设置 TTL，则直接写入最新 JSON。
     */
    private void writePageJsonKeepingTtl(String key, FeedPageResponse page) {
        try {
            String json = objectMapper.writeValueAsString(page);
            long ttl = redis.getExpire(key);
            if (ttl > 0) {
                redis.opsForValue().set(key, json, java.time.Duration.ofSeconds(ttl));
            } else {
                redis.opsForValue().set(key, json);
            }
        } catch (Exception ignored) {}
    }
}
