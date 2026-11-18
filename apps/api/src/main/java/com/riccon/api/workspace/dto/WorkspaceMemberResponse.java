package com.riccon.api.workspace.dto;

import com.riccon.api.workspace.domain.WorkspaceMemberRecord;
import java.time.Instant;
import java.util.UUID;

/**
 * CUSTOM 空间显式成员响应，对应 OpenAPI {@code WorkspaceMember}。
 *
 * <p>只含管理界面展示字段，不含邮箱、角色、最近登录或部门信息（ADR-0018 第 7.2 节）。
 *
 * @param userId 账号 ID
 * @param username 登录名
 * @param displayName 展示名
 * @param grantedAt 加入时间
 */
public record WorkspaceMemberResponse(UUID userId, String username, String displayName, Instant grantedAt) {

    /**
     * 将持久化快照映射为响应。
     *
     * @param member 成员快照
     * @return 响应
     */
    public static WorkspaceMemberResponse from(WorkspaceMemberRecord member) {
        return new WorkspaceMemberResponse(
                member.userId(), member.username(), member.displayName(), member.grantedAt());
    }
}
