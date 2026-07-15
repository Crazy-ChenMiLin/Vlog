package com.tongji.llm.rag.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RagConversation {
    private Long id;
    private Long userId;
    private String scope;
    private Long postId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
