package com.tongji.llm.memoryService.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RagMessage {
    private Long id;
    private Long conversationId;
    private Long userId;
    private String role;
    private String content;
    private LocalDateTime createdAt;
}
