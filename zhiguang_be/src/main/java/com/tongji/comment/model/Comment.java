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
    /** USER = a real person, AGENT = a "知光 Agent" reply. */
    private String authorType;
    /** For AGENT comments: the user comment that triggered this run. */
    private Long replyCommentId;
    private String content;
    private Instant createTime;
}
