package com.riccon.api.organization.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 部门成员快照。
 *
 * <p>只含组织展示所需字段，不复用 {@code UserSummary}，避免把邮箱、角色与最近登录时间暴露给部门负责人
 * （ADR-0018 第 7.2 节）。
 *
 * @param userId 账号 ID
 * @param username 登录名
 * @param displayName 展示名
 */
public record DepartmentMemberSnapshot(UUID userId, String username, String displayName) {

    /** 校验必填字段。 */
    public DepartmentMemberSnapshot {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
    }
}
