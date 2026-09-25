package com.tongji.auth.model;

/**
 * 登录命令（模型层）：一次登录所需的全部参数。
 * <p>
 * 不同渠道使用其中不同字段，用不到的字段为 null：
 * - 密码/验证码登录：identifierType / identifier / password 或 verificationCode；
 * - OAuth 登录（校园网、GitHub）：oauthCode（+ 校园网需要 state）。
 * clientInfo 用于审计日志（成功日志由 AuthService 记录，渠道内失败日志也可能用到）。
 */
public record LoginCommand(
        String type,
        IdentifierType identifierType,
        String identifier,
        String password,
        String verificationCode,
        String oauthCode,
        String state,
        ClientInfo clientInfo
) {
}
