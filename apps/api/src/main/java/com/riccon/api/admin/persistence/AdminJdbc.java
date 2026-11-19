package com.riccon.api.admin.persistence;

import com.riccon.api.identity.domain.TenantRecord;
import com.riccon.api.identity.domain.TenantStatus;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 平台管理 SQL。
 *
 * <p>只被 {@code ..admin..} 包使用：租户表是全局表，租户内账号与角色通过 {@link PlatformJdbc} 的目标租户
 * 方法访问，SQL 必须显式带 {@code tenant_id = :tenantId}。普通业务代码禁止依赖本类。
 */
@Component
public class AdminJdbc {

    /** 租户字段。 */
    private static final String TENANT_COLUMNS = "id, name, status, plan, retention_days, created_at";

    /** 账号字段。 */
    private static final String USER_COLUMNS =
            "tenant_id, id, username, display_name, email, status, auth_source, external_issuer,"
                    + " last_login_at, created_at";

    /** 查询租户。 */
    private static final String FIND_TENANT = "SELECT " + TENANT_COLUMNS + " FROM tenant WHERE id = :id";

    /** 租户列表。 */
    private static final String LIST_TENANTS =
            "SELECT " + TENANT_COLUMNS + " FROM tenant ORDER BY created_at ASC, id ASC LIMIT :limit OFFSET :offset";

    /** 租户总数。 */
    private static final String COUNT_TENANTS = "SELECT COUNT(*) FROM tenant";

    /** 插入租户。 */
    private static final String INSERT_TENANT = """
            INSERT INTO tenant (id, name, status, plan, retention_days)
            VALUES (:id, :name, :status, :plan, :retentionDays)
            """;

    /** 更新租户状态与保留策略。 */
    private static final String UPDATE_TENANT = """
            UPDATE tenant
            SET status = COALESCE(:status, status),
                retention_days = COALESCE(:retentionDays, retention_days),
                updated_at = :now
            WHERE id = :id
            """;

    /** 按角色码查询角色 ID。 */
    private static final String FIND_ROLE_ID = "SELECT id FROM role WHERE tenant_id = :tenantId AND code = :code";

    /** 插入角色。 */
    private static final String INSERT_ROLE = """
            INSERT INTO role (tenant_id, id, code, name, description, is_system)
            VALUES (:tenantId, :id, :code, :name, :description, TRUE)
            """;

    /** 插入角色权限。 */
    private static final String INSERT_ROLE_PERMISSION = """
            INSERT INTO role_permission (tenant_id, role_id, permission_code)
            VALUES (:tenantId, :roleId, :permissionCode)
            """;

    /** 按账号 ID 查询。 */
    private static final String FIND_USER =
            "SELECT " + USER_COLUMNS + " FROM app_user WHERE tenant_id = :tenantId AND id = :userId";

    /** 按登录名查询。 */
    private static final String FIND_USER_BY_USERNAME =
            "SELECT " + USER_COLUMNS + " FROM app_user WHERE tenant_id = :tenantId AND username = :username";

    /** 账号列表。 */
    private static final String LIST_USERS = "SELECT " + USER_COLUMNS
            + " FROM app_user WHERE tenant_id = :tenantId ORDER BY created_at ASC, id ASC LIMIT :limit OFFSET :offset";

    /** 账号总数。 */
    private static final String COUNT_USERS = "SELECT COUNT(*) FROM app_user WHERE tenant_id = :tenantId";

    /** 批量查询账号角色。 */
    private static final String LIST_ROLES_FOR_USERS = """
            SELECT ur.user_id, r.code
            FROM user_role ur
            JOIN role r ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
            WHERE ur.tenant_id = :tenantId AND ur.user_id IN (:userIds)
            ORDER BY r.code
            """;

    /** 查询单个账号角色。 */
    private static final String LIST_ROLES_FOR_USER = """
            SELECT r.code
            FROM user_role ur
            JOIN role r ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
            WHERE ur.tenant_id = :tenantId AND ur.user_id = :userId
            ORDER BY r.code
            """;

