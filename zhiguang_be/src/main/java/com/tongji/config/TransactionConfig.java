package com.tongji.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 事务管理器配置。
 *
 * <p>接入 Neo4j（spring-boot-starter-data-neo4j）后，Spring Boot 自动配置会创建
 * reactiveTransactionManager，与 JDBC 的 transactionManager 共存于容器中。
 * 通过 @Primary 显式标记 JDBC 事务管理器为默认选择，确保：
 * <ul>
 *   <li>@Transactional 不指定 transactionManager 时使用 JDBC 事务管理器</li>
 *   <li>任何 @Autowired TransactionManager 直接注入时也能唯一解析</li>
 * </ul>
 * 此前的 TransactionManagementConfigurer 方案只对 @Transactional 注解生效，
 * 对 Spring 框架内部直接查找 TransactionManager bean 的路径无效，
 * 因此改用 @Primary 从 bean 解析层面彻底解决歧义。</p>
 */
@Configuration
public class TransactionConfig {

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
