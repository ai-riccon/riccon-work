package com.riccon.api.admin.identity.persistence;

import com.riccon.api.admin.identity.OidcAccountIdentity;
import com.riccon.api.admin.persistence.PlatformJdbc;
import com.riccon.api.identity.domain.UserAccount;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.AuthSource;
import com.riccon.platform.domain.identity.UserStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * OIDC 账号开通、绑定与解绑 SQL。
 *
 * <p>位于 {@code ..admin..} 包内，允许使用 {@link PlatformJdbc} 跨租户管理目标租户账号（租户管理员与平台
 * 管理员共用同一套语义）。所有语句都显式带 {@code tenant_id = :tenantId}，并利用 V2 的
 * {@code chk_app_user_external} 与 {@code uk_app_user_external_identity} 保证身份键一致性与唯一性。
 *
 * <p>这里不读写密码明文；凭据只以调用方传入的哈希写入，删除与重建都在服务层同一事务内完成。
 */
@Component
public class OidcBindingJdbc {

    /** 账号字段；与 {@code AdminJdbc} 保持一致，避免管理端出现两套字段集合。 */
    private static final String USER_COLUMNS =
            "tenant_id, id, username, display_name, email, status, auth_source, last_login_at, created_at";

    /** 按租户 + issuer + subject 查询已绑定账号。 */
    private static final String FIND_BOUND_USER = "SELECT " + USER_COLUMNS + " FROM app_user"
            + " WHERE tenant_id = :tenantId AND auth_source = 'OIDC'"
            + " AND external_issuer = :issuer AND external_subject = :subject";

    /** 查询账号当前认证来源与外部身份。 */
    private static final String FIND_IDENTITY = "SELECT auth_source, external_issuer, external_subject FROM app_user"
            + " WHERE tenant_id = :tenantId AND id = :userId";

    /** 直接开通 OIDC 账号：不写 user_credential，外部身份与 auth_source 同时写入。 */
    private static final String INSERT_OIDC_USER = """
            INSERT INTO app_user (
                tenant_id, id, username, display_name, email, status, auth_source, external_issuer, external_subject
            ) VALUES (
                :tenantId, :id, :username, :displayName, :email, :status, 'OIDC', :issuer, :subject
            )
            """;

    /** 把 LOCAL 账号转为 OIDC；只允许从 LOCAL 转换，避免覆盖并发写入的其他身份。 */
    private static final String BIND = """
            UPDATE app_user
            SET auth_source = 'OIDC', external_issuer = :issuer, external_subject = :subject, updated_at = :now
            WHERE tenant_id = :tenantId AND id = :userId AND auth_source = 'LOCAL'
            """;

    /** 删除本地密码凭据：OIDC 账号只能通过 IdP 登录（单认证来源）。 */
    private static final String DELETE_CREDENTIAL =
            "DELETE FROM user_credential WHERE tenant_id = :tenantId AND user_id = :userId";

    /** 解除 OIDC 绑定：恢复 LOCAL 并清空外部身份。 */
    private static final String UNBIND = """
            UPDATE app_user
            SET auth_source = 'LOCAL', external_issuer = NULL, external_subject = NULL, updated_at = :now
            WHERE tenant_id = :tenantId AND id = :userId AND auth_source = 'OIDC'
            """;

    /** 写入新密码凭据；解绑时必须重建，重复执行则覆盖并清除失败计数与锁定。 */
    private static final String UPSERT_CREDENTIAL = """
            INSERT INTO user_credential (
                tenant_id, user_id, password_hash, algorithm, failed_attempts, locked_until, password_updated_at
            ) VALUES (
                :tenantId, :userId, :passwordHash, 'bcrypt', 0, NULL, :now
            )
            ON DUPLICATE KEY UPDATE
                password_hash = :passwordHash,
                algorithm = 'bcrypt',
                failed_attempts = 0,
                locked_until = NULL,
                password_updated_at = :now,
                updated_at = :now
            """;

    /** 平台管理数据访问入口。 */
    private final PlatformJdbc platformJdbc;

    /**
     * 创建 OIDC 绑定 SQL 组件。
     *
     * @param platformJdbc 平台管理数据访问入口
     */
    public OidcBindingJdbc(PlatformJdbc platformJdbc) {
        this.platformJdbc = Objects.requireNonNull(platformJdbc, "platformJdbc must not be null");
    }

