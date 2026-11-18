package com.riccon.api.department.persistence;

import com.riccon.api.department.domain.DepartmentMemberRecord;
import com.riccon.api.department.domain.DepartmentRecord;
import com.riccon.api.organization.domain.DepartmentMemberSnapshot;
import com.riccon.api.organization.domain.DepartmentSnapshot;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.UserStatus;
import com.riccon.platform.domain.organization.DepartmentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 租户范围内的部门读取 SQL。
 *
 * <p>只被 {@code ..department..} 的仓储使用；所有查询经过 {@link TenantJdbc} 强制 {@code tenant_id}
 * 条件与线程租户一致，不读取其他租户数据。本类只返回组织元数据，不触碰会话、文件、报告或知识库
 * （ADR-0018 第 2.3 节）。
 */
@Component
public class DepartmentJdbc {

    /** 部门快照字段；成员数只统计启用账号。 */
    private static final String DEPARTMENT_COLUMNS = """
            d.id, d.name, d.status, d.manager_user_id, m.display_name AS manager_display_name,
            (SELECT COUNT(*) FROM app_user c
              WHERE c.tenant_id = d.tenant_id AND c.department_id = d.id AND c.status = 'ACTIVE')
                AS member_count,
            d.created_at, d.updated_at
            """;

    /** 部门列表。 */
    private static final String LIST_DEPARTMENTS = "SELECT " + DEPARTMENT_COLUMNS
            + " FROM department d"
            + " LEFT JOIN app_user m ON m.tenant_id = d.tenant_id AND m.id = d.manager_user_id"
            + " WHERE d.tenant_id = :tenantId"
            + " ORDER BY d.name ASC, d.id ASC LIMIT :limit OFFSET :offset";

    /** 部门总数。 */
    private static final String COUNT_DEPARTMENTS = "SELECT COUNT(*) FROM department d WHERE d.tenant_id = :tenantId";

    /** 单个部门。 */
    private static final String FIND_DEPARTMENT = "SELECT " + DEPARTMENT_COLUMNS
            + " FROM department d"
            + " LEFT JOIN app_user m ON m.tenant_id = d.tenant_id AND m.id = d.manager_user_id"
            + " WHERE d.tenant_id = :tenantId AND d.id = :departmentId";

    /** 账号当前主部门；停用账号不返回。 */
    private static final String FIND_DEPARTMENT_OF_USER = "SELECT " + DEPARTMENT_COLUMNS
            + " FROM app_user u"
            + " JOIN department d ON d.tenant_id = u.tenant_id AND d.id = u.department_id"
            + " LEFT JOIN app_user m ON m.tenant_id = d.tenant_id AND m.id = d.manager_user_id"
            + " WHERE u.tenant_id = :tenantId AND u.id = :userId AND u.status = 'ACTIVE'";

    /** 部门成员，包含停用账号以便管理员清理归属；按展示名、账号 ID 稳定排序。 */
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

    /** 部门当前启用成员，用于组织目录。 */
    private static final String LIST_ACTIVE_MEMBERS = """
            SELECT u.id, u.username, u.display_name
            FROM app_user u
            WHERE u.tenant_id = :tenantId AND u.department_id = :departmentId
              AND u.status = 'ACTIVE'
            ORDER BY u.display_name ASC, u.id ASC
            """;

