package com.tongji.auth.verification;

import com.tongji.auth.util.IdentifierValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 邮件验证码发送渠道。
 * <p>
 * 实现 {@link CodeSender}：只支持"邮箱"收件标识，真正发送邮件。
 * 通过配置 {@code auth.mail.enabled=true} 启用；未启用时该渠道不会注册为 Bean，
 * 业务代码不需要感知渠道是否存在。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "auth.mail", name = "enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MailCodeSender implements CodeSender {

    private final JavaMailSender mailSender;

    @Value("${auth.mail.from:${spring.mail.username:}}")
    private String from;

    @Override
    public boolean canSend(String identifier) {
        return IdentifierValidator.isValidEmail(identifier);
    }

    @Override
    public void sendCode(VerificationScene scene, String identifier, String code, int expireMinutes) {
        if (!canSend(identifier)) {
            log.warn("Skip email verification code: identifier is not an email, identifier={}", identifier);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        if (StringUtils.hasText(from)) {
            message.setFrom(from);
        }
        message.setTo(identifier);
        message.setSubject("知光验证码");
        message.setText("""
                你的知光验证码是：%s

                场景：%s
                有效期：%d 分钟

                如果不是你本人操作，请忽略这封邮件。
                """.formatted(code, scene.name(), expireMinutes));
        mailSender.send(message);
        log.info("Verification code email sent scene={} email={} expireMinutes={}", scene, identifier, expireMinutes);
    }
}
