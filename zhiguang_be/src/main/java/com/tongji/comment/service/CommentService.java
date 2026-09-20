package com.tongji.comment.service;

import com.tongji.comment.api.dto.CommentResponse;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.model.Comment;
import com.tongji.knowpost.id.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentMapper commentMapper;
    private final SnowflakeIdGenerator idGenerator;

    /** @return the id of the stored comment, so the caller can trigger an Agent run. */
    public Long create(Long postId, Long userId, String content) {
        long id = idGenerator.nextId();
        commentMapper.insert(Comment.builder()
                .id(id)
                .postId(postId)
                .userId(userId)
                .content(content)
                .createTime(Instant.now())
                .build());
        return id;
    }

    public List<CommentResponse> listByPostId(long postId, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 50);
        int offset = Math.max(page - 1, 0) * limit;
        return commentMapper.listByPostId(postId, limit, offset);
    }
}
