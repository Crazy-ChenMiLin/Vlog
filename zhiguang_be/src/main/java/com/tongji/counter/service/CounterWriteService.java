package com.tongji.counter.service;

import java.util.List;

/**
 * 内容实体计数写服务。
 */
public interface CounterWriteService {
    boolean like(String entityType, String entityId, long userId);

    boolean unlike(String entityType, String entityId, long userId);

    boolean fav(String entityType, String entityId, long userId);

    boolean unfav(String entityType, String entityId, long userId);

    /**
     * 实体计数结构不存在时初始化为零，已有数据不覆盖。
     */
    void initZeroCountsIfAbsent(String entityType, String entityId, List<String> metrics);
}
