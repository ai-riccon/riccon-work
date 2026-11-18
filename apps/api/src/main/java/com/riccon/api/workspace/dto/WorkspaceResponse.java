package com.riccon.api.workspace.dto;

import com.riccon.api.workspace.domain.WorkspaceRecord;
import com.riccon.platform.domain.workspace.WorkspaceKind;
import com.riccon.platform.domain.workspace.WorkspaceStatus;
import com.riccon.platform.domain.workspace.WorkspaceVisibility;
import java.time.Instant;
import java.util.UUID;

/**
 * 工作空间响应，对应 OpenAPI {@code Workspace}。
 *
 * <p>M03 起返回部门归属与可见性；{@code visibility} 对 M02 旧代码写入的 {@code NULL} 行按类型降级为
 * {@code PRIVATE}/{@code TENANT}，保证响应字段必填（ADR-0018 第 7.2 节）。
 *
 * @param id 工作空间 ID
 * @param kind 个人或团队
 * @param name 名称
 * @param description 说明，可为空
 * @param ownerUserId 所有者账号 ID
 * @param departmentId 所属部门；可空
 * @param departmentName 所属部门名；可空
 * @param visibility 可见范围
 * @param status 状态
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record WorkspaceResponse(
        UUID id,
        WorkspaceKind kind,
        String name,
        String description,
        UUID ownerUserId,
        UUID departmentId,
        String departmentName,
        WorkspaceVisibility visibility,
        WorkspaceStatus status,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 将持久化快照映射为响应。
     *
     * @param workspace 工作空间快照
     * @return 响应
     */
    public static WorkspaceResponse from(WorkspaceRecord workspace) {
        return new WorkspaceResponse(
                workspace.id(),
                workspace.kind(),
                workspace.name(),
                workspace.description(),
                workspace.ownerUserId(),
                workspace.departmentId(),
                workspace.departmentName(),
                workspace.effectiveVisibility(),
                workspace.status(),
                workspace.createdAt(),
                workspace.updatedAt());
    }
}
