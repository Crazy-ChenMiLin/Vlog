package com.tongji.knowpost.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RagChatStreamRequest(
        Long conversationId,
        @NotBlank String scope,
        Long postId,
        @NotBlank @Size(max = 500) String question,
        @Min(1) @Max(20) Integer topK
) {
    public int safeTopK() {
        return topK == null ? 5 : topK;
    }
}
