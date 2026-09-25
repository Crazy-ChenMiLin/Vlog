package com.tongji.auth.model;

import com.tongji.user.domain.User;

/**
 * 认证成功结果（模型层）：用户 + 审计标识 + 审计渠道名。
 * <p>
 * auditIdentifier 是登录日志里展示用的账号标识，各渠道不同：
 * - 密码/验证码：手机号或邮箱；
 * - 校园网：preferred_username 或 campusId；
 * - GitHub：GitHub 用户名。
 * channel 是审计日志用的渠道名（与历史数据保持一致）：
 * PASSWORD / CODE / CAMPUS / GITHUB。
 */
public record LoginSuccess(
        User user,
        String auditIdentifier,
        String channel
) {
}
