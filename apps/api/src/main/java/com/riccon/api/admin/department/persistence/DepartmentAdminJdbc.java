package com.riccon.api.admin.department.persistence;

import com.riccon.api.admin.persistence.PlatformJdbc;
import com.riccon.api.department.domain.DepartmentMemberRecord;
import com.riccon.api.department.domain.DepartmentRecord;
import com.riccon.api.department.domain.DepartmentUserRecord;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.UserStatus;
import com.riccon.platform.domain.organization.DepartmentStatus;
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
 * 平台管理员跨租户部门 SQL。
 *
 * <p>只被 {@code ..admin.department..} 使用；所有语句显式携带目标租户 {@code tenant_id = :tenantId}，与 M02
 * 的 {@code AdminJdbc} 口径一致。业务读写使用目标租户作用域，审计事件由 service 切回操作者租户写入
 * （ADR-0018 第 5 节）。本类不读取目标租户的会话、文件、报告、产物或知识库。
 */
@Component
public class DepartmentAdminJdbc {

    /** 部门快照字段；成员数只统计启用账号。 */
    private static final String DEPARTMENT_COLUMNS = """
            d.id, d.name, d.status, d.manager_user_id, m.display_name AS manager_display_name,
            (SELECT COUNT(*) FROM app_user c
              WHERE c.tenant_id = d.tenant_id AND c.department_id = d.id AND c.status = 'ACTIVE')
                AS member_count,
            d.created_at, d.updated_at
            """;

    /** 查询单个部门。 */
    private static final String FIND_DEPARTMENT = "SELECT " + DEPARTMENT_COLUMNS
            + " FROM department d"
            + " LEFT JOIN app_user m ON m.tenant_id = d.tenant_id AND m.id = d.manager_user_id"
            + " WHERE d.tenant_id = :tenantId AND d.id = :departmentId";

    /** 部门列表。 */
    private static final String LIST_DEPARTMENTS = "SELECT " + DEPARTMENT_COLUMNS
            + " FROM department d"
            + " LEFT JOIN app_user m ON m.tenant_id = d.tenant_id AND m.id = d.manager_user_id"
            + " WHERE d.tenant_id = :tenantId"
            + " ORDER BY d.name ASC, d.id ASC LIMIT :limit OFFSET :offset";

    /** 按状态过滤的部门列表。 */
    private static final String LIST_DEPARTMENTS_BY_STATUS = "SELECT " + DEPARTMENT_COLUMNS
            + " FROM department d"
            + " LEFT JOIN app_user m ON m.tenant_id = d.tenant_id AND m.id = d.manager_user_id"
            + " WHERE d.tenant_id = :tenantId AND d.status = :status"
            + " ORDER BY d.name ASC, d.id ASC LIMIT :limit OFFSET :offset";

    /** 部门总数。 */
    private static final String COUNT_DEPARTMENTS = "SELECT COUNT(*) FROM department d WHERE d.tenant_id = :tenantId";

    /** 按状态过滤的部门总数。 */
    private static final String COUNT_DEPARTMENTS_BY_STATUS =
            "SELECT COUNT(*) FROM department d WHERE d.tenant_id = :tenantId AND d.status = :status";

    /** 同租户内部门名是否已存在；列排序规则大小写/重音不敏感。 */
    private static final String COUNT_DEPARTMENT_NAME = """
            SELECT COUNT(*) FROM department d
            WHERE d.tenant_id = :tenantId AND d.name = :name
            """;

    /** 插入部门；负责人初始为空，创建人是平台管理员 UUID（逻辑引用，无复合外键）。 */
    private static final String INSERT_DEPARTMENT = """
            INSERT INTO department (
                tenant_id, id, name, manager_user_id, status, created_by, disabled_at, created_at, updated_at
            ) VALUES (
                :tenantId, :id, :name, NULL, 'ACTIVE', :createdBy, NULL, :now, :now
            )
            """;

    /** 仅启用部门可重命名。 */
    private static final String UPDATE_DEPARTMENT_NAME = """
            UPDATE department SET name = :name, updated_at = :now
            WHERE tenant_id = :tenantId AND id = :departmentId AND status = 'ACTIVE'
            """;

