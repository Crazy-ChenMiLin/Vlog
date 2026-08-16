package com.tongji.comment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Comment {
    private Long id;
    private Long postId;
    private Long userId;
    private String content;
    private Instant createTime;
}
