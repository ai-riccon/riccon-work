package com.riccon.api.workspace.repository;

import com.riccon.api.workspace.domain.WorkspaceRecord;
import com.riccon.api.workspace.persistence.WorkspaceJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workspace.WorkspaceKind;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 工作空间仓储。
 *
 * <p>只做持久化编排；可见性规则与个人空间自动创建由 {@code WorkspaceService} 决定。
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
}