    /** 更新部门状态与停用时间。 */
    private static final String UPDATE_DEPARTMENT_STATUS = """
            UPDATE department SET status = :status, disabled_at = :disabledAt, updated_at = :now
            WHERE tenant_id = :tenantId AND id = :departmentId
            """;

    /** 更新负责人。 */
    private static final String UPDATE_DEPARTMENT_MANAGER = """
            UPDATE department SET manager_user_id = :managerUserId, updated_at = :now
            WHERE tenant_id = :tenantId AND id = :departmentId
            """;

    /** 分页列出部门成员；包含停用账号以便管理员清理归属。 */
    private static final String LIST_MEMBERS = """
            SELECT u.id, u.username, u.display_name, u.status
            FROM app_user u
            WHERE u.tenant_id = :tenantId AND u.department_id = :departmentId
            ORDER BY u.display_name ASC, u.id ASC
            LIMIT :limit OFFSET :offset
            """;

    /** 部门成员总数（含停用账号）。 */
    private static final String COUNT_MEMBERS = """
            SELECT COUNT(*) FROM app_user u
            WHERE u.tenant_id = :tenantId AND u.department_id = :departmentId
            """;

    /** 部门当前启用成员数。 */
    private static final String COUNT_ACTIVE_MEMBERS = """
            SELECT COUNT(*) FROM app_user u
            WHERE u.tenant_id = :tenantId AND u.department_id = :departmentId AND u.status = 'ACTIVE'
            """;

    /** 读取账号归属与状态；跨租户账号在这里按不存在处理。 */
    private static final String FIND_USER = """
            SELECT u.id, u.username, u.display_name, u.status, u.department_id
            FROM app_user u
            WHERE u.tenant_id = :tenantId AND u.id = :userId
            """;

    /** 把未分配部门的账号加入目标部门；已有部门时返回 0 行，由服务层转 409。 */
    private static final String ASSIGN_USER_DEPARTMENT = """
            UPDATE app_user SET department_id = :departmentId, updated_at = :now
            WHERE tenant_id = :tenantId AND id = :userId AND department_id IS NULL
            """;

    /** 把账号移出指定部门；归属已变化时返回 0 行。 */
    private static final String CLEAR_USER_DEPARTMENT = """
            UPDATE app_user SET department_id = NULL, updated_at = :now
            WHERE tenant_id = :tenantId AND id = :userId AND department_id = :departmentId
            """;

    /**
     * 清理该用户在本部门 CUSTOM 空间的显式成员行。
     *
     * <p>ADR-0018 第 2.2 节要求移出部门时在同一事务内清理，避免重新加入后恢复旧共享；B2 用一条受租户与部门
     * 限定的事务内 SQL 完成，不依赖 B3。
     */
    private static final String DELETE_CUSTOM_MEMBERS = """
            DELETE FROM workspace_member
            WHERE tenant_id = :tenantId
              AND user_id = :userId
              AND workspace_id IN (
                  SELECT w.id FROM workspace w
                  WHERE w.tenant_id = :tenantId
                    AND w.department_id = :departmentId
                    AND w.visibility = 'CUSTOM'
              )
            """;

    /** 跨租户平台管理 JDBC 入口。 */
    private final PlatformJdbc platformJdbc;

    /**
     * 创建部门管理 SQL 组件。
     *
     * @param platformJdbc 跨租户平台管理 JDBC 入口
     */
    public DepartmentAdminJdbc(PlatformJdbc platformJdbc) {
        this.platformJdbc = Objects.requireNonNull(platformJdbc, "platformJdbc must not be null");
    }

    /**
     * 查询目标租户部门。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @return 部门快照；不存在或不属于目标租户时为空
     */
    public Optional<DepartmentRecord> findDepartment(TenantId tenantId, UUID departmentId) {
        return platformJdbc.queryOneTenant(
                tenantId,
                FIND_DEPARTMENT,
                Map.of("departmentId", BinaryUuids.toBytes(departmentId)),
                DepartmentAdminJdbc::mapDepartment);
    }

