package com.riccon.api.department.domain;

import com.riccon.platform.domain.identity.UserStatus;
import java.util.UUID;

/**
 * 部门成员变更所需的账号快照。
 *
 * <p>只读取目标租户下的账号归属与启用状态，用于单主部门校验；不读取邮箱、角色或业务内容
 * （ADR-0018 第 1.2/1.4 节）。
 *
 * @param id 账号 ID
 * @param username 登录名
 * @param displayName 展示名
 * @param status 账号状态
 * @param departmentId 当前主部门；未分配时为 {@code null}
 */
public record DepartmentUserRecord(
        UUID id, String username, String displayName, UserStatus status, UUID departmentId) {}
