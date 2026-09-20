package com.tongji.agent.service;

import com.tongji.agent.mapper.AgentRunMapper;
import com.tongji.agent.model.AgentRun;
import com.tongji.agent.model.AgentRunStatus;
import com.tongji.knowpost.id.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Detects "@知光" in a freshly stored comment and starts one Agent Run.
 *
 * <p>The comment is already committed by the time this runs (v5 §19.3): the
 * request thread returns immediately and the Agent runs in the background.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTriggerService {

    static final String MENTION = "@知光";

    private final AgentRunMapper agentRunMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final AgentRunExecutor executor;

    /** No-op unless the comment actually mentions the agent. */
    public void onCommentCreated(long commentId, long postId, long userId, String content) {
        if (content == null || !content.contains(MENTION)) return;

        String question = content.replace(MENTION, "").trim();
        if (question.isEmpty()) question = "请阅读并总结本帖内容。";

        AgentRun run = AgentRun.builder()
                .id(idGenerator.nextId())
                .postId(postId)
                .triggerCommentId(commentId)
                .actorUserId(userId)
                .status(AgentRunStatus.PENDING.name())
                .build();

        try {
            agentRunMapper.insert(run);
        } catch (DuplicateKeyException e) {
            // v5 §19.6: one trigger comment = at most one run.
            log.info("agent run already exists for comment {} — skipping", commentId);
            return;
        }
        log.info("agent run created id={} post={} comment={} actor={}", run.getId(), postId, commentId, userId);
        executor.submit(run.getId());
    }
}
