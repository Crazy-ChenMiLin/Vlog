package com.tongji.counter.service;

import java.util.List;
import java.util.Map;

public interface CounterReadService {
    /**
     * 获取指定指标的计数。
     */
    Map<String, Long> getCounts(String entityType, String entityId, List<String> metrics);

    Map<String, Map<String, Long>> getCountsBatch(String entityType, List<String> entityIds, List<String> metrics);

    /**
     * 判断是否点赞/收藏（位图）。
     */
    boolean isLiked(String entityType, String entityId, long userId);
    boolean isFaved(String entityType, String entityId, long userId);
}
