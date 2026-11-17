package com.riccon.api.workspace.domain;

import com.riccon.platform.domain.workspace.WorkspaceKind;
import com.riccon.platform.domain.workspace.WorkspaceStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 工作空间行快照。
 *
 * <p>只包含数据库列映射后的业务字段；个人空间的所有者用于可见性判断，团队空间对本租户员工可见。
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
public record WorkspaceRecord(
        UUID id,
        WorkspaceKind kind,
        String name,
        String description,
        UUID ownerUserId,
        WorkspaceStatus status,
        Instant createdAt,
        Instant updatedAt) {

    /** 校验必填字段，避免半成品行进入响应。 */
    public WorkspaceRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
