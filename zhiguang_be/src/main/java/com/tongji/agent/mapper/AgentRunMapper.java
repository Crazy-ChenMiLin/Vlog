package com.tongji.agent.mapper;

import com.tongji.agent.model.AgentRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentRunMapper {

    void insert(AgentRun run);

    /**
     * Atomic claim: only the worker that flips PENDING -> RUNNING may proceed.
     * v5 §19.6: affected rows must be 1.
     */
    int claim(@Param("id") long id);

    int markSuccess(@Param("id") long id, @Param("replyCommentId") Long replyCommentId);

    int markFinished(@Param("id") long id, @Param("status") String status,
                     @Param("errorMessage") String errorMessage);

    AgentRun findById(@Param("id") long id);

    AgentRun findByTriggerCommentId(@Param("triggerCommentId") long triggerCommentId);
}
