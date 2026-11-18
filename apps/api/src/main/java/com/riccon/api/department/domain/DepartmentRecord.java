package com.riccon.api.department.domain;

import com.riccon.platform.domain.organization.DepartmentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 部门持久化快照。
 *
 * <p>对应 OpenAPI {@code Department} 的全部字段：{@code memberCount} 只统计 {@code status='ACTIVE'} 的账号
 * （ADR-0018 第 1.3 节），{@code managerDisplayName} 只用于管理界面展示，不进入审计 details。
 *
 * @param id 部门 ID
 * @param name 部门名
 * @param status 部门状态
 * @param managerUserId 负责人账号 ID；未任命时为 {@code null}
 * @param managerDisplayName 负责人展示名；未任命或账号缺失时为 {@code null}
 * @param memberCount 启用成员数
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record DepartmentRecord(
        UUID id,
        String name,
        DepartmentStatus status,
        UUID managerUserId,
        String managerDisplayName,
        int memberCount,
        Instant createdAt,
        Instant updatedAt) {}
