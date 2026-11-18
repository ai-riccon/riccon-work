package com.riccon.api.oidc.persistence;

import com.riccon.api.identity.domain.UserAccount;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.AuthSource;
import com.riccon.platform.domain.identity.UserStatus;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * OIDC 身份到账号的只读查询。
 *
 * <p>身份键固定 {@code (tenant_id, external_issuer, external_subject)}，由 V2 唯一键
 * {@code uk_app_user_external_identity} 保证；不读取、不比较邮箱（ADR-0019 第 3 节）。所有 SQL 都经过
 * {@link TenantJdbc}，缺少租户条件会立即失败。
 */
@Component
public class OidcIdentityJdbc {

    /** 账号字段；与 {@code UserAccountJdbc} 的公开字段保持一致。 */
    private static final String USER_COLUMNS =
            "tenant_id, id, username, display_name, email, status, auth_source, last_login_at, created_at";

    /** 按租户 + issuer + subject 查询已绑定账号；只匹配 OIDC 来源。 */
    private static final String FIND_BOUND_USER = "SELECT " + USER_COLUMNS + " FROM app_user"
            + " WHERE tenant_id = :tenantId AND auth_source = 'OIDC'"
            + " AND external_issuer = :issuer AND external_subject = :subject";

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建 OIDC 身份查询组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public OidcIdentityJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 查询已绑定到指定 OIDC 身份的账号。
     *
     * @param tenantId 当前租户
     * @param issuer 规范化 issuer
     * @param subject IdP 的 subject
     * @return 账号
     */
    public Optional<UserAccount> findBoundUser(TenantId tenantId, String issuer, String subject) {
        return tenantJdbc.queryOne(
                tenantId, FIND_BOUND_USER, Map.of("issuer", issuer, "subject", subject), OidcIdentityJdbc::mapUser);
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
}