    /** 判断账号是否为部门当前启用成员；同时要求部门启用。 */
    private static final String COUNT_ACTIVE_MEMBER = """
            SELECT COUNT(*)
            FROM app_user u
            JOIN department d ON d.tenant_id = u.tenant_id AND d.id = u.department_id
            WHERE u.tenant_id = :tenantId AND u.department_id = :departmentId
              AND u.id = :userId AND u.status = 'ACTIVE' AND d.status = 'ACTIVE'
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建部门读取 SQL 组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public DepartmentJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 列出租户内部门，按名称、ID 升序。
     *
     * @param tenantId 租户
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 部门快照
     */
    public List<DepartmentRecord> listDepartments(TenantId tenantId, int limit, int offset) {
        return tenantJdbc.query(
                tenantId, LIST_DEPARTMENTS, Map.of("limit", limit, "offset", offset), DepartmentJdbc::mapDepartment);
    }

    /**
     * 统计租户内部门数。
     *
     * @param tenantId 租户
     * @return 部门数
     */
    public long countDepartments(TenantId tenantId) {
        return tenantJdbc.count(tenantId, COUNT_DEPARTMENTS, Map.of());
    }

    /**
     * 查询租户内单个部门。
     *
     * @param tenantId 租户
     * @param departmentId 部门 ID
     * @return 部门快照；不存在或不属于该租户时为空
     */
    public Optional<DepartmentRecord> findDepartment(TenantId tenantId, UUID departmentId) {
        return tenantJdbc.queryOne(
                tenantId,
                FIND_DEPARTMENT,
                Map.of("departmentId", BinaryUuids.toBytes(departmentId)),
                DepartmentJdbc::mapDepartment);
    }

    /**
     * 查询账号当前主部门。
     *
     * @param tenantId 租户
     * @param userId 账号 ID
     * @return 部门快照；账号停用或未分配部门时为空
     */
    public Optional<DepartmentRecord> findDepartmentOfUser(TenantId tenantId, UUID userId) {
        return tenantJdbc.queryOne(
                tenantId,
                FIND_DEPARTMENT_OF_USER,
                Map.of("userId", BinaryUuids.toBytes(userId)),
                DepartmentJdbc::mapDepartment);
    }

    /**
     * 分页列出部门成员，包含停用账号。
     *
     * @param tenantId 租户
     * @param departmentId 部门 ID
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 成员快照
     */
    public List<DepartmentMemberRecord> listMembers(TenantId tenantId, UUID departmentId, int limit, int offset) {
        return tenantJdbc.query(
                tenantId,
                LIST_MEMBERS,
                Map.of("departmentId", BinaryUuids.toBytes(departmentId), "limit", limit, "offset", offset),
                DepartmentJdbc::mapMember);
    }

    /**
     * 统计部门成员数（含停用账号）。
     *
     * @param tenantId 租户
     * @param departmentId 部门 ID
     * @return 成员数
     */
    public long countMembers(TenantId tenantId, UUID departmentId) {
        return tenantJdbc.count(tenantId, COUNT_MEMBERS, Map.of("departmentId", BinaryUuids.toBytes(departmentId)));
    }

    /**
     * 列出部门当前启用成员，供组织目录使用。
     *
     * @param tenantId 租户
     * @param departmentId 部门 ID
     * @return 成员快照；部门不存在时为空列表
     */
    public List<DepartmentMemberSnapshot> listActiveMembers(TenantId tenantId, UUID departmentId) {
        return tenantJdbc.query(
                tenantId,
                LIST_ACTIVE_MEMBERS,
                Map.of("departmentId", BinaryUuids.toBytes(departmentId)),
                (rs, rowNum) -> new DepartmentMemberSnapshot(
                        BinaryUuids.fromBytes(rs.getBytes("id")),
                        rs.getString("username"),
                        rs.getString("display_name")));
    }

    /**
     * 判断账号是否为部门的当前启用成员。
     *
     * @param tenantId 租户
     * @param departmentId 部门 ID
     * @param userId 账号 ID
     * @return 账号与部门均启用且主部门匹配时返回 {@code true}
     */
    public boolean isActiveMember(TenantId tenantId, UUID departmentId, UUID userId) {
        return tenantJdbc.count(
                        tenantId,
                        COUNT_ACTIVE_MEMBER,
                        Map.of(
                                "departmentId", BinaryUuids.toBytes(departmentId),
                                "userId", BinaryUuids.toBytes(userId)))
                > 0;
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
        Instant created = SqlTimestamps.toInstant(createdAt);
        Instant updated = SqlTimestamps.toInstant(updatedAt);
        return new DepartmentRecord(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                rs.getString("name"),
                DepartmentStatus.valueOf(rs.getString("status")),
                BinaryUuids.fromBytes(rs.getBytes("manager_user_id")),
                rs.getString("manager_display_name"),
                rs.getInt("member_count"),
                created,
                updated);
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
     * 将组织目录快照映射为部门快照；只暴露组织字段。
     *
     * @param department 部门快照
     * @return 组织目录快照
     */
    public static DepartmentSnapshot toSnapshot(DepartmentRecord department) {
        return new DepartmentSnapshot(
                department.id(), department.name(), department.status(), department.managerUserId());
    }
}
