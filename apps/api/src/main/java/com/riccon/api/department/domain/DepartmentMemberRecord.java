package com.riccon.api.department.domain;

import com.riccon.platform.domain.identity.UserStatus;
import java.util.UUID;

/**
 * 部门成员持久化快照。
 *
 * <p>只含 OpenAPI {@code DepartmentMember} 的展示字段，不含邮箱、角色与最近登录时间（ADR-0018 第 7.2 节）。
 * 列表包含停用账号以便管理员清理归属，但 {@code memberCount} 只统计启用账号。
 *
 * @param id 账号 ID
 * @param username 登录名
 * @param displayName 展示名
 * @param status 账号状态
 */
public record DepartmentMemberRecord(UUID id, String username, String displayName, UserStatus status) {}