    /**
     * 查询指定 OIDC 身份已绑定的账号。
     *
     * @param tenantId 目标租户
     * @param issuer 规范化 issuer
     * @param subject IdP subject
     * @return 已绑定账号
     */
    public Optional<UserAccount> findBoundUser(TenantId tenantId, String issuer, String subject) {
        return platformJdbc.queryOneTenant(
                tenantId, FIND_BOUND_USER, Map.of("issuer", issuer, "subject", subject), OidcBindingJdbc::mapUser);
    }

    /**
     * 查询账号当前认证来源与外部身份。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @return 身份快照
     */
    public Optional<OidcAccountIdentity> findIdentity(TenantId tenantId, UUID userId) {
        return platformJdbc.queryOneTenant(
                tenantId,
                FIND_IDENTITY,
                Map.of("userId", BinaryUuids.toBytes(userId)),
                (rs, rowNum) -> new OidcAccountIdentity(
                        AuthSource.valueOf(rs.getString("auth_source")),
                        rs.getString("external_issuer"),
                        rs.getString("external_subject")));
    }

    /**
     * 直接创建 OIDC 账号，不写本地凭据。
     *
     * @param tenantId 目标租户
     * @param user 账号（authSource 必须为 OIDC）
     * @param issuer 规范化 issuer
     * @param subject IdP subject
     * @return 受影响行数
     */
    public int insertOidcUser(TenantId tenantId, UserAccount user, String issuer, String subject) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(user.id()));
        params.put("username", user.username());
        params.put("displayName", user.displayName());
        params.put("email", user.email());
        params.put("status", user.status().name());
        params.put("issuer", issuer);
        params.put("subject", subject);
        return platformJdbc.insertTenant(tenantId, INSERT_OIDC_USER, params);
    }

    /**
     * 把 LOCAL 账号更新为 OIDC 账号。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @param issuer 规范化 issuer
     * @param subject IdP subject
     * @param now 更新时间
     * @return 受影响行数；0 表示账号已不是 LOCAL 或不存在
     */
    public int bind(TenantId tenantId, UUID userId, String issuer, String subject, Instant now) {
        return platformJdbc.updateTenant(
                tenantId,
                BIND,
                Map.of(
                        "userId",
                        BinaryUuids.toBytes(userId),
                        "issuer",
                        issuer,
                        "subject",
                        subject,
                        "now",
                        SqlTimestamps.toDatabase(now)));
    }

    /**
     * 删除账号的本地密码凭据。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @return 受影响行数
     */
    public int deleteCredential(TenantId tenantId, UUID userId) {
        return platformJdbc.updateTenant(tenantId, DELETE_CREDENTIAL, Map.of("userId", BinaryUuids.toBytes(userId)));
    }

    /**
     * 把 OIDC 账号恢复为 LOCAL。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @param now 更新时间
     * @return 受影响行数；0 表示账号已不是 OIDC 或不存在
     */
    public int unbind(TenantId tenantId, UUID userId, Instant now) {
        return platformJdbc.updateTenant(
                tenantId, UNBIND, Map.of("userId", BinaryUuids.toBytes(userId), "now", SqlTimestamps.toDatabase(now)));
    }

    /**
     * 写入或覆盖本地密码凭据，并清除失败计数与锁定。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @param passwordHash 带算法前缀的密码哈希
     * @param now 更新时间
     * @return 受影响行数
     */
    public int upsertCredential(TenantId tenantId, UUID userId, String passwordHash, Instant now) {
        return platformJdbc.insertTenant(
                tenantId,
                UPSERT_CREDENTIAL,
                Map.of(
                        "userId", BinaryUuids.toBytes(userId),
                        "passwordHash", passwordHash,
                        "now", SqlTimestamps.toDatabase(now)));
    }

    /**
     * 映射账号行。
     *
     * <p>与 {@code AdminJdbc.mapUser} 字段一致；因两者位于不同持久化组件，保留独立映射避免公开内部方法。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 账号
     * @throws SQLException 读取失败
     */
    private static UserAccount mapUser(ResultSet rs, int rowNum) throws SQLException {
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
}
