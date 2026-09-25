package com.tongji.auth.verification;

/**
 * 验证码发送器接口（策略模式）。
 * <p>
 * 抽象真实发送行为（短信/邮件/站内），支持不同场景与账号标识。
 * 每个实现类声明自己能处理哪种收件标识，业务代码只依赖本接口，
 * 由 Spring 自动收集所有渠道并按需选择。
 * <p>
 * 默认实现可为日志输出（开发环境兜底），生产环境可替换为第三方服务集成。
 */
public interface CodeSender {

    /**
     * 判断本渠道能否给该收件标识发送验证码。
     * <p>
     * 收件标识可以是手机号或邮箱：邮件渠道只支持邮箱，短信渠道只支持手机号，
     * 日志渠道（开发兜底）支持所有标识。
     *
     * @param identifier 收件标识（手机号或邮箱）。
     * @return 能否发送。
     */
    boolean canSend(String identifier);

    /**
     * 发送验证码到指定标识。
     *
     * @param scene         验证码场景（REGISTER/LOGIN/RESET_PASSWORD）。
     * @param identifier    收件标识（手机号或邮箱）。
     * @param code          验证码内容。
     * @param expireMinutes 验证码有效期（分钟）。
     */
    void sendCode(VerificationScene scene, String identifier, String code, int expireMinutes);
}
