package com.tongji.auth.verification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "auth.mail", name = "enabled", havingValue = "true")
public class MailCodeSender {

    private final JavaMailSender mailSender;

    @Value("${auth.mail.from:${spring.mail.username:}}")
    private String from;

    public void send(VerificationScene scene, String deliveryEmail, String code, int expireMinutes) {
        SimpleMailMessage message = new SimpleMailMessage();
        if (StringUtils.hasText(from)) {
            message.setFrom(from);
        }
        message.setTo(deliveryEmail);
        message.setSubject("知光验证码");
        message.setText("""
                你的知光验证码是：%s

                场景：%s
                有效期：%d 分钟

                如果不是你本人操作，请忽略这封邮件。
                """.formatted(code, scene.name(), expireMinutes));
        mailSender.send(message);
        log.info("Verification code email sent scene={} deliveryEmail={} expireMinutes={}", scene, deliveryEmail, expireMinutes);
    }
}
