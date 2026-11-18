package com.riccon.api.organization;

import com.riccon.api.organization.domain.DepartmentMemberSnapshot;
import com.riccon.api.organization.domain.DepartmentSnapshot;
import com.riccon.platform.common.tenant.TenantId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 组织目录只读接口。
 *
 * <p>B2 的部门仓储实现本接口，B3 的空间可见性与管理能力判定通过它读取部门、负责人和成员关系。
 * 接口只暴露组织元数据，不依赖也不返回会话、文件、报告、产物或知识库内容，避免把部门读取扩成业务内容读取
 * （ADR-0018 第 1.4/2.3 节）。所有方法都显式传入 {@link TenantId}，调用方不得依赖线程上下文隐式切换租户。
 */
public interface OrganizationDirectory {

    /**
     * 查询租户内部门。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @return 部门快照；不存在或不属于该租户时为空
     */
    Optional<DepartmentSnapshot> findDepartment(TenantId tenantId, UUID departmentId);

    /**
     * 查询账号当前的主部门。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @return 账号当前主部门；账号不存在、已停用或未分配部门时为空
     */
    Optional<DepartmentSnapshot> findDepartmentOfUser(TenantId tenantId, UUID userId);

    /**
     * 列出部门的当前启用成员，按展示名、账号 ID 升序排列。
     *
     * <p>只返回组织展示所需的最小字段，不含邮箱、角色与最近登录时间（ADR-0018 第 7.2 节）；
     * 停用账号保留部门归属但不计入成员列表。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @return 成员快照；部门不存在时返回空列表
     */
    List<DepartmentMemberSnapshot> listActiveMembers(TenantId tenantId, UUID departmentId);

    /**
     * 判断账号是否为部门的当前启用成员。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param userId 账号 ID
     * @return 账号启用、部门启用且主部门为该部门时返回 {@code true}
     */
    boolean isActiveMember(TenantId tenantId, UUID departmentId, UUID userId);
}
