package com.tongji.agent.service;

import com.tongji.agent.client.PithagorasClient;
import com.tongji.agent.config.AgentProperties;
import com.tongji.agent.mapper.AgentRunMapper;
import com.tongji.agent.model.AgentRun;
import com.tongji.agent.model.AgentRunStatus;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;

/**
 * Bounded background executor for Agent Runs (v5 §19.3).
 *
 * <p>A hot post must never create unbounded threads, so the pool and its queue
 * are both fixed. Work beyond the queue is rejected and the run is marked
 * FAILED rather than silently dropped.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRunExecutor {

    private final AgentRunMapper agentRunMapper;
    private final PithagorasClient pithagorasClient;
    private final AgentProperties props;
    private final AgentRunFinishService finishService;

    private ExecutorService pool;

    private synchronized ExecutorService pool() {
        if (pool == null) {
            pool = new ThreadPoolExecutor(
                    props.getPoolSize(),
                    props.getPoolSize(),
                    60L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(props.getQueueCapacity()),
                    new ThreadPoolExecutor.AbortPolicy()
            );
        }
        return pool;
    }

    public void submit(long runId) {
        try {
            pool().submit(() -> run(runId));
        } catch (RejectedExecutionException e) {
            log.warn("agent run {} rejected: queue full", runId);
            agentRunMapper.markFinished(runId, AgentRunStatus.FAILED.name(), "Agent 队列已满，请稍后重试");
        }
    }

    void run(long runId) {
        // Atomic claim: only one worker may proceed (v5 §19.6).
        if (agentRunMapper.claim(runId) != 1) {
            log.info("agent run {} already claimed — skipping", runId);
            return;
        }
        AgentRun run = agentRunMapper.findById(runId);
        if (run == null) return;

        Instant start = Instant.now();
        try {
            String reply = pithagorasClient.ask(
                    run.getPostId(), run.getActorUserId(), "user-" + run.getActorUserId(),
                    messageFor(run));
            finishService.finishSuccess(runId, reply);
            log.info("agent run {} succeeded in {} ms", runId, Duration.between(start, Instant.now()).toMillis());
        } catch (Exception e) {
            boolean timeout = e instanceof java.net.http.HttpTimeoutException
                    || e instanceof java.util.concurrent.TimeoutException;
            String status = timeout ? AgentRunStatus.TIMEOUT.name() : AgentRunStatus.FAILED.name();
            String userMessage = timeout
                    ? "知光 Agent：本次处理超时，请稍后重试。"
                    : "知光 Agent：本次处理失败，请稍后重试。";
            // v5 §19.5: never silent, never retried automatically.
            finishService.finishFailure(runId, status, userMessage, e.getMessage());
            log.warn("agent run {} {} after {} ms: {}", runId, status,
                    Duration.between(start, Instant.now()).toMillis(), e.getMessage());
        }
    }

    private String messageFor(AgentRun run) {
        // The post id is server-side context: the Agent can then call get_post.
        return "用户想了解帖子 " + run.getPostId() + "。请使用 get_post 工具读取该帖子内容后回答用户的问题。";
    }

    @PreDestroy
    public void shutdown() {
        if (pool != null) pool.shutdownNow();
    }
}
