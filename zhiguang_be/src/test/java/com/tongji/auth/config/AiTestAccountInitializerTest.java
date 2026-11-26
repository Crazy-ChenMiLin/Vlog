package com.tongji.auth.config;

import com.tongji.auth.audit.LoginLogService;
import com.tongji.auth.authservice.impl.PasswordLoginChannelServiceImpl;
import com.tongji.auth.enums.IdentifierType;
import com.tongji.auth.pojo.cmd.LoginCommand;
import com.tongji.auth.pojo.dto.ClientInfo;
import com.tongji.auth.verification.VerificationService;
import com.tongji.user.domain.User;
import com.tongji.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTestAccountInitializerTest {

    private static final String EMAIL = "test-user@example.test";
    private static final String PHONE = "10000000001";
    private static final String TEST_PASSWORD = "ab12".repeat(16);

    private final UserService users = mock(UserService.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AiTestAccountInitializer.class)
            .withBean(UserService.class, () -> users)
            .withBean(PasswordEncoder.class, () -> encoder);

    @Test
    void ignoresTestAccountConfigurationWithoutTheTestProfile() {
        contextRunner.withPropertyValues("auth.ai-test-account.email=" + EMAIL,
                        "auth.ai-test-account.password=" + TEST_PASSWORD)
                .run(context -> assertThat(context).doesNotHaveBean(AiTestAccountInitializer.class));
    }

    @Test
    void provisionsAnAccountThatCanUseTheExistingPasswordChannel() {
        contextRunner.withPropertyValues("spring.profiles.active=ai-test",
                        "auth.ai-test-account.email=Test-User@example.test",
                        "auth.ai-test-account.phone=" + PHONE,
                        "auth.ai-test-account.password=" + TEST_PASSWORD)
                .run(context -> {
                    context.getBean(AiTestAccountInitializer.class).run(null);
                    ArgumentCaptor<User> account = ArgumentCaptor.forClass(User.class);
                    verify(users).createUser(account.capture());
                    User user = account.getValue();
                    assertThat(user.getEmail()).isEqualTo(EMAIL);
                    assertThat(user.getPhone()).isEqualTo(PHONE);
                    assertThat(user.getPasswordHash()).isNotEqualTo(TEST_PASSWORD);
                    when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));

                    PasswordLoginChannelServiceImpl channel = new PasswordLoginChannelServiceImpl(
                            users, mock(VerificationService.class), encoder, mock(LoginLogService.class));
                    LoginCommand login = new LoginCommand("password", IdentifierType.EMAIL, EMAIL, TEST_PASSWORD,
                            null, null, null, new ClientInfo("127.0.0.1", "test"));

                    assertThat(channel.authenticate(login).channel()).isEqualTo("PASSWORD");
                });
    }

    @Test
    void provisionsAnEmailOnlyAccountWhenPhoneIsOmitted() {
        contextRunner.withPropertyValues("spring.profiles.active=ai-test", "auth.ai-test-account.email=" + EMAIL,
                        "auth.ai-test-account.password=" + TEST_PASSWORD)
                .run(context -> {
                    context.getBean(AiTestAccountInitializer.class).run(null);
                    ArgumentCaptor<User> account = ArgumentCaptor.forClass(User.class);
                    verify(users).createUser(account.capture());
                    assertThat(account.getValue().getPhone()).isNull();
                });
    }

    @Test
    void preservesExistingAccountWithDifferentCredentials() {
        User existing = User.builder().phone(PHONE).passwordHash(encoder.encode("other-password")).build();
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> new AiTestAccountInitializer(users, encoder, EMAIL, PHONE, TEST_PASSWORD).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different credentials");
        verify(users, never()).createUser(any());
        verify(users, never()).updatePassword(any());
    }

    @Test
    void acceptsTheSameFixtureCreatedByAConcurrentStartup() {
        User existing = User.builder().phone(PHONE).passwordHash(encoder.encode(TEST_PASSWORD)).build();
        when(users.findByEmail(EMAIL)).thenReturn(Optional.empty()).thenReturn(Optional.of(existing));
        when(users.createUser(any(User.class))).thenThrow(new DuplicateKeyException("fixture exists"));

        new AiTestAccountInitializer(users, encoder, EMAIL, PHONE, TEST_PASSWORD).run(null);
    }
}
