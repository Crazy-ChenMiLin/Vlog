package com.tongji.comment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCommentRequest(
        @NotNull Long postId,
        @NotBlank @Size(max = 1024) String content
) {}
