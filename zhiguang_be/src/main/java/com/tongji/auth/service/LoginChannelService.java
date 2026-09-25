package com.tongji.auth.service;

import com.tongji.auth.model.LoginCommand;
import com.tongji.auth.model.LoginSuccess;

/**
 * 登录渠道接口（策略模式，service 层接口）。
 * <p>
 * 每一种登录方式（密码/验证码、校园网、GitHub 等）都是一个独立的实现类
 * （位于 service.impl 包，命名 XxxChannelServiceImpl），各自只负责"把登录凭证变成用户"。
 * 签发令牌、记录审计日志等公共逻辑统一由 {@link AuthService} 在认证成功后处理，不在渠道内重复。
 */
public interface LoginChannelService {

    /**
     * 渠道标识，用于路由分发。
     *
     * @return 渠道名，如 password / campus / github。
     */
    String getType();

    /**
     * 认证：把登录凭证变成用户。
     * <p>
     * 实现类只做自己独特的认证逻辑；认证成功后返回用户与审计标识，
     * 由 AuthService 统一签发令牌、记录成功日志。
     *
     * @param cmd 登录参数（不同渠道使用其中不同字段）。
     * @return 认证成功结果（用户 + 审计标识）。
     */
    LoginSuccess authenticate(LoginCommand cmd);
}
