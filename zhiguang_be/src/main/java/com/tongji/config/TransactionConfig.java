package com.tongji.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.TransactionManagementConfigurer;

/**
 * 明确指定 @Transactional 默认使用 MySQL/JDBC 事务管理器。
 *
 * <p>接入 Neo4j 后，Spring 容器里会同时存在 JDBC 的 transactionManager
 * 和 Neo4j 的 reactiveTransactionManager。若不指定默认值，MyBatis 业务方法上的
 * @Transactional 会因为无法二选一而在运行时报 NoUniqueBeanDefinitionException。</p>
 */
@Configuration
public class TransactionConfig implements TransactionManagementConfigurer {

    private final TransactionManager transactionManager;

    public TransactionConfig(@Qualifier("transactionManager") TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    @Override
    public TransactionManager annotationDrivenTransactionManager() {
        return transactionManager;
    }
}
