package com.tongji.user.service;

import com.tongji.user.domain.User;
import java.util.Optional;

/**
 * 用户服务接口。
 */
public interface UserService {

    Optional<User> findByPhone(String phone);

    Optional<User> findByEmail(String email);

    Optional<User> findById(long id);

    boolean existsByPhone(String phone);

    boolean existsByEmail(String email);

    User createUser(User user);

    /** 根据 CAS 学号查询用户 */
    Optional<User> findByCasId(String casId);

    /** 根据 CAS 学号查用户，不存在则自动创建（首次 CAS 登录） */
    User findOrCreateByCasId(String casId);

    void updatePassword(User user);
}