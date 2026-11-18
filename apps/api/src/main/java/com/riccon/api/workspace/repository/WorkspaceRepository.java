package com.riccon.api.workspace.repository;

import com.riccon.api.workspace.domain.WorkspaceMemberRecord;
import com.riccon.api.workspace.domain.WorkspaceRecord;
import com.riccon.api.workspace.persistence.WorkspaceJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workspace.WorkspaceKind;
import com.riccon.platform.domain.workspace.WorkspaceVisibility;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 工作空间仓储。
 *
 * <p>只转调 {@link WorkspaceJdbc}；可见性 SQL、部门连接与成员行操作都留在持久化层，业务代码不拼接 SQL
 * （ADR-0018 第 3.1 节）。
 */
@Repository
public class WorkspaceRepository {

    /** 工作空间 SQL。 */
    private final WorkspaceJdbc workspaceJdbc;

    /**
     * 创建仓储。
     *
     * @param workspaceJdbc 工作空间 SQL
     */
    public WorkspaceRepository(WorkspaceJdbc workspaceJdbc) {
        this.workspaceJdbc = Objects.requireNonNull(workspaceJdbc, "workspaceJdbc must not be null");
    }

    /**
     * 查询用户的个人空间。
     *
     * @param tenantId 当前租户
     * @param userId 账号 ID
     * @return 个人空间
     */
    public Optional<WorkspaceRecord> findPersonal(TenantId tenantId, UUID userId) {
        return workspaceJdbc.findPersonal(tenantId, userId);
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
        workspaceJdbc.insert(tenantId, personalOwnerUserId, workspace, createdBy);
    }

    /**
     * 按 ID 读取工作空间，不做可见性过滤。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @return 工作空间
     */
    public Optional<WorkspaceRecord> findById(TenantId tenantId, UUID workspaceId) {
        return workspaceJdbc.findById(tenantId, workspaceId);
    }

    /**
     * 查询对指定用户可见的工作空间。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param userId 账号 ID
     * @return 可见工作空间
     */
    public Optional<WorkspaceRecord> findAccessible(TenantId tenantId, UUID workspaceId, UUID userId) {
        return workspaceJdbc.findAccessible(tenantId, workspaceId, userId);
    }

    /**
     * 统计可见工作空间。
     *
     * @param tenantId 当前租户
     * @param userId 账号 ID
     * @param kind 可选类型过滤
     * @return 总数
     */
    public long countAccessible(TenantId tenantId, UUID userId, WorkspaceKind kind) {
        return workspaceJdbc.countAccessible(tenantId, userId, kind);
    }

    /**
     * 分页查询可见工作空间。
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
        return workspaceJdbc.listAccessible(tenantId, userId, kind, limit, offset);
    }

    /**
     * 分页列出部门内空间，供管理界面使用。
     *
     * @param tenantId 当前租户
     * @param departmentId 部门 ID
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 空间元数据
     */
    public List<WorkspaceRecord> listByDepartment(TenantId tenantId, UUID departmentId, int limit, int offset) {
        return workspaceJdbc.listByDepartment(tenantId, departmentId, limit, offset);
    }

    /**
     * 统计部门内空间。
     *
     * @param tenantId 当前租户
     * @param departmentId 部门 ID
     * @return 空间数
     */
    public long countByDepartment(TenantId tenantId, UUID departmentId) {
        return workspaceJdbc.countByDepartment(tenantId, departmentId);
    }

    /**
     * 更新名称与说明。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param name 新名称
     * @param description 新说明
     * @param updatedAt 更新时间
     * @return 受影响行数
     */
    public int updateMetadata(TenantId tenantId, UUID workspaceId, String name, String description, Instant updatedAt) {
        return workspaceJdbc.updateMetadata(tenantId, workspaceId, name, description, updatedAt);
    }

    /**
     * 更新可见性。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param visibility 新可见性
     * @param updatedAt 更新时间
     * @return 受影响行数
     */
    public int updateVisibility(
            TenantId tenantId, UUID workspaceId, WorkspaceVisibility visibility, Instant updatedAt) {
        return workspaceJdbc.updateVisibility(tenantId, workspaceId, visibility, updatedAt);
    }

    /**
     * 统计显式成员。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @return 成员数
     */
    public long countMembers(TenantId tenantId, UUID workspaceId) {
        return workspaceJdbc.countMembers(tenantId, workspaceId);
    }

    /**
     * 分页列出显式成员。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 成员列表
     */
    public List<WorkspaceMemberRecord> listMembers(TenantId tenantId, UUID workspaceId, int limit, int offset) {
        return workspaceJdbc.listMembers(tenantId, workspaceId, limit, offset);
    }

    /**
     * 判断显式成员是否存在。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param userId 账号 ID
     * @return 存在时返回 {@code true}
     */
    public boolean memberExists(TenantId tenantId, UUID workspaceId, UUID userId) {
        return workspaceJdbc.memberExists(tenantId, workspaceId, userId);
    }

    /**
     * 加入显式成员。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param userId 账号 ID
     * @param grantedBy 操作人账号 ID
     * @param grantedAt 加入时间
     */
    public void insertMember(TenantId tenantId, UUID workspaceId, UUID userId, UUID grantedBy, Instant grantedAt) {
        workspaceJdbc.insertMember(tenantId, workspaceId, userId, grantedBy, grantedAt);
    }

    /**
     * 移出显式成员。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param userId 账号 ID
     * @return 删除行数
     */
    public int deleteMember(TenantId tenantId, UUID workspaceId, UUID userId) {
        return workspaceJdbc.deleteMember(tenantId, workspaceId, userId);
    }

    /**
     * 清空空间显式成员。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @return 删除行数
     */
    public int deleteAllMembers(TenantId tenantId, UUID workspaceId) {
        return workspaceJdbc.deleteAllMembers(tenantId, workspaceId);
    }
}
