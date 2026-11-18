package com.riccon.api.department.dto;

import com.riccon.api.department.domain.DepartmentMemberRecord;
import com.riccon.platform.domain.identity.UserStatus;
import java.util.UUID;

/**
 * 部门成员响应，对应 OpenAPI {@code DepartmentMember}。
 *
 * <p>只含账号展示字段；不复用 {@code UserSummary}，避免把邮箱、角色与最近登录时间暴露给部门负责人
 * （ADR-0018 第 7.2 节）。
 *
 * @param id 账号 ID
 * @param username 登录名
 * @param displayName 展示名
 * @param status 账号状态
 */
public record DepartmentMemberResponse(UUID id, String username, String displayName, UserStatus status) {

    /**
     * 将持久化快照映射为响应。
     *
     * @param member 成员快照
     * @return 响应
     */
    public static DepartmentMemberResponse from(DepartmentMemberRecord member) {
        return new DepartmentMemberResponse(member.id(), member.username(), member.displayName(), member.status());
    }
}
