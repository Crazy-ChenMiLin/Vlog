package com.tongji.auth.service;

/**
 * OAuth 登录渠道接口（service 层子接口）。
 * <p>
 * 只有需要"先跳转第三方授权页"的登录方式（校园网、GitHub）才实现本接口，
 * 额外提供生成授权链接的能力。密码/验证码登录不需要。
 */
public interface OAuthChannelService extends LoginChannelService {

    /**
     * 生成第三方授权页链接，前端拿到后跳转。
     *
     * @return 授权页完整 URL。
     */
    String getLoginUrl();
}
