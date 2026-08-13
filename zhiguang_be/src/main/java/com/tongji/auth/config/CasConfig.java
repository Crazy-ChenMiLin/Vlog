package com.tongji.auth.config;

import org.apereo.cas.client.validation.Cas30ServiceTicketValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAS 单点登录配置。
 * <p>
 * 只注册 {@link Cas30ServiceTicketValidator}（验票器），不引入 Spring Security 的
 * CasAuthenticationFilter/Provider/Manager 那套——因为知光是 JWT 无状态架构，
 * 验完票后直接发 JWT，不需要建 Session/SecurityContext。
 * <p>
 * 参考实现：D:\javaee\CQUT_TimeTable_Backend 的 SecurityConfiguration，
 * 但只借其 cas30ServiceTicketValidator() 一层。
 */
@Configuration
public class CasConfig {

    @Value("${cas.server-url}")
    private String casServerUrl;

    /**
     * CAS 3.0 票据验证器：拿 ticket 去学校 CAS 服务器验证，返回用户身份。
     * <p>
     * 这是 CAS 委托链最底层"真正打电话"的组件，知光只借这一层。
     *
     * @return 配置好学校 CAS 服务器地址的验票器。
     */
    @Bean
    public Cas30ServiceTicketValidator casTicketValidator() {
        Cas30ServiceTicketValidator validator = new Cas30ServiceTicketValidator(casServerUrl);
        validator.setEncoding("UTF-8"); // 解决 CAS 返回中文信息乱码
        return validator;
    }
}
