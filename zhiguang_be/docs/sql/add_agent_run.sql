-- 知光 Shared Agent M4/M5（需求 v5 §17）所需表与字段
-- 1) agent_run：一次 @知光 触发对应最多一个 Agent Run（UNIQUE(trigger_comment_id) 保证幂等）
-- 2) agent_audit：Tool / 决策审计
-- 3) comments：增加 author_type 区分真人评论与 Agent 评论；reply_comment_id 关联触发评论
-- 4) users：固定「知光 Agent」账号，满足 comments.user_id 外键（fk_comments_user）

CREATE TABLE IF NOT EXISTS agent_run (
    id BIGINT NOT NULL COMMENT '雪花算法生成',
    post_id BIGINT NOT NULL COMMENT '所属帖子',
    trigger_comment_id BIGINT NOT NULL COMMENT '触发评论ID（幂等键）',
    actor_user_id BIGINT NOT NULL COMMENT '触发者用户ID',
    status VARCHAR(32) NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/TIMEOUT',
    skill_name VARCHAR(128) NULL COMMENT '使用的 Skill',
    reply_comment_id BIGINT NULL COMMENT '回写的 Agent 评论ID',
    started_at DATETIME NULL,
    finished_at DATETIME NULL,
    error_message VARCHAR(512) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_run_trigger_comment (trigger_comment_id),
    KEY ix_agent_run_status (status),
    KEY ix_agent_run_post (post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_audit (
    id BIGINT NOT NULL COMMENT '雪花算法生成',
    run_id BIGINT NULL COMMENT '关联 agent_run.id',
    actor_user_id BIGINT NULL COMMENT '操作者',
    tool_name VARCHAR(128) NULL COMMENT '工具名',
    action VARCHAR(256) NULL COMMENT '具体动作',
    decision VARCHAR(32) NULL COMMENT 'ALLOW/DENY/...',
    reason VARCHAR(512) NULL COMMENT '原因',
    cost_ms INT NULL COMMENT '耗时',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY ix_agent_audit_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Agent 评论身份：USER / AGENT
ALTER TABLE comments
    ADD COLUMN author_type VARCHAR(16) NOT NULL DEFAULT 'USER' COMMENT 'USER=真人评论, AGENT=知光Agent回复' AFTER user_id;

-- 关联被回复的触发评论，用于回查与重复写回保护
ALTER TABLE comments
    ADD COLUMN reply_comment_id BIGINT NULL COMMENT 'Agent 回复所对应的触发评论ID' AFTER author_type;

-- Agent 评论的作者账号（固定 ID，供 comments.user_id 外键使用）
INSERT INTO users (id, nickname, created_at, updated_at)
VALUES (999999999, '知光 Agent', NOW(), NOW())
ON DUPLICATE KEY UPDATE nickname = nickname;
