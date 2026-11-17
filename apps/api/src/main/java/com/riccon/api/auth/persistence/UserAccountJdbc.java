package com.riccon.api.auth.persistence;

import com.riccon.api.identity.domain.Credential;
import com.riccon.api.identity.domain.UserAccount;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.AuthSource;
import com.riccon.platform.domain.identity.UserStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 账号、凭据与角色权限的租户内查询。
 *
 * <p>所有 SQL 都通过 {@link TenantJdbc}；app_user 与 user_credential 的复合主键与 tenant_id 条件保证
 * 跨租户不可见。登录成功后在同一事务内清零失败计数并更新最近登录时间。
 */
@Component
public class UserAccountJdbc {

    /** 账号字段查询。 */
    private static final String USER_COLUMNS =
            "tenant_id, id, username, display_name, email, status, auth_source, last_login_at, created_at";

    /** 按租户与登录名查询账号。 */
    private static final String FIND_BY_USERNAME =
            "SELECT " + USER_COLUMNS + " FROM app_user WHERE tenant_id = :tenantId AND username = :username";

    /** 按租户与账号 ID 查询账号。 */
    private static final String FIND_BY_ID =
            "SELECT " + USER_COLUMNS + " FROM app_user WHERE tenant_id = :tenantId AND id = :userId";

    /** 查询密码凭据。 */
    private static final String FIND_CREDENTIAL =
            "SELECT password_hash, failed_attempts, locked_until FROM user_credential"
                    + " WHERE tenant_id = :tenantId AND user_id = :userId";

    /** 查询账号角色码。 */
    private static final String FIND_ROLES = """
            SELECT r.code
            FROM user_role ur
            JOIN role r ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
            WHERE ur.tenant_id = :tenantId AND ur.user_id = :userId
            ORDER BY r.code
            """;

    /** 查询账号权限码。 */
    private static final String FIND_PERMISSIONS = """
            SELECT DISTINCT rp.permission_code
            FROM user_role ur
            JOIN role_permission rp ON rp.tenant_id = ur.tenant_id AND rp.role_id = ur.role_id
            WHERE ur.tenant_id = :tenantId AND ur.user_id = :userId
            ORDER BY rp.permission_code
            """;

    /** 登录成功：清零失败计数与锁定。 */
    private static final String CLEAR_FAILURES = """
            UPDATE user_credential
            SET failed_attempts = 0, locked_until = NULL, updated_at = :now
            WHERE tenant_id = :tenantId AND user_id = :userId
            """;

    /** 登录成功：更新最近登录时间。 */
    private static final String UPDATE_LAST_LOGIN = """
            UPDATE app_user
            SET last_login_at = :now, updated_at = :now
            WHERE tenant_id = :tenantId AND id = :userId
            """;

    /** 登录失败：累计失败次数与锁定时间。 */
    private static final String RECORD_FAILURE = """
            UPDATE user_credential
            SET failed_attempts = :failedAttempts, locked_until = :lockedUntil, updated_at = :now
            WHERE tenant_id = :tenantId AND user_id = :userId
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建账号持久化组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public UserAccountJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 按登录名查询账号。
     *
     * @param tenantId 当前租户
     * @param username 登录名
     * @return 账号
     */
    public Optional<UserAccount> findByUsername(TenantId tenantId, String username) {
        return tenantJdbc.queryOne(tenantId, FIND_BY_USERNAME, Map.of("username", username), UserAccountJdbc::mapUser);
    }

    /**
     * 按账号 ID 查询账号。
     *
     * @param tenantId 当前租户
     * @param userId 账号 ID
     * @return 账号
     */
    public Optional<UserAccount> findById(TenantId tenantId, UUID userId) {
        return tenantJdbc.queryOne(
                tenantId, FIND_BY_ID, Map.of("userId", BinaryUuids.toBytes(userId)), UserAccountJdbc::mapUser);
    }

    /**
     * 查询密码凭据。
     *
     * @param tenantId 当前租户
     * @param userId 账号 ID
     * @return 凭据
     */
    public Optional<Credential> findCredential(TenantId tenantId, UUID userId) {
        return tenantJdbc.queryOne(
                tenantId,
                FIND_CREDENTIAL,
                Map.of("userId", BinaryUuids.toBytes(userId)),
                UserAccountJdbc::mapCredential);
    }

    /**
     * 查询账号角色码。
     *
     * @param tenantId 当前租户
     * @param userId 账号 ID
     * @return 角色码列表
     */
    public List<String> findRoles(TenantId tenantId, UUID userId) {
        return tenantJdbc.query(
                tenantId,
                FIND_ROLES,
                Map.of("userId", BinaryUuids.toBytes(userId)),
                (rs, rowNum) -> rs.getString("code"));
    }

    /**
     * 查询账号权限码。
     *
     * @param tenantId 当前租户
     * @param userId 账号 ID
     * @return 权限码列表
     */
    public List<String> findPermissions(TenantId tenantId, UUID userId) {
        return tenantJdbc.query(
                tenantId,
                FIND_PERMISSIONS,
                Map.of("userId", BinaryUuids.toBytes(userId)),
                (rs, rowNum) -> rs.getString("permission_code"));
    }

    /**
     * 登录成功：清零失败计数并更新最近登录时间。
     *
     * @param tenantId 当前租户
     * @param userId 账号 ID
     * @param now 当前时间
     */
    public void markLoginSucceeded(TenantId tenantId, UUID userId, Instant now) {
        Map<String, Object> params =
                Map.of("userId", BinaryUuids.toBytes(userId), "now", SqlTimestamps.toDatabase(now));
        tenantJdbc.update(tenantId, CLEAR_FAILURES, params);
        tenantJdbc.update(tenantId, UPDATE_LAST_LOGIN, params);
    }

    /**
     * 登录失败：写入失败次数与锁定截止时间。
     *
     * @param tenantId 当前租户
     * @param userId 账号 ID
     * @param failedAttempts 新的连续失败次数
     * @param lockedUntil 锁定截止时间；为空表示未锁定
     * @param now 当前时间
     */
    public void recordLoginFailure(
            TenantId tenantId, UUID userId, int failedAttempts, Instant lockedUntil, Instant now) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("userId", BinaryUuids.toBytes(userId));
        params.put("failedAttempts", failedAttempts);
        params.put("lockedUntil", SqlTimestamps.toDatabase(lockedUntil));
        params.put("now", SqlTimestamps.toDatabase(now));
        tenantJdbc.update(tenantId, RECORD_FAILURE, params);
    }

    /**
     * 映射账号行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 账号
     * @throws java.sql.SQLException 读取失败
     */
    private static UserAccount mapUser(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        LocalDateTime lastLoginAt = rs.getObject("last_login_at", LocalDateTime.class);
        return new UserAccount(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                rs.getString("tenant_id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("email"),
                UserStatus.valueOf(rs.getString("status")),
                AuthSource.valueOf(rs.getString("auth_source")),
                SqlTimestamps.toInstant(lastLoginAt),
                SqlTimestamps.toInstant(rs.getObject("created_at", LocalDateTime.class)));
    }

    /**
     * 映射凭据行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 凭据
     * @throws java.sql.SQLException 读取失败
     */
    private static Credential mapCredential(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        LocalDateTime lockedUntil = rs.getObject("locked_until", LocalDateTime.class);
        return new Credential(
                rs.getString("password_hash"), rs.getInt("failed_attempts"), SqlTimestamps.toInstant(lockedUntil));
    }
}