    /** 插入账号。 */
    private static final String INSERT_USER = """
            INSERT INTO app_user (tenant_id, id, username, display_name, email, status, auth_source)
            VALUES (:tenantId, :id, :username, :displayName, :email, :status, :authSource)
            """;

    /** 插入密码凭据。 */
    private static final String INSERT_CREDENTIAL = """
            INSERT INTO user_credential (tenant_id, user_id, password_hash, algorithm)
            VALUES (:tenantId, :userId, :passwordHash, :algorithm)
            """;

    /** 删除账号全部角色。 */
    private static final String DELETE_USER_ROLES =
            "DELETE FROM user_role WHERE tenant_id = :tenantId AND user_id = :userId";

    /** 插入账号角色。 */
    private static final String INSERT_USER_ROLE = """
            INSERT INTO user_role (tenant_id, user_id, role_id, granted_by)
            VALUES (:tenantId, :userId, :roleId, :grantedBy)
            """;

    /** 更新账号状态。 */
    private static final String UPDATE_USER_STATUS = """
            UPDATE app_user SET status = :status, updated_at = :now
            WHERE tenant_id = :tenantId AND id = :userId
            """;

    /** 统计拥有指定角色的账号数。 */
    private static final String COUNT_USERS_WITH_ROLE = """
            SELECT COUNT(*)
            FROM user_role ur
            JOIN role r ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
            WHERE ur.tenant_id = :tenantId AND r.code = :roleCode
            """;

    /** 平台管理 JDBC 入口。 */
    private final PlatformJdbc platformJdbc;

    /**
     * 创建平台管理 SQL 组件。
     *
     * @param platformJdbc 平台管理 JDBC 入口
     */
    public AdminJdbc(PlatformJdbc platformJdbc) {
        this.platformJdbc = Objects.requireNonNull(platformJdbc, "platformJdbc must not be null");
    }

    /**
     * 查询租户元数据。
     *
     * @param tenantId 租户代码
     * @return 租户元数据
     */
    public Optional<TenantRecord> findTenant(String tenantId) {
        return platformJdbc.queryOneGlobal(FIND_TENANT, Map.of("id", tenantId), AdminJdbc::mapTenant);
    }

    /**
     * 分页列出租户。
     *
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 租户列表
     */
    public List<TenantRecord> listTenants(int limit, int offset) {
        return platformJdbc.queryGlobal(LIST_TENANTS, Map.of("limit", limit, "offset", offset), AdminJdbc::mapTenant);
    }

    /**
     * 统计租户总数。
     *
     * @return 租户数
     */
    public long countTenants() {
        return platformJdbc.countGlobal(COUNT_TENANTS, Map.of());
    }

    /**
     * 插入租户。
     *
     * @param tenant 租户元数据
     * @return 受影响行数
     */
    public int insertTenant(TenantRecord tenant) {
        return platformJdbc.insertGlobal(
                INSERT_TENANT,
                Map.of(
                        "id", tenant.id(),
                        "name", tenant.name(),
                        "status", tenant.status().name(),
                        "plan", tenant.plan(),
                        "retentionDays", tenant.retentionDays()));
    }

    /**
     * 更新租户状态或保留策略。
     *
     * @param tenantId 租户代码
     * @param status 目标状态；为空则不更新
     * @param retentionDays 目标保留天数；为空则不更新
     * @param now 更新时间
     * @return 受影响行数
     */
    public int updateTenant(String tenantId, TenantStatus status, Integer retentionDays, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", tenantId);
        params.put("status", status == null ? null : status.name());
        params.put("retentionDays", retentionDays);
        params.put("now", SqlTimestamps.toDatabase(now));
        return platformJdbc.updateGlobal(UPDATE_TENANT, params);
    }