    /**
     * 分页列出目标租户部门。
     *
     * @param tenantId 目标租户
     * @param status 可选状态过滤
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 部门快照
     */
    public List<DepartmentRecord> listDepartments(TenantId tenantId, DepartmentStatus status, int limit, int offset) {
        if (status == null) {
            return platformJdbc.queryTenant(
                    tenantId,
                    LIST_DEPARTMENTS,
                    Map.of("limit", limit, "offset", offset),
                    DepartmentAdminJdbc::mapDepartment);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", status.name());
        params.put("limit", limit);
        params.put("offset", offset);
        return platformJdbc.queryTenant(
                tenantId, LIST_DEPARTMENTS_BY_STATUS, params, DepartmentAdminJdbc::mapDepartment);
    }

    /**
     * 统计目标租户部门数。
     *
     * @param tenantId 目标租户
     * @param status 可选状态过滤
     * @return 部门数
     */
    public long countDepartments(TenantId tenantId, DepartmentStatus status) {
        if (status == null) {
            return platformJdbc.countTenant(tenantId, COUNT_DEPARTMENTS, Map.of());
        }
        return platformJdbc.countTenant(tenantId, COUNT_DEPARTMENTS_BY_STATUS, Map.of("status", status.name()));
    }

    /**
     * 判断目标租户内是否已有同名部门。
     *
     * @param tenantId 目标租户
     * @param name 部门名
     * @return 存在时返回 {@code true}
     */
    public boolean existsByName(TenantId tenantId, String name) {
        return platformJdbc.countTenant(tenantId, COUNT_DEPARTMENT_NAME, Map.of("name", name)) > 0;
    }

    /**
     * 插入部门。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param name 部门名
     * @param createdBy 创建人账号 ID（平台管理员）
     * @param now 创建/更新时间
     */
    public void insertDepartment(TenantId tenantId, UUID departmentId, String name, UUID createdBy, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(departmentId));
        params.put("name", name);
        params.put("createdBy", BinaryUuids.toBytes(createdBy));
        params.put("now", SqlTimestamps.toDatabase(now));
        platformJdbc.insertTenant(tenantId, INSERT_DEPARTMENT, params);
    }

    /**
     * 重命名启用部门。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param name 新名称
     * @param now 更新时间
     * @return 受影响行数；停用部门返回 0
     */
    public int updateName(TenantId tenantId, UUID departmentId, String name, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("departmentId", BinaryUuids.toBytes(departmentId));
        params.put("name", name);
        params.put("now", SqlTimestamps.toDatabase(now));
        return platformJdbc.updateTenant(tenantId, UPDATE_DEPARTMENT_NAME, params);
    }

    /**
     * 更新部门状态。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param status 目标状态
     * @param disabledAt 停用时间；启用时为 {@code null}
     * @param now 更新时间
     * @return 受影响行数
     */
    public int updateStatus(
            TenantId tenantId, UUID departmentId, DepartmentStatus status, Instant disabledAt, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("departmentId", BinaryUuids.toBytes(departmentId));
        params.put("status", status.name());
        params.put("disabledAt", SqlTimestamps.toDatabase(disabledAt));
        params.put("now", SqlTimestamps.toDatabase(now));
        return platformJdbc.updateTenant(tenantId, UPDATE_DEPARTMENT_STATUS, params);
    }

    /**
     * 更新部门负责人。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param managerUserId 新负责人账号 ID
     * @param now 更新时间
     * @return 受影响行数
     */
    public int updateManager(TenantId tenantId, UUID departmentId, UUID managerUserId, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("departmentId", BinaryUuids.toBytes(departmentId));
        params.put("managerUserId", BinaryUuids.toBytes(managerUserId));
        params.put("now", SqlTimestamps.toDatabase(now));
        return platformJdbc.updateTenant(tenantId, UPDATE_DEPARTMENT_MANAGER, params);
    }

    /**
     * 分页列出部门成员。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 成员快照
     */
    public List<DepartmentMemberRecord> listMembers(TenantId tenantId, UUID departmentId, int limit, int offset) {
        return platformJdbc.queryTenant(
                tenantId,
                LIST_MEMBERS,
                Map.of("departmentId", BinaryUuids.toBytes(departmentId), "limit", limit, "offset", offset),
                DepartmentAdminJdbc::mapMember);
    }

