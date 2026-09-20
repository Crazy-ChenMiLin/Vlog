package com.tongji.agent.model;

/** v5 §17: the only statuses an Agent Run may hold. */
public enum AgentRunStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    TIMEOUT
}