    /**
     * 按角色码查询角色 ID。
     *
     * @param tenantId 目标租户
     * @param code 角色码
     * @return 角色 ID
     */
    public Optional<UUID> findRoleId(TenantId tenantId, String code) {
        return platformJdbc.queryOneTenant(
                tenantId, FIND_ROLE_ID, Map.of("code", code), (rs, rowNum) -> BinaryUuids.fromBytes(rs.getBytes("id")));
    }

    /**
     * 插入系统角色。
     *
     * @param tenantId 目标租户
     * @param roleId 角色 ID
     * @param code 角色码
     * @param name 展示名
     * @param description 说明
     * @return 受影响行数
     */
    public int insertRole(TenantId tenantId, UUID roleId, String code, String name, String description) {
        return platformJdbc.insertTenant(
                tenantId,
                INSERT_ROLE,
                Map.of(
                        "id", BinaryUuids.toBytes(roleId),
                        "code", code,
                        "name", name,
                        "description", description));
    }

    /**
     * 插入角色权限映射。
     *
     * @param tenantId 目标租户
     * @param roleId 角色 ID
     * @param permissionCode 权限码
     * @return 受影响行数
     */
    public int insertRolePermission(TenantId tenantId, UUID roleId, String permissionCode) {
        return platformJdbc.insertTenant(
                tenantId,
                INSERT_ROLE_PERMISSION,
                Map.of("roleId", BinaryUuids.toBytes(roleId), "permissionCode", permissionCode));
    }

    /**
     * 按账号 ID 查询账号。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @return 账号
     */
    public Optional<UserAccount> findUser(TenantId tenantId, UUID userId) {
        return platformJdbc.queryOneTenant(
                tenantId, FIND_USER, Map.of("userId", BinaryUuids.toBytes(userId)), AdminJdbc::mapUser);
    }

    /**
     * 按登录名查询账号。
     *
     * @param tenantId 目标租户
     * @param username 登录名
     * @return 账号
     */
    public Optional<UserAccount> findUserByUsername(TenantId tenantId, String username) {
        return platformJdbc.queryOneTenant(
                tenantId, FIND_USER_BY_USERNAME, Map.of("username", username), AdminJdbc::mapUser);
    }

    /**
     * 分页列出账号。
     *
     * @param tenantId 目标租户
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 账号列表
     */
    public List<UserAccount> listUsers(TenantId tenantId, int limit, int offset) {
        return platformJdbc.queryTenant(
                tenantId, LIST_USERS, Map.of("limit", limit, "offset", offset), AdminJdbc::mapUser);
    }

    /**
     * 统计账号总数。
     *
     * @param tenantId 目标租户
     * @return 账号数
     */
    public long countUsers(TenantId tenantId) {
        return platformJdbc.countTenant(tenantId, COUNT_USERS, Map.of());
    }

    /**
     * 批量查询账号角色。
     *
     * @param tenantId 目标租户
     * @param userIds 账号 ID 列表；为空时返回空映射
     * @return 账号 ID 到角色码列表的映射
     */
    public Map<UUID, List<String>> rolesForUsers(TenantId tenantId, List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<byte[]> binaryIds = userIds.stream().map(BinaryUuids::toBytes).toList();
        List<UserRoleRow> rows = platformJdbc.queryTenant(
                tenantId,
                LIST_ROLES_FOR_USERS,
                Map.of("userIds", binaryIds),
                (rs, rowNum) -> new UserRoleRow(BinaryUuids.fromBytes(rs.getBytes("user_id")), rs.getString("code")));
        Map<UUID, List<String>> roles = new HashMap<>();
        for (UserRoleRow row : rows) {
            roles.computeIfAbsent(row.userId(), ignored -> new ArrayList<>()).add(row.code());
        }
        return roles;
    }

