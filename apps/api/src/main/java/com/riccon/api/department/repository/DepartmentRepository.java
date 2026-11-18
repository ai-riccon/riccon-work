package com.riccon.api.department.repository;

import com.riccon.api.department.domain.DepartmentMemberRecord;
import com.riccon.api.department.domain.DepartmentRecord;
import com.riccon.api.department.persistence.DepartmentJdbc;
import com.riccon.api.organization.OrganizationDirectory;
import com.riccon.api.organization.domain.DepartmentMemberSnapshot;
import com.riccon.api.organization.domain.DepartmentSnapshot;
import com.riccon.platform.common.tenant.TenantId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 租户内部门读取仓储。
 *
 * <p>实现 B1a 定义的 {@link OrganizationDirectory}，供 B3 的空间可见性与管理能力判定读取部门、负责人和成员
 * 关系；同时为租户端只读部门接口提供分页数据。所有方法显式传入 {@link TenantId}，不依赖 workspace/chat/file
 * 包，也不返回邮箱、角色或最近登录（ADR-0018 第 2.3/7.2 节）。
 */
@Repository
public class DepartmentRepository implements OrganizationDirectory {

    /** 部门读取 SQL。 */
    private final DepartmentJdbc departmentJdbc;

    /**
     * 创建部门读取仓储。
     *
     * @param departmentJdbc 部门读取 SQL
     */
    public DepartmentRepository(DepartmentJdbc departmentJdbc) {
        this.departmentJdbc = Objects.requireNonNull(departmentJdbc, "departmentJdbc must not be null");
    }

    /**
     * 分页列出本租户部门。
     *
     * @param tenantId 租户
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 部门快照
     */
    public List<DepartmentRecord> listDepartments(TenantId tenantId, int limit, int offset) {
        return departmentJdbc.listDepartments(tenantId, limit, offset);
    }

    /**
     * 统计本租户部门数。
     *
     * @param tenantId 租户
     * @return 部门数
     */
    public long countDepartments(TenantId tenantId) {
        return departmentJdbc.countDepartments(tenantId);
    }

    /**
     * 查询账号当前主部门。
     *
     * @param tenantId 租户
     * @param userId 账号 ID
     * @return 部门快照；账号停用或未分配部门时为空
     */
    public Optional<DepartmentRecord> findDepartmentRecordOfUser(TenantId tenantId, UUID userId) {
        return departmentJdbc.findDepartmentOfUser(tenantId, userId);
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
        return departmentJdbc.listMembers(tenantId, departmentId, limit, offset);
    }

    /**
     * 统计部门成员数（含停用账号）。
     *
     * @param tenantId 租户
     * @param departmentId 部门 ID
     * @return 成员数
     */
    public long countMembers(TenantId tenantId, UUID departmentId) {
        return departmentJdbc.countMembers(tenantId, departmentId);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<DepartmentSnapshot> findDepartment(TenantId tenantId, UUID departmentId) {
        return departmentJdbc.findDepartment(tenantId, departmentId).map(DepartmentJdbc::toSnapshot);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<DepartmentSnapshot> findDepartmentOfUser(TenantId tenantId, UUID userId) {
        return departmentJdbc.findDepartmentOfUser(tenantId, userId).map(DepartmentJdbc::toSnapshot);
    }

    /** {@inheritDoc} */
    @Override
    public List<DepartmentMemberSnapshot> listActiveMembers(TenantId tenantId, UUID departmentId) {
        return departmentJdbc.listActiveMembers(tenantId, departmentId);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isActiveMember(TenantId tenantId, UUID departmentId, UUID userId) {
        return departmentJdbc.isActiveMember(tenantId, departmentId, userId);
    }
}
