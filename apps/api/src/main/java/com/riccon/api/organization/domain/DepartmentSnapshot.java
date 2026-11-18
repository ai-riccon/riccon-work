package com.riccon.api.organization.domain;

import com.riccon.platform.domain.organization.DepartmentStatus;
import java.util.Objects;
import java.util.UUID;

/**
 * 部门快照：组织目录返回的最小只读元数据。
 *
 * <p>不包含成员数、展示名或业务内容；成员数由 B2 的部门端点自行统计，避免每次可见性判定都做聚合查询。
 *
 * @param id 部门 ID
 * @param name 部门名
 * @param status 部门状态
 * @param managerUserId 负责人账号 ID；未任命时为 {@code null}
 */
public record DepartmentSnapshot(UUID id, String name, DepartmentStatus status, UUID managerUserId) {

    /** 校验必填字段，避免不完整快照进入可见性判定。 */
    public DepartmentSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    /**
     * 部门是否处于启用状态。
     *
     * <p>停用部门不授予空间可见性，也不能用于新建团队空间（ADR-0018 第 1.3/2.1 节）。
     *
     * @return 状态为 {@link DepartmentStatus#ACTIVE} 时返回 {@code true}
     */
    public boolean active() {
        return status == DepartmentStatus.ACTIVE;
    }
}
