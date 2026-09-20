package com.tongji.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** One Agent Run per triggering comment (v5 §19.6 idempotency). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRun {
    private Long id;
    private Long postId;
    private Long triggerCommentId;
    private Long actorUserId;
    private String status;
    private String skillName;
    private Long replyCommentId;
    private Instant startedAt;
    private Instant finishedAt;
    private String errorMessage;
}