    /**
     * 查询单个账号角色。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @return 角色码列表
     */
    public List<String> rolesForUser(TenantId tenantId, UUID userId) {
        return platformJdbc.queryTenant(
                tenantId,
                LIST_ROLES_FOR_USER,
                Map.of("userId", BinaryUuids.toBytes(userId)),
                (rs, rowNum) -> rs.getString("code"));
    }

    /**
     * 插入账号。
     *
     * @param tenantId 目标租户
     * @param user 账号
     * @return 受影响行数
     */
    public int insertUser(TenantId tenantId, UserAccount user) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(user.id()));
        params.put("username", user.username());
        params.put("displayName", user.displayName());
        params.put("email", user.email());
        params.put("status", user.status().name());
        params.put("authSource", user.authSource().name());
        return platformJdbc.insertTenant(tenantId, INSERT_USER, params);
    }

    /**
     * 插入密码凭据。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @param passwordHash 带算法前缀的哈希
     * @return 受影响行数
     */
    public int insertCredential(TenantId tenantId, UUID userId, String passwordHash) {
        return platformJdbc.insertTenant(
                tenantId,
                INSERT_CREDENTIAL,
                Map.of("userId", BinaryUuids.toBytes(userId), "passwordHash", passwordHash, "algorithm", "bcrypt"));
    }

    /**
     * 替换账号角色。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @param roleIds 角色 ID 列表
     * @param grantedBy 授予人账号 ID；系统授予为空
     * @return 插入的角色数
     */
    public int replaceUserRoles(TenantId tenantId, UUID userId, List<UUID> roleIds, UUID grantedBy) {
        platformJdbc.updateTenant(tenantId, DELETE_USER_ROLES, Map.of("userId", BinaryUuids.toBytes(userId)));
        int inserted = 0;
        for (UUID roleId : roleIds) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("userId", BinaryUuids.toBytes(userId));
            params.put("roleId", BinaryUuids.toBytes(roleId));
            params.put("grantedBy", grantedBy == null ? null : BinaryUuids.toBytes(grantedBy));
            inserted += platformJdbc.insertTenant(tenantId, INSERT_USER_ROLE, params);
        }
        return inserted;
    }

    /**
     * 更新账号状态。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @param status 目标状态
     * @param now 更新时间
     * @return 受影响行数
     */
    public int updateUserStatus(TenantId tenantId, UUID userId, UserStatus status, Instant now) {
        return platformJdbc.updateTenant(
                tenantId,
                UPDATE_USER_STATUS,
                Map.of(
                        "userId", BinaryUuids.toBytes(userId),
                        "status", status.name(),
                        "now", SqlTimestamps.toDatabase(now)));
    }

    /**
     * 统计拥有指定角色的账号数；用于启动时判断平台管理员是否已存在。
     *
     * @param tenantId 目标租户
     * @param roleCode 角色码
     * @return 账号数
     */
    public long countUsersWithRole(TenantId tenantId, String roleCode) {
        return platformJdbc.countTenant(tenantId, COUNT_USERS_WITH_ROLE, Map.of("roleCode", roleCode));
    }

    /**
     * 映射租户行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 租户元数据
     * @throws SQLException 读取失败
     */
    private static TenantRecord mapTenant(ResultSet rs, int rowNum) throws SQLException {
        LocalDateTime createdAt = rs.getObject("created_at", LocalDateTime.class);
        return new TenantRecord(
                rs.getString("id"),
                rs.getString("name"),
                TenantStatus.valueOf(rs.getString("status")),
                rs.getString("plan"),
                rs.getInt("retention_days"),
                SqlTimestamps.toInstant(createdAt));
    }

    /**
     * 映射账号行。
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
                rs.getString("external_issuer"),
                SqlTimestamps.toInstant(lastLoginAt),
                SqlTimestamps.toInstant(rs.getObject("created_at", LocalDateTime.class)));
    }

    /**
     * 账号-角色行，仅用于批量聚合。
     *
     * @param userId 账号 ID
     * @param code 角色码
     */
    private record UserRoleRow(UUID userId, String code) {}
}
