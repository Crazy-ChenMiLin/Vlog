package com.tongji.comment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateCommentRequest(
        @NotBlank @Pattern(regexp = "\\d+") String postId,
        @NotBlank @Size(max = 1024) String content
) {}
