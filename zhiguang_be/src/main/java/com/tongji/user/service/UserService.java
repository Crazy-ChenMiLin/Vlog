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

    /** 根据 GitHub 用户 ID 查询用户 */
    Optional<User> findByGithubId(String githubId);

    /** 根据 GitHub 用户 ID 查用户，不存在则自动创建（首次 GitHub 登录） */
    User findOrCreateByGithubId(String githubId);

    void updateProfile(User user);

    void updatePassword(User user);
}
