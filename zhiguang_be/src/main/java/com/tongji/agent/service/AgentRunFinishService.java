package com.tongji.agent.service;

import com.tongji.agent.config.AgentProperties;
import com.tongji.agent.mapper.AgentRunMapper;
import com.tongji.agent.model.AgentRun;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.model.Comment;
import com.tongji.knowpost.id.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Writes the Agent result back as a comment.
 *
 * <p>v5 §19.6: creating the Agent comment, setting reply_comment_id and moving
 * the run to SUCCESS must happen in one transaction. Failure paths also write a
 * visible comment — v5 §19.5 forbids silent failure.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunFinishService {

    /** comments.content is VARCHAR(1024). */
    private static final int MAX_CONTENT = 1000;

    private final CommentMapper commentMapper;
    private final AgentRunMapper agentRunMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final AgentProperties props;

    @Transactional
    public void finishSuccess(long runId, String reply) {
        AgentRun run = agentRunMapper.findById(runId);
        if (run == null) return;
        long commentId = insertAgentComment(run.getPostId(), run.getTriggerCommentId(), reply);
        agentRunMapper.markSuccess(runId, commentId);
    }

    @Transactional
    public void finishFailure(long runId, String status, String userMessage, String errorMessage) {
        AgentRun run = agentRunMapper.findById(runId);
        if (run == null) return;
        insertAgentComment(run.getPostId(), run.getTriggerCommentId(), userMessage);
        agentRunMapper.markFinished(runId, status, truncate(errorMessage));
    }

    private long insertAgentComment(long postId, long triggerCommentId, String content) {
        long id = idGenerator.nextId();
        commentMapper.insert(Comment.builder()
                .id(id)
                .postId(postId)
                .userId(props.getCommentUserId())
                .authorType("AGENT")
                .replyCommentId(triggerCommentId)
                .content(truncate(content))
                .createTime(Instant.now())
                .build());
        return id;
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() <= MAX_CONTENT ? s : s.substring(0, MAX_CONTENT);
    }
}
