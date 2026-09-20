package com.tongji.comment.api.dto;

import java.time.Instant;

public record CommentResponse(
        Long id,
        Long postId,
        Long userId,
        /** USER or AGENT — lets the UI mark "知光 Agent" replies. */
        String authorType,
        String content,
        String nickname,
        String avatar,
        Instant createTime
) {}
