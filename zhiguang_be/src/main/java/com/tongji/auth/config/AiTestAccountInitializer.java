package com.tongji.auth.config;

import com.tongji.auth.util.IdentifierValidator;
import com.tongji.user.domain.User;
import com.tongji.user.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Component
@Profile("ai-test")
public class AiTestAccountInitializer implements ApplicationRunner {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String phone;
    private final String password;

    public AiTestAccountInitializer(
            UserService userService,
            PasswordEncoder passwordEncoder,
            @Value("${auth.ai-test-account.email:}") String email,
            @Value("${auth.ai-test-account.phone:}") String phone,
            @Value("${auth.ai-test-account.password:${AI_TEST_ACCOUNT_PASSWORD:}}") String password
    ) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.email = email.trim().toLowerCase(Locale.ROOT);
        this.phone = phone.isBlank() ? null : phone.trim();
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!IdentifierValidator.isValidEmail(email)) {
            throw new IllegalStateException("auth.ai-test-account.email must be a valid email address");
        }
        if (phone != null && !IdentifierValidator.isValidPhone(phone)) {
            throw new IllegalStateException("auth.ai-test-account.phone must be a valid phone number when configured");
        }
        if (!password.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException("auth.ai-test-account.password must be a 32-byte random secret encoded as hex");
        }

        Optional<User> existing = userService.findByEmail(email);
        if (existing.isPresent()) {
            verifyExistingAccount(existing.get());
            return;
        }

        try {
            userService.createUser(User.builder()
                    .email(email)
                    .phone(phone)
                    .passwordHash(passwordEncoder.encode(password))
                    .nickname("AI 测试账号")
                    .tagsJson("[]")
                    .build());
        } catch (DuplicateKeyException conflict) {
            // Another instance may have created the fixture after the lookup.
            verifyExistingAccount(userService.findByEmail(email).orElseThrow(() -> conflict));
        }
    }

    private void verifyExistingAccount(User user) {
        if (!Objects.equals(phone, user.getPhone()) || user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalStateException("AI test account already exists with different credentials in this database");
        }
    }
}
