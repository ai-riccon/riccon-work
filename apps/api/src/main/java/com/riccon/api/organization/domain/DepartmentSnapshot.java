package com.riccon.api.organization.domain;

import com.riccon.platform.domain.organization.DepartmentStatus;
import java.util.UUID;

/**
 * 部门快照：组织目录返回的最小只读元数据。
 *
 * <p>字段直接映射 {@code department} 表的非空列；不包含成员数、展示名或业务内容，成员数由 B2 的部门端点
 * 自行统计，避免每次可见性判定都做聚合查询。{@code managerUserId} 在未任命负责人时为 {@code null}。
 *
 * @param id 部门 ID
 * @param name 部门名
 * @param status 部门状态
 * @param managerUserId 负责人账号 ID；未任命时为 {@code null}
 */
public record DepartmentSnapshot(UUID id, String name, DepartmentStatus status, UUID managerUserId) {}
