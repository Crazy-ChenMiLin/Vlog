package com.tongji.auth.service.impl;

import com.tongji.auth.audit.LoginLogService;
import com.tongji.auth.model.IdentifierType;
import com.tongji.auth.model.LoginCommand;
import com.tongji.auth.model.LoginSuccess;
import com.tongji.auth.service.LoginChannelService;
import com.tongji.auth.util.IdentifierValidator;
import com.tongji.auth.verification.VerificationCheckResult;
import com.tongji.auth.verification.VerificationCodeStatus;
import com.tongji.auth.verification.VerificationScene;
import com.tongji.auth.verification.VerificationService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.user.domain.User;
import com.tongji.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

/**
 * 密码 / 验证码登录渠道实现（LoginChannelService 的实现类）。
 * <p>
 * 独门逻辑：校验账号格式 → 校验密码或验证码 → 返回用户。
 * 成功后签发令牌、记录成功日志等公共逻辑由 {@link com.tongji.auth.service.AuthService} 统一处理。
 */
@Service
@RequiredArgsConstructor
public class PasswordLoginChannelServiceImpl implements LoginChannelService {

    private final UserService userService;
    private final VerificationService verificationService;
    private final PasswordEncoder passwordEncoder;
    private final LoginLogService loginLogService;

    @Override
    public String getType() {
        return "password";
    }

    @Override
    public LoginSuccess authenticate(LoginCommand cmd) {
        validateIdentifier(cmd.identifierType(), cmd.identifier());
        String identifier = normalizeIdentifier(cmd.identifierType(), cmd.identifier());
        Optional<User> userOptional = findUserByIdentifier(cmd.identifierType(), identifier);
        if (userOptional.isEmpty()) {
            throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND);
        }
        User user = userOptional.get();
        String channel;
        if (StringUtils.hasText(cmd.password())) {
            channel = "PASSWORD";
            if (!StringUtils.hasText(user.getPasswordHash()) || !passwordEncoder.matches(cmd.password(), user.getPasswordHash())) {
                loginLogService.record(user.getId(), identifier, channel,
                        cmd.clientInfo().ip(), cmd.clientInfo().userAgent(), "FAILED");
                throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
            }
        } else if (StringUtils.hasText(cmd.verificationCode())) {
            channel = "CODE";
            ensureVerificationSuccess(verificationService.verify(VerificationScene.LOGIN, identifier, cmd.verificationCode()));
        } else {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请提供验证码或密码");
        }
        return new LoginSuccess(user, identifier, channel);
    }

    /**
     * 保证验证码校验成功，否则按状态抛出对应业务异常。
     */
    private void ensureVerificationSuccess(VerificationCheckResult result) {
        if (result.isSuccess()) {
            return;
        }
        VerificationCodeStatus status = result.status();
        if (status == VerificationCodeStatus.NOT_FOUND || status == VerificationCodeStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.VERIFICATION_NOT_FOUND);
        }
        if (status == VerificationCodeStatus.MISMATCH) {
            throw new BusinessException(ErrorCode.VERIFICATION_MISMATCH);
        }
        if (status == VerificationCodeStatus.TOO_MANY_ATTEMPTS) {
            throw new BusinessException(ErrorCode.VERIFICATION_TOO_MANY_ATTEMPTS);
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码校验失败");
    }

    private void validateIdentifier(IdentifierType type, String identifier) {
        if (type == IdentifierType.PHONE && !IdentifierValidator.isValidPhone(identifier)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手机号格式错误");
        }
        if (type == IdentifierType.EMAIL && !IdentifierValidator.isValidEmail(identifier)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱格式错误");
        }
    }

    private Optional<User> findUserByIdentifier(IdentifierType type, String identifier) {
        return switch (type) {
            case PHONE -> userService.findByPhone(identifier);
            case EMAIL -> userService.findByEmail(identifier);
        };
    }

    private String normalizeIdentifier(IdentifierType type, String identifier) {
        return switch (type) {
            case PHONE -> identifier.trim();
            case EMAIL -> identifier.trim().toLowerCase(Locale.ROOT);
        };
    }
}
