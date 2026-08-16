package com.tongji.comment.api.dto;

import java.time.Instant;

public record CommentResponse(
        Long id,
        Long postId,
        Long userId,
        String content,
        String nickname,
        String avatar,
        Instant createTime
) {}
