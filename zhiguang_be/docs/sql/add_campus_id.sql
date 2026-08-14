-- 校园账号登录（CQUT-Auth OIDC）所需字段
-- 在 users 表新增 campus_id（校园认证 OIDC sub 唯一标识），并建立唯一索引。
ALTER TABLE users
    ADD COLUMN campus_id VARCHAR(64) NULL COMMENT '校园认证(CQUT-Auth OIDC)用户唯一标识 sub' AFTER github_id;

ALTER TABLE users
    ADD UNIQUE KEY idx_users_campus_id (campus_id);
