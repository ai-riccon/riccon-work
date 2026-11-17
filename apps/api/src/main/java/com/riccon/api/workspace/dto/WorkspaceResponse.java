package com.riccon.api.workspace.dto;

import com.riccon.api.workspace.domain.WorkspaceRecord;
import com.riccon.platform.domain.workspace.WorkspaceKind;
import com.riccon.platform.domain.workspace.WorkspaceStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 工作空间响应，对应 OpenAPI {@code Workspace}。
 *
 * @param id 工作空间 ID
 * @param kind 个人或团队
 * @param name 名称
 * @param description 说明，可为空
 * @param ownerUserId 所有者账号 ID
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
                workspace.status(),
                workspace.createdAt(),
                workspace.updatedAt());
    }
}
