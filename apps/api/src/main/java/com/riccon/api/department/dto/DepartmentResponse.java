package com.riccon.api.department.dto;

import com.riccon.api.department.domain.DepartmentRecord;
import com.riccon.platform.domain.organization.DepartmentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 部门响应，对应 OpenAPI {@code Department}。
 *
 * @param id 部门 ID
 * @param name 部门名
 * @param status 部门状态
 * @param managerUserId 负责人账号 ID；未任命时为 {@code null}
 * @param managerDisplayName 负责人展示名；仅管理界面使用
 * @param memberCount 启用成员数；停用账号保留归属但不计数
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record DepartmentResponse(
        UUID id,
        String name,
        DepartmentStatus status,
        UUID managerUserId,
        String managerDisplayName,
        int memberCount,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 将持久化快照映射为响应。
     *
     * @param department 部门快照
     * @return 响应
     */
    public static DepartmentResponse from(DepartmentRecord department) {
        return new DepartmentResponse(
                department.id(),
                department.name(),
                department.status(),
                department.managerUserId(),
                department.managerDisplayName(),
                department.memberCount(),
                department.createdAt(),
                department.updatedAt());
    }
}
