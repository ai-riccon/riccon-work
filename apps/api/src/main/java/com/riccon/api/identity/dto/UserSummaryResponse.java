package com.riccon.api.identity.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.riccon.platform.domain.identity.AuthSource;
import com.riccon.platform.domain.identity.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 账号摘要，对应 OpenAPI {@code UserSummary}。
 *
 * <p>{@code authSource} 由管理端账号查询/创建/绑定响应提供；会话摘要的旧调用方不传该值时保持向后兼容，
 * 序列化时省略该字段（OpenAPI 中 {@code authSource} 为可选属性），避免把未知来源误报为 LOCAL。
 *
 * @param id 账号 ID
 * @param username 登录名
 * @param displayName 展示名
 * @param email 联系方式，可为空
 * @param status 账号状态
 * @param roles 角色码
 * @param authSource 认证来源；为空表示调用方未提供
 * @param lastLoginAt 最近登录时间，可为空
 * @param createdAt 创建时间
 */
public record UserSummaryResponse(
        UUID id,
        String username,
        String displayName,
        String email,
        UserStatus status,
        List<String> roles,
        @JsonInclude(JsonInclude.Include.NON_NULL) AuthSource authSource,
        Instant lastLoginAt,
        Instant createdAt) {

    /**
     * 兼容旧调用方（会话摘要）的构造器。
     *
     * <p>认证来源未知时写入 {@code null} 并由序列化省略；管理端路径必须使用规范构造器传入真实来源。
     *
     * @param id 账号 ID
     * @param username 登录名
     * @param displayName 展示名
     * @param email 联系方式，可为空
     * @param status 账号状态
     * @param roles 角色码
     * @param lastLoginAt 最近登录时间，可为空
     * @param createdAt 创建时间
     */
    public UserSummaryResponse(
            UUID id,
            String username,
            String displayName,
            String email,
            UserStatus status,
            List<String> roles,
            Instant lastLoginAt,
            Instant createdAt) {
        this(id, username, displayName, email, status, roles, null, lastLoginAt, createdAt);
    }
}
