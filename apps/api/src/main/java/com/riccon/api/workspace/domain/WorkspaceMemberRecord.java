package com.riccon.api.workspace.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * CUSTOM 空间的显式成员快照。
 *
 * <p>只包含管理界面展示所需字段：成员行自带的加入时间与账号展示名；不复用 {@code UserSummary}，
 * 避免把邮箱、角色或最近登录时间暴露给部门负责人（ADR-0018 第 7.2 节）。
 *
 * @param userId 账号 ID
 * @param username 登录名
 * @param displayName 展示名
 * @param grantedAt 加入时间（UTC）
 */
public record WorkspaceMemberRecord(UUID userId, String username, String displayName, Instant grantedAt) {

    /** 校验必填字段，避免半成品行进入响应。 */
    public WorkspaceMemberRecord {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(grantedAt, "grantedAt must not be null");
    }
}