    /**
     * 统计部门成员数（含停用账号）。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @return 成员数
     */
    public long countMembers(TenantId tenantId, UUID departmentId) {
        return platformJdbc.countTenant(
                tenantId, COUNT_MEMBERS, Map.of("departmentId", BinaryUuids.toBytes(departmentId)));
    }

    /**
     * 统计部门当前启用成员数。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @return 启用成员数
     */
    public long countActiveMembers(TenantId tenantId, UUID departmentId) {
        return platformJdbc.countTenant(
                tenantId, COUNT_ACTIVE_MEMBERS, Map.of("departmentId", BinaryUuids.toBytes(departmentId)));
    }

    /**
     * 查询目标租户内的账号归属与状态。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @return 账号快照；不存在或属于其他租户时为空
     */
    public Optional<DepartmentUserRecord> findUser(TenantId tenantId, UUID userId) {
        return platformJdbc.queryOneTenant(
                tenantId, FIND_USER, Map.of("userId", BinaryUuids.toBytes(userId)), DepartmentAdminJdbc::mapUser);
    }

    /**
     * 把未分配部门的账号加入目标部门。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param userId 账号 ID
     * @param now 更新时间
     * @return 受影响行数；账号已有部门时返回 0
     */
    public int assignDepartment(TenantId tenantId, UUID departmentId, UUID userId, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("departmentId", BinaryUuids.toBytes(departmentId));
        params.put("userId", BinaryUuids.toBytes(userId));
        params.put("now", SqlTimestamps.toDatabase(now));
        return platformJdbc.updateTenant(tenantId, ASSIGN_USER_DEPARTMENT, params);
    }

    /**
     * 把账号移出指定部门。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param userId 账号 ID
     * @param now 更新时间
     * @return 受影响行数；账号不属于该部门时返回 0
     */
    public int clearDepartment(TenantId tenantId, UUID departmentId, UUID userId, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("departmentId", BinaryUuids.toBytes(departmentId));
        params.put("userId", BinaryUuids.toBytes(userId));
        params.put("now", SqlTimestamps.toDatabase(now));
        return platformJdbc.updateTenant(tenantId, CLEAR_USER_DEPARTMENT, params);
    }

    /**
     * 清理账号在本部门 CUSTOM 空间的显式成员行。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param userId 账号 ID
     * @return 删除行数
     */
    public int deleteCustomWorkspaceMembers(TenantId tenantId, UUID departmentId, UUID userId) {
        return platformJdbc.updateTenant(
                tenantId,
                DELETE_CUSTOM_MEMBERS,
                Map.of(
                        "departmentId", BinaryUuids.toBytes(departmentId),
                        "userId", BinaryUuids.toBytes(userId)));
    }

    /**
     * 映射部门行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 部门快照
     * @throws SQLException 读取失败
     */
    private static DepartmentRecord mapDepartment(ResultSet rs, int rowNum) throws SQLException {
        LocalDateTime createdAt = rs.getObject("created_at", LocalDateTime.class);
        LocalDateTime updatedAt = rs.getObject("updated_at", LocalDateTime.class);
        return new DepartmentRecord(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                rs.getString("name"),
                DepartmentStatus.valueOf(rs.getString("status")),
                BinaryUuids.fromBytes(rs.getBytes("manager_user_id")),
                rs.getString("manager_display_name"),
                rs.getInt("member_count"),
                SqlTimestamps.toInstant(createdAt),
                SqlTimestamps.toInstant(updatedAt));
    }

    /**
     * 映射成员行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 成员快照
     * @throws SQLException 读取失败
     */
    private static DepartmentMemberRecord mapMember(ResultSet rs, int rowNum) throws SQLException {
        return new DepartmentMemberRecord(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                rs.getString("username"),
                rs.getString("display_name"),
                UserStatus.valueOf(rs.getString("status")));
    }

    /**
     * 映射账号行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 账号快照
     * @throws SQLException 读取失败
     */
    private static DepartmentUserRecord mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new DepartmentUserRecord(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                rs.getString("username"),
                rs.getString("display_name"),
                UserStatus.valueOf(rs.getString("status")),
                BinaryUuids.fromBytes(rs.getBytes("department_id")));
    }
}
