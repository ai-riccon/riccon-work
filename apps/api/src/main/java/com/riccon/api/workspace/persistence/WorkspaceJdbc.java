package com.riccon.api.workspace.persistence;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.api.workspace.domain.WorkspaceRecord;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workspace.WorkspaceKind;
import com.riccon.platform.domain.workspace.WorkspaceStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 工作空间 SQL。
 *
 * <p>所有查询由 {@link TenantJdbc} 强制带 {@code tenant_id}；可见性规则在 SQL 中固定：个人空间只对所有者可见，
 * 团队空间对本租户员工可见。列表默认只返回 ACTIVE，归档空间仍可按 ID 读取。
 */
@Component
public class WorkspaceJdbc {

    /** 响应与审计共用的列。 */
    private static final String COLUMNS = "id, kind, name, description, owner_user_id, status, created_at, updated_at";

    /** 个人空间查询；唯一键保证每人最多一个。 */
    private static final String FIND_PERSONAL = "SELECT " + COLUMNS
            + " FROM workspace"
            + " WHERE tenant_id = :tenantId AND personal_owner_user_id = :ownerUserId AND deleted_at IS NULL";

    /** 可见空间查询；团队空间不限制成员范围（M02）。 */
    private static final String FIND_ACCESSIBLE = "SELECT " + COLUMNS
            + " FROM workspace"
            + " WHERE tenant_id = :tenantId AND id = :workspaceId AND deleted_at IS NULL"
            + " AND (kind = 'TEAM' OR (kind = 'PERSONAL' AND owner_user_id = :userId))";

    /** 插入工作空间。 */
    private static final String INSERT = """
            INSERT INTO workspace (
                tenant_id, id, kind, name, description, owner_user_id, personal_owner_user_id,
                status, created_by, created_at, updated_at
            ) VALUES (
                :tenantId, :id, :kind, :name, :description, :ownerUserId, :personalOwnerUserId,
                :status, :createdBy, :createdAt, :updatedAt
            )
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建工作空间 SQL 组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public WorkspaceJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 查询用户的个人空间。
     *
     * @param tenantId 当前租户
     * @param userId 账号 ID
     * @return 个人空间；不存在时为空
     */
    public Optional<WorkspaceRecord> findPersonal(TenantId tenantId, UUID userId) {
        return tenantJdbc.queryOne(
                tenantId, FIND_PERSONAL, Map.of("ownerUserId", BinaryUuids.toBytes(userId)), WorkspaceJdbc::mapRow);
    }

    /**
     * 插入工作空间。
     *
     * @param tenantId 当前租户
     * @param personalOwnerUserId 个人空间所有者；团队空间为 {@code null}
     * @param workspace 工作空间快照
     * @param createdBy 创建人账号 ID
     */
    public void insert(TenantId tenantId, UUID personalOwnerUserId, WorkspaceRecord workspace, UUID createdBy) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(workspace.id()));
        params.put("kind", workspace.kind().name());
        params.put("name", workspace.name());
        params.put("description", workspace.description());
        params.put("ownerUserId", BinaryUuids.toBytes(workspace.ownerUserId()));
        params.put(
                "personalOwnerUserId", personalOwnerUserId == null ? null : BinaryUuids.toBytes(personalOwnerUserId));
        params.put("status", workspace.status().name());
        params.put("createdBy", BinaryUuids.toBytes(createdBy));
        params.put("createdAt", SqlTimestamps.toDatabase(workspace.createdAt()));
        params.put("updatedAt", SqlTimestamps.toDatabase(workspace.updatedAt()));
        tenantJdbc.insert(tenantId, INSERT, params);
    }

    /**
     * 按 ID 查询对指定用户可见的工作空间。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param userId 账号 ID
     * @return 工作空间；不存在、属于其他租户或不可见时为空
     */
    public Optional<WorkspaceRecord> findAccessible(TenantId tenantId, UUID workspaceId, UUID userId) {
        return tenantJdbc.queryOne(
                tenantId,
                FIND_ACCESSIBLE,
                Map.of("workspaceId", BinaryUuids.toBytes(workspaceId), "userId", BinaryUuids.toBytes(userId)),
                WorkspaceJdbc::mapRow);
    }

    /**
     * 统计可见的 ACTIVE 工作空间。
     *
     * @param tenantId 当前租户
     * @param userId 账号 ID
     * @param kind 可选类型过滤
     * @return 总数
     */
    public long countAccessible(TenantId tenantId, UUID userId, WorkspaceKind kind) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                  FROM workspace
                 WHERE tenant_id = :tenantId
                   AND deleted_at IS NULL
                   AND status = 'ACTIVE'
                   AND (kind = 'TEAM' OR (kind = 'PERSONAL' AND owner_user_id = :userId))
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", BinaryUuids.toBytes(userId));
        if (kind != null) {
            sql.append(" AND kind = :kind");
            params.put("kind", kind.name());
        }
        return tenantJdbc.count(tenantId, sql.toString(), params);
    }

    /**
     * 分页查询可见的 ACTIVE 工作空间，个人空间排在前面。
     *
     * @param tenantId 当前租户
     * @param userId 账号 ID
     * @param kind 可选类型过滤
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 工作空间列表
     */
    public List<WorkspaceRecord> listAccessible(
            TenantId tenantId, UUID userId, WorkspaceKind kind, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, kind, name, description, owner_user_id, status, created_at, updated_at
                  FROM workspace
                 WHERE tenant_id = :tenantId
                   AND deleted_at IS NULL
                   AND status = 'ACTIVE'
                   AND (kind = 'TEAM' OR (kind = 'PERSONAL' AND owner_user_id = :userId))
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", BinaryUuids.toBytes(userId));
        if (kind != null) {
            sql.append(" AND kind = :kind");
            params.put("kind", kind.name());
        }
        sql.append(" ORDER BY CASE WHEN kind = 'PERSONAL' THEN 0 ELSE 1 END, created_at ASC, id ASC")
                .append(" LIMIT :limit OFFSET :offset");
        params.put("limit", limit);
        params.put("offset", offset);
        return tenantJdbc.query(tenantId, sql.toString(), params, WorkspaceJdbc::mapRow);
    }

    /**
     * 映射工作空间行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 工作空间快照
     * @throws SQLException 列读取失败
     */
    private static WorkspaceRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new WorkspaceRecord(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                WorkspaceKind.valueOf(rs.getString("kind")),
                rs.getString("name"),
                rs.getString("description"),
                BinaryUuids.fromBytes(rs.getBytes("owner_user_id")),
                WorkspaceStatus.valueOf(rs.getString("status")),
                toInstant(rs.getObject("created_at", LocalDateTime.class)),
                toInstant(rs.getObject("updated_at", LocalDateTime.class)));
    }

    /**
     * 将数据库 UTC 时间转为 Instant。
     *
     * @param value 数据库时间
     * @return UTC 时间；null 原样返回
     */
    private static Instant toInstant(LocalDateTime value) {
        return SqlTimestamps.toInstant(value);
    }
}
