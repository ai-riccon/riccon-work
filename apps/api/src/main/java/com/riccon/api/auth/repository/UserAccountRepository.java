package com.riccon.api.auth.repository;

import com.riccon.api.auth.persistence.UserAccountJdbc;
import com.riccon.api.identity.domain.Credential;
import com.riccon.api.identity.domain.UserAccount;
import com.riccon.platform.common.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 账号仓储。
 *
 * <p>只暴露认证与授权需要的最小查询；不提供删除账号能力（M02 只禁用）。
 */
@Repository
public class UserAccountRepository {

    /** 账号持久化组件。 */
    private final UserAccountJdbc userAccountJdbc;

    /**
     * 创建账号仓储。
     *
     * @param userAccountJdbc 账号持久化组件
     */
    public UserAccountRepository(UserAccountJdbc userAccountJdbc) {
        this.userAccountJdbc = Objects.requireNonNull(userAccountJdbc, "userAccountJdbc must not be null");
    }

    /**
     * 按登录名查询账号。
     *
     * @param tenantId 租户
     * @param username 登录名
     * @return 账号
     */
    public Optional<UserAccount> findByUsername(TenantId tenantId, String username) {
        return userAccountJdbc.findByUsername(tenantId, username);
    }

    /**
     * 按账号 ID 查询账号。
     *
     * @param tenantId 租户
     * @param userId 账号 ID
     * @return 账号
     */
    public Optional<UserAccount> findById(TenantId tenantId, UUID userId) {
        return userAccountJdbc.findById(tenantId, userId);
    }

    /**
     * 查询密码凭据。
     *
     * @param tenantId 租户
     * @param userId 账号 ID
     * @return 凭据
     */
    public Optional<Credential> findCredential(TenantId tenantId, UUID userId) {
        return userAccountJdbc.findCredential(tenantId, userId);
    }

    /**
     * 查询角色码。
     *
     * @param tenantId 租户
     * @param userId 账号 ID
     * @return 角色码列表
     */
    public List<String> findRoles(TenantId tenantId, UUID userId) {
        return userAccountJdbc.findRoles(tenantId, userId);
    }

    /**
     * 查询权限码。
     *
     * @param tenantId 租户
     * @param userId 账号 ID
     * @return 权限码列表
     */
    public List<String> findPermissions(TenantId tenantId, UUID userId) {
        return userAccountJdbc.findPermissions(tenantId, userId);
    }

    /**
     * 登录成功：清零失败计数并更新最近登录时间。
     *
     * @param tenantId 租户
     * @param userId 账号 ID
     * @param now 当前时间
     */
    public void markLoginSucceeded(TenantId tenantId, UUID userId, Instant now) {
        userAccountJdbc.markLoginSucceeded(tenantId, userId, now);
    }

    /**
     * 登录失败：写入失败次数与锁定截止时间。
     *
     * @param tenantId 租户
     * @param userId 账号 ID
     * @param failedAttempts 连续失败次数
     * @param lockedUntil 锁定截止时间
     * @param now 当前时间
     */
    public void recordLoginFailure(
            TenantId tenantId, UUID userId, int failedAttempts, Instant lockedUntil, Instant now) {
        userAccountJdbc.recordLoginFailure(tenantId, userId, failedAttempts, lockedUntil, now);
    }
}
