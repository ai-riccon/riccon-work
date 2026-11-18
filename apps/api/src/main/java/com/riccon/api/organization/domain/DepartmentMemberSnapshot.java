package com.riccon.api.organization.domain;

import java.util.UUID;

/**
 * 部门成员快照。
 *
 * <p>只含组织展示所需字段，字段直接映射 {@code app_user} 的非空列；不复用 {@code UserSummary}，
 * 避免把邮箱、角色与最近登录时间暴露给部门负责人（ADR-0018 第 7.2 节）。
 *
 * @param userId 账号 ID
 * @param username 登录名
 * @param displayName 展示名
 */
public record DepartmentMemberSnapshot(UUID userId, String username, String displayName) {}
