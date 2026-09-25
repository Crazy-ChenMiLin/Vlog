package com.tongji.auth.verification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 开发/测试用验证码发送器。
 * <p>
 * 不实际发送，仅记录日志，便于本地开发与集成测试。
 * 作为兜底渠道，支持所有收件标识，且优先级最低（{@link Ordered#LOWEST_PRECEDENCE}），
 * 真实渠道（如邮件）存在时优先被选中。
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class LoggingCodeSender implements CodeSender {

    @Override
    public boolean canSend(String identifier) {
        return true;
    }

    @Override
    public void sendCode(VerificationScene scene, String identifier, String code, int expireMinutes) {
        log.info("Send verification code scene={} identifier={} code={} expireMinutes={}", scene, identifier, code, expireMinutes);
    }
}
