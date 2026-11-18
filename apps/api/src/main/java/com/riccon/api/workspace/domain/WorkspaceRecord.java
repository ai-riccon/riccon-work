package com.riccon.api.workspace.domain;

import com.riccon.platform.domain.workspace.WorkspaceKind;
import com.riccon.platform.domain.workspace.WorkspaceStatus;
import com.riccon.platform.domain.workspace.WorkspaceVisibility;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 工作空间行快照。
 *
 * <p>只包含数据库列映射后的业务字段。个人空间固定 {@link WorkspaceVisibility#PRIVATE} 且没有部门；
 * 团队空间的可见性由 {@link WorkspaceVisibility} 表达，M03 之后新建团队空间必须有部门
 * （ADR-0018 第 2.1 节）。{@code visibility} 允许为 {@code null}，只用于兼容 M02 旧代码在滚动发布
 * 期间写入的行；B1b 收紧 V9 前这些行由 {@link #effectiveVisibility()} 按类型降级处理。
 *
 * @param id 工作空间 ID
 * @param kind 个人或团队
 * @param name 名称
 * @param description 说明，可为空
 * @param ownerUserId 所有者账号 ID
 * @param departmentId 所属部门；个人空间与历史 {@code TENANT} 团队空间为空
 * @param departmentName 所属部门名，仅用于展示；可空
 * @param visibility 可见范围；M02 旧代码写入的行可能为空
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
        UUID departmentId,
        String departmentName,
        WorkspaceVisibility visibility,
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

    /**
     * 返回对外的有效可见性。
     *
     * <p>M02 旧代码在 V9 收紧前仍可能写入 {@code visibility = NULL}：个人空间按 {@code PRIVATE} 解释，
     * 团队空间按历史 {@code TENANT} 解释。该降级只影响响应映射；查询侧对 {@code NULL} 团队空间一律不可见，
     * 避免在回填前放大可见面（ADR-0018 第 6.2 节）。
     *
     * @return 有效可见性
     */
    public WorkspaceVisibility effectiveVisibility() {
        if (visibility != null) {
            return visibility;
        }
        return kind == WorkspaceKind.PERSONAL ? WorkspaceVisibility.PRIVATE : WorkspaceVisibility.TENANT;
    }
}
