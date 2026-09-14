package com.tongji.counter.application;

/**
 * 跨模块编排用户维度计数的全量重建。
 */
public interface UserCounterRebuildService {
    void rebuildAllCounters(long userId);
}
