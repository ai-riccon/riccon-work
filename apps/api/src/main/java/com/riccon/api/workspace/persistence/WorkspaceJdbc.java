package com.riccon.api.workspace.persistence;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.api.workspace.domain.WorkspaceMemberRecord;
import com.riccon.api.workspace.domain.WorkspaceRecord;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workspace.WorkspaceKind;
import com.riccon.platform.domain.workspace.WorkspaceStatus;
import com.riccon.platform.domain.workspace.WorkspaceVisibility;
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
 * 工作空间 SQL，也是 M03 空间可见性规则的唯一实现点。
 *
 * <p>所有查询由 {@link TenantJdbc} 强制带 {@code tenant_id}；可见性谓词只在 {@link #VISIBILITY_PREDICATE}
 * 定义一次，由详情、列表与计数共用，禁止在 controller、其他仓储或业务代码复制（ADR-0018 第 3.1 节）。
 * 判定读取数据库当前关系（账号当前部门、部门状态、显式成员行），因此成员移出部门或部门停用会在下一次请求
 * 立即生效，不引入跨请求缓存（ADR-0018 第 3.3 节）。
 *
 * <p>{@code visibility = NULL} 是 M02 旧代码在滚动发布窗口写入的兼容状态：个人空间仍只对所有者可见，
 * 团队空间一律不可见，直到 B1b 回填为 {@code TENANT}，避免在收紧 V9 前放大可见面。
 */
@Component
public class WorkspaceJdbc {

    /** 响应与审计共用的列；{@code wd} 是空间所属部门，只用于展示名称。 */
    private static final String COLUMNS = """
            w.id, w.kind, w.name, w.description, w.owner_user_id,
            w.department_id, wd.name AS department_name, w.visibility,
            w.status, w.created_at, w.updated_at
            """;

    /** 工作空间来源与部门展示名连接；不参与可见性判断。 */
    private static final String WORKSPACE_SOURCE = """
            FROM workspace w
            LEFT JOIN department wd
              ON wd.tenant_id = w.tenant_id
             AND wd.id = w.department_id
            """;

    /**
     * 唯一可见性谓词（ADR-0018 第 3.1 节）。
     *
     * <p>调用方必须提供 {@code actor}（当前启用账号）与 {@code actor_department}（账号当前主部门）。
     * PERSONAL 只认所有者；TEAM+TENANT 保留 M02 历史可见面；DEPT/CUSTOM 同时要求空间部门等于账号当前部门、
     * 部门 ACTIVE；CUSTOM 还要求显式成员行存在。{@code visibility = NULL} 的团队空间不匹配任何分支。
     */
    private static final String VISIBILITY_PREDICATE = """
            (
                   (w.kind = 'PERSONAL' AND w.owner_user_id = actor.id)
                OR (w.kind = 'TEAM' AND w.visibility = 'TENANT')
                OR (w.kind = 'TEAM' AND w.visibility = 'DEPT'
                    AND actor.department_id IS NOT NULL
                    AND w.department_id = actor.department_id
                    AND actor_department.status = 'ACTIVE')
                OR (w.kind = 'TEAM' AND w.visibility = 'CUSTOM'
                    AND actor.department_id IS NOT NULL
                    AND w.department_id = actor.department_id
                    AND actor_department.status = 'ACTIVE'
                    AND EXISTS (SELECT 1 FROM workspace_member wm
                                 WHERE wm.tenant_id = w.tenant_id
                                   AND wm.workspace_id = w.id
                                   AND wm.user_id = actor.id))
            )
            """;

    /** 可访问空间的数据来源；详情、列表、计数必须在这里追加条件，不得另写谓词。 */
    private static final String ACCESSIBLE_SOURCE = """
            FROM workspace w
            JOIN app_user actor
              ON actor.tenant_id = w.tenant_id
             AND actor.id = :userId
             AND actor.status = 'ACTIVE'
            LEFT JOIN department actor_department
              ON actor_department.tenant_id = actor.tenant_id
             AND actor_department.id = actor.department_id
            LEFT JOIN department wd
              ON wd.tenant_id = w.tenant_id
             AND wd.id = w.department_id
            WHERE w.tenant_id = :tenantId
              AND w.deleted_at IS NULL
              AND """ + VISIBILITY_PREDICATE;

    /** 个人空间查询；唯一键保证每人最多一个。 */
    private static final String FIND_PERSONAL = "SELECT " + COLUMNS + WORKSPACE_SOURCE
            + " WHERE w.tenant_id = :tenantId AND w.personal_owner_user_id = :ownerUserId AND w.deleted_at IS NULL";

    /** 按 ID 读取（不做可见性过滤），只供管理能力判定与更新后重读使用。 */
    private static final String FIND_BY_ID = "SELECT " + COLUMNS + WORKSPACE_SOURCE
            + " WHERE w.tenant_id = :tenantId AND w.id = :workspaceId AND w.deleted_at IS NULL";

    /** 可见空间详情；与列表、计数共用同一谓词。 */
    private static final String FIND_ACCESSIBLE = "SELECT " + COLUMNS + ACCESSIBLE_SOURCE + " AND w.id = :workspaceId";

    /** 可见空间计数；与列表、详情共用同一谓词。 */
    private static final String COUNT_ACCESSIBLE = "SELECT COUNT(*) " + ACCESSIBLE_SOURCE + " AND w.status = 'ACTIVE'";

    /** 可见空间分页；个人空间排在前面。 */
    private static final String LIST_ACCESSIBLE = "SELECT " + COLUMNS + ACCESSIBLE_SOURCE + " AND w.status = 'ACTIVE'";

    /** 管理用部门空间列表；只按部门归属过滤，不对成员隐藏，调用方必须先通过管理能力判定。 */
    private static final String LIST_BY_DEPARTMENT = "SELECT " + COLUMNS + WORKSPACE_SOURCE
            + " WHERE w.tenant_id = :tenantId AND w.department_id = :departmentId"
            + " AND w.deleted_at IS NULL AND w.status = 'ACTIVE'"
            + " ORDER BY w.created_at ASC, w.id ASC LIMIT :limit OFFSET :offset";

    /** 管理用部门空间计数。 */
    private static final String COUNT_BY_DEPARTMENT = "SELECT COUNT(*) " + WORKSPACE_SOURCE
            + " WHERE w.tenant_id = :tenantId AND w.department_id = :departmentId"
            + " AND w.deleted_at IS NULL AND w.status = 'ACTIVE'";

    /** 插入工作空间；B3 起同时写入可见性与所属部门。 */
    private static final String INSERT = """
            INSERT INTO workspace (
                tenant_id, id, kind, name, description, owner_user_id, personal_owner_user_id,
                department_id, visibility, status, created_by, created_at, updated_at
            ) VALUES (
                :tenantId, :id, :kind, :name, :description, :ownerUserId, :personalOwnerUserId,
                :departmentId, :visibility, :status, :createdBy, :createdAt, :updatedAt
            )
            """;

    /** 更新名称与说明。 */
    private static final String UPDATE_METADATA = """
            UPDATE workspace
               SET name = :name, description = :description, updated_at = :updatedAt
             WHERE tenant_id = :tenantId AND id = :workspaceId AND deleted_at IS NULL
            """;

    /** 更新可见性；部门不变，跨部门搬移不在 M03 范围。 */
    private static final String UPDATE_VISIBILITY = """
            UPDATE workspace
               SET visibility = :visibility, updated_at = :updatedAt
             WHERE tenant_id = :tenantId AND id = :workspaceId AND deleted_at IS NULL
            """;

    /** 统计 CUSTOM 空间显式成员。 */
    private static final String COUNT_MEMBERS = """
            SELECT COUNT(*)
              FROM workspace_member m
             WHERE m.tenant_id = :tenantId AND m.workspace_id = :workspaceId
            """;

    /** 列出 CUSTOM 空间显式成员；加入时间与账号展示名足够管理界面使用。 */
    private static final String LIST_MEMBERS = """
            SELECT u.id, u.username, u.display_name, m.created_at
              FROM workspace_member m
              JOIN app_user u
                ON u.tenant_id = m.tenant_id
               AND u.id = m.user_id
             WHERE m.tenant_id = :tenantId AND m.workspace_id = :workspaceId
             ORDER BY m.created_at ASC, u.id ASC
             LIMIT :limit OFFSET :offset
            """;

    /** 判断显式成员行是否存在。 */
    private static final String COUNT_MEMBER = """
            SELECT COUNT(*)
              FROM workspace_member m
             WHERE m.tenant_id = :tenantId AND m.workspace_id = :workspaceId AND m.user_id = :userId
            """;

    /** 加入显式成员。 */
    private static final String INSERT_MEMBER = """
            INSERT INTO workspace_member (tenant_id, workspace_id, user_id, granted_by, created_at)
            VALUES (:tenantId, :workspaceId, :userId, :grantedBy, :createdAt)
            """;

    /** 移出显式成员；不存在时返回 0。 */
    private static final String DELETE_MEMBER = """
            DELETE FROM workspace_member
             WHERE tenant_id = :tenantId AND workspace_id = :workspaceId AND user_id = :userId
            """;

    /** 清空空间显式成员；DEPT/CUSTOM 互转时使用。 */
    private static final String DELETE_ALL_MEMBERS = """
            DELETE FROM workspace_member
             WHERE tenant_id = :tenantId AND workspace_id = :workspaceId
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
     * <p>B3 起必须写 {@code visibility} 与 {@code department_id}；个人空间由系统创建时传
     * {@link WorkspaceVisibility#PRIVATE} 与空部门，团队空间必须传启用部门（ADR-0018 第 2.1/6.2 节）。
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
        params.put(
                "departmentId",
                workspace.departmentId() == null ? null : BinaryUuids.toBytes(workspace.departmentId()));
        params.put(
                "visibility",
                workspace.visibility() == null ? null : workspace.visibility().name());
        params.put("status", workspace.status().name());
        params.put("createdBy", BinaryUuids.toBytes(createdBy));
        params.put("createdAt", SqlTimestamps.toDatabase(workspace.createdAt()));
        params.put("updatedAt", SqlTimestamps.toDatabase(workspace.updatedAt()));
        tenantJdbc.insert(tenantId, INSERT, params);
    }

    /**
     * 按 ID 读取工作空间，不做可见性过滤。
     *
     * <p>只供管理能力判定与更新后重读使用；返回结果不得直接作为内容响应，内容读取必须经过
     * {@link #findAccessible}。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @return 工作空间；不存在或属于其他租户时为空
     */
    public Optional<WorkspaceRecord> findById(TenantId tenantId, UUID workspaceId) {
        return tenantJdbc.queryOne(
                tenantId, FIND_BY_ID, Map.of("workspaceId", BinaryUuids.toBytes(workspaceId)), WorkspaceJdbc::mapRow);
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
        StringBuilder sql = new StringBuilder(COUNT_ACCESSIBLE);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", BinaryUuids.toBytes(userId));
        if (kind != null) {
            sql.append(" AND w.kind = :kind");
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
        StringBuilder sql = new StringBuilder(LIST_ACCESSIBLE);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", BinaryUuids.toBytes(userId));
        if (kind != null) {
            sql.append(" AND w.kind = :kind");
            params.put("kind", kind.name());
        }
        sql.append(" ORDER BY CASE WHEN w.kind = 'PERSONAL' THEN 0 ELSE 1 END, w.created_at ASC, w.id ASC")
                .append(" LIMIT :limit OFFSET :offset");
        params.put("limit", limit);
        params.put("offset", offset);
        return tenantJdbc.query(tenantId, sql.toString(), params, WorkspaceJdbc::mapRow);
    }

    /**
     * 分页列出部门内 ACTIVE 空间，供管理界面使用。
     *
     * <p>调用方必须先完成“租户管理员或本部门负责人”判定；本方法不应用成员可见性，否则负责人将看不到
     * 未加入的 CUSTOM 空间而无法管理。返回结果不含任何业务内容。
     *
     * @param tenantId 当前租户
     * @param departmentId 部门 ID
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 空间元数据列表
     */
    public List<WorkspaceRecord> listByDepartment(TenantId tenantId, UUID departmentId, int limit, int offset) {
        return tenantJdbc.query(
                tenantId,
                LIST_BY_DEPARTMENT,
                Map.of(
                        "departmentId", BinaryUuids.toBytes(departmentId),
                        "limit", limit,
                        "offset", offset),
                WorkspaceJdbc::mapRow);
    }

    /**
     * 统计部门内 ACTIVE 空间。
     *
     * @param tenantId 当前租户
     * @param departmentId 部门 ID
     * @return 空间数
     */
    public long countByDepartment(TenantId tenantId, UUID departmentId) {
        return tenantJdbc.count(
                tenantId, COUNT_BY_DEPARTMENT, Map.of("departmentId", BinaryUuids.toBytes(departmentId)));
    }

    /**
     * 更新空间名称与说明。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param name 新名称
     * @param description 新说明；可为空
     * @param updatedAt 更新时间
     * @return 受影响行数
     */
    public int updateMetadata(TenantId tenantId, UUID workspaceId, String name, String description, Instant updatedAt) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workspaceId", BinaryUuids.toBytes(workspaceId));
        params.put("name", name);
        params.put("description", description);
        params.put("updatedAt", SqlTimestamps.toDatabase(updatedAt));
        return tenantJdbc.update(tenantId, UPDATE_METADATA, params);
    }

    /**
     * 更新空间可见性。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param visibility 新可见性
     * @param updatedAt 更新时间
     * @return 受影响行数
     */
    public int updateVisibility(
            TenantId tenantId, UUID workspaceId, WorkspaceVisibility visibility, Instant updatedAt) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workspaceId", BinaryUuids.toBytes(workspaceId));
        params.put("visibility", visibility.name());
        params.put("updatedAt", SqlTimestamps.toDatabase(updatedAt));
        return tenantJdbc.update(tenantId, UPDATE_VISIBILITY, params);
    }

    /**
     * 统计 CUSTOM 空间显式成员。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @return 成员数
     */
    public long countMembers(TenantId tenantId, UUID workspaceId) {
        return tenantJdbc.count(tenantId, COUNT_MEMBERS, Map.of("workspaceId", BinaryUuids.toBytes(workspaceId)));
    }

    /**
     * 分页列出 CUSTOM 空间显式成员。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 成员列表
     */
    public List<WorkspaceMemberRecord> listMembers(TenantId tenantId, UUID workspaceId, int limit, int offset) {
        return tenantJdbc.query(
                tenantId,
                LIST_MEMBERS,
                Map.of("workspaceId", BinaryUuids.toBytes(workspaceId), "limit", limit, "offset", offset),
                WorkspaceJdbc::mapMember);
    }

    /**
     * 判断显式成员行是否存在。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param userId 账号 ID
     * @return 存在时返回 {@code true}
     */
    public boolean memberExists(TenantId tenantId, UUID workspaceId, UUID userId) {
        return tenantJdbc.count(
                        tenantId,
                        COUNT_MEMBER,
                        Map.of("workspaceId", BinaryUuids.toBytes(workspaceId), "userId", BinaryUuids.toBytes(userId)))
                > 0;
    }

    /**
     * 加入 CUSTOM 空间显式成员。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param userId 账号 ID
     * @param grantedBy 操作人账号 ID
     * @param grantedAt 加入时间
     */
    public void insertMember(TenantId tenantId, UUID workspaceId, UUID userId, UUID grantedBy, Instant grantedAt) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workspaceId", BinaryUuids.toBytes(workspaceId));
        params.put("userId", BinaryUuids.toBytes(userId));
        params.put("grantedBy", BinaryUuids.toBytes(grantedBy));
        params.put("createdAt", SqlTimestamps.toDatabase(grantedAt));
        tenantJdbc.insert(tenantId, INSERT_MEMBER, params);
    }

    /**
     * 移出 CUSTOM 空间显式成员；不存在时返回 0。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param userId 账号 ID
     * @return 删除行数
     */
    public int deleteMember(TenantId tenantId, UUID workspaceId, UUID userId) {
        return tenantJdbc.update(
                tenantId,
                DELETE_MEMBER,
                Map.of("workspaceId", BinaryUuids.toBytes(workspaceId), "userId", BinaryUuids.toBytes(userId)));
    }

    /**
     * 清空空间显式成员；DEPT/CUSTOM 互转时使用。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @return 删除行数
     */
    public int deleteAllMembers(TenantId tenantId, UUID workspaceId) {
        return tenantJdbc.update(tenantId, DELETE_ALL_MEMBERS, Map.of("workspaceId", BinaryUuids.toBytes(workspaceId)));
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
        String visibility = rs.getString("visibility");
        return new WorkspaceRecord(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                WorkspaceKind.valueOf(rs.getString("kind")),
                rs.getString("name"),
                rs.getString("description"),
                BinaryUuids.fromBytes(rs.getBytes("owner_user_id")),
                BinaryUuids.fromBytes(rs.getBytes("department_id")),
                rs.getString("department_name"),
                visibility == null ? null : WorkspaceVisibility.valueOf(visibility),
                WorkspaceStatus.valueOf(rs.getString("status")),
                toInstant(rs.getObject("created_at", LocalDateTime.class)),
                toInstant(rs.getObject("updated_at", LocalDateTime.class)));
    }

    /**
     * 映射显式成员行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 成员快照
     * @throws SQLException 列读取失败
     */
    private static WorkspaceMemberRecord mapMember(ResultSet rs, int rowNum) throws SQLException {
        return new WorkspaceMemberRecord(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                rs.getString("username"),
                rs.getString("display_name"),
                toInstant(rs.getObject("created_at", LocalDateTime.class)));
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
