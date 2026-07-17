package com.tongji.auth.verification;

import com.tongji.auth.config.AuthProperties;
import com.tongji.auth.util.IdentifierValidator;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class VerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final VerificationCodeStore codeStore;
    private final CodeSender codeSender;
    private final ObjectProvider<MailCodeSender> mailCodeSenderProvider;
    private final StringRedisTemplate stringRedisTemplate;
    private final AuthProperties properties;

    public SendCodeResult sendCode(VerificationScene scene, String identifier) {
        return sendCode(scene, identifier, null);
    }

    public SendCodeResult sendCode(VerificationScene scene, String identifier, String deliveryEmail) {
        if (scene == null || !StringUtils.hasText(identifier)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请提供正确的验证码发送参数");
        }

        MailCodeSender mailCodeSender = null;
        String normalizedDeliveryEmail = null;
        if (StringUtils.hasText(deliveryEmail)) {
            normalizedDeliveryEmail = deliveryEmail.trim().toLowerCase();
            if (!IdentifierValidator.isValidEmail(normalizedDeliveryEmail)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码接收邮箱格式错误");
            }
            mailCodeSender = mailCodeSenderProvider.getIfAvailable();
            if (mailCodeSender == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "邮件验证码服务未启用");
            }
        }

        AuthProperties.Verification cfg = properties.getVerification();
        enforceSendInterval(scene, identifier, cfg.getSendInterval());
        enforceDailyLimit(scene, identifier, cfg.getDailyLimit());

        String code = generateNumericCode(cfg.getCodeLength());
        codeStore.saveCode(scene.name(), identifier, code, cfg.getTtl(), cfg.getMaxAttempts());
        codeSender.sendCode(scene, identifier, code, (int) cfg.getTtl().toMinutes());
        if (mailCodeSender != null) {
            mailCodeSender.send(scene, normalizedDeliveryEmail, code, (int) cfg.getTtl().toMinutes());
        }
        return new SendCodeResult(identifier, scene, (int) cfg.getTtl().toSeconds());
    }

    public VerificationCheckResult verify(VerificationScene scene, String identifier, String code) {
        if (scene == null || !StringUtils.hasText(identifier) || !StringUtils.hasText(code)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码校验参数不完整");
        }
        return codeStore.verify(scene.name(), identifier, code);
    }

    public void invalidate(VerificationScene scene, String identifier) {
        codeStore.invalidate(scene.name(), identifier);
    }

    private void enforceSendInterval(VerificationScene scene, String identifier, Duration interval) {
        if (interval.isZero() || interval.isNegative()) {
            return;
        }
        String key = "auth:code:last:" + scene.name() + ":" + identifier;
        String existing = stringRedisTemplate.opsForValue().get(key);
        if (existing != null) {
            throw new BusinessException(ErrorCode.VERIFICATION_RATE_LIMIT);
        }
        stringRedisTemplate.opsForValue().set(key, "1", interval);
    }

    private void enforceDailyLimit(VerificationScene scene, String identifier, int limit) {
        if (limit <= 0) {
            return;
        }
        String date = DAY_FORMAT.format(LocalDate.now());
        String key = "auth:code:count:" + scene.name() + ":" + identifier + ":" + date;
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, Duration.ofDays(1));
        }
        if (count != null && count > limit) {
            throw new BusinessException(ErrorCode.VERIFICATION_DAILY_LIMIT);
        }
    }

    private static String generateNumericCode(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(RANDOM.nextInt(10));
        }
        return builder.toString();
    }
}
