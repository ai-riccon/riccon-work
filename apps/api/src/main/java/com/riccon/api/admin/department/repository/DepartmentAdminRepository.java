package com.riccon.api.admin.department.repository;

import com.riccon.api.admin.department.persistence.DepartmentAdminJdbc;
import com.riccon.api.department.domain.DepartmentMemberRecord;
import com.riccon.api.department.domain.DepartmentRecord;
import com.riccon.api.department.domain.DepartmentUserRecord;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.organization.DepartmentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 平台管理员部门仓储。
 *
 * <p>只做持久化编排；事务、租户校验与审计在 {@code DepartmentAdminService} 完成。跨租户访问必须显式传入
 * 目标租户，SQL 一律经过 {@code PlatformJdbc} 的租户作用域方法（ADR-0018 第 5 节）。
 */
@Repository
public class DepartmentAdminRepository {

    /** 跨租户部门 SQL。 */
    private final DepartmentAdminJdbc departmentAdminJdbc;

    /**
     * 创建部门管理仓储。
     *
     * @param departmentAdminJdbc 跨租户部门 SQL
     */
    public DepartmentAdminRepository(DepartmentAdminJdbc departmentAdminJdbc) {
        this.departmentAdminJdbc = Objects.requireNonNull(departmentAdminJdbc, "departmentAdminJdbc must not be null");
    }

    /**
     * 查询目标租户部门。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @return 部门快照
     */
    public Optional<DepartmentRecord> findDepartment(TenantId tenantId, UUID departmentId) {
        return departmentAdminJdbc.findDepartment(tenantId, departmentId);
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
        return departmentAdminJdbc.listDepartments(tenantId, status, limit, offset);
    }

    /**
     * 统计目标租户部门数。
     *
     * @param tenantId 目标租户
     * @param status 可选状态过滤
     * @return 部门数
     */
    public long countDepartments(TenantId tenantId, DepartmentStatus status) {
        return departmentAdminJdbc.countDepartments(tenantId, status);
    }

    /**
     * 判断目标租户内是否已有同名部门。
     *
     * @param tenantId 目标租户
     * @param name 部门名
     * @return 存在时返回 {@code true}
     */
    public boolean existsByName(TenantId tenantId, String name) {
        return departmentAdminJdbc.existsByName(tenantId, name);
    }

    /**
     * 插入部门。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param name 部门名
     * @param createdBy 创建人账号 ID
     * @param now 创建/更新时间
     */
    public void insertDepartment(TenantId tenantId, UUID departmentId, String name, UUID createdBy, Instant now) {
        departmentAdminJdbc.insertDepartment(tenantId, departmentId, name, createdBy, now);
    }

    /**
     * 重命名启用部门。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param name 新名称
     * @param now 更新时间
     * @return 受影响行数
     */
    public int updateName(TenantId tenantId, UUID departmentId, String name, Instant now) {
        return departmentAdminJdbc.updateName(tenantId, departmentId, name, now);
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
        return departmentAdminJdbc.updateStatus(tenantId, departmentId, status, disabledAt, now);
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
        return departmentAdminJdbc.updateManager(tenantId, departmentId, managerUserId, now);
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
        return departmentAdminJdbc.listMembers(tenantId, departmentId, limit, offset);
    }

    /**
     * 统计部门成员数（含停用账号）。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @return 成员数
     */
    public long countMembers(TenantId tenantId, UUID departmentId) {
        return departmentAdminJdbc.countMembers(tenantId, departmentId);
    }

    /**
     * 统计部门当前启用成员数。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @return 启用成员数
     */
    public long countActiveMembers(TenantId tenantId, UUID departmentId) {
        return departmentAdminJdbc.countActiveMembers(tenantId, departmentId);
    }

    /**
     * 查询目标租户账号归属与状态。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @return 账号快照
     */
    public Optional<DepartmentUserRecord> findUser(TenantId tenantId, UUID userId) {
        return departmentAdminJdbc.findUser(tenantId, userId);
    }

    /**
     * 把未分配部门的账号加入目标部门。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param userId 账号 ID
     * @param now 更新时间
     * @return 受影响行数
     */
    public int assignDepartment(TenantId tenantId, UUID departmentId, UUID userId, Instant now) {
        return departmentAdminJdbc.assignDepartment(tenantId, departmentId, userId, now);
    }

    /**
     * 把账号移出指定部门。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param userId 账号 ID
     * @param now 更新时间
     * @return 受影响行数
     */
    public int clearDepartment(TenantId tenantId, UUID departmentId, UUID userId, Instant now) {
        return departmentAdminJdbc.clearDepartment(tenantId, departmentId, userId, now);
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
        return departmentAdminJdbc.deleteCustomWorkspaceMembers(tenantId, departmentId, userId);
    }
}
