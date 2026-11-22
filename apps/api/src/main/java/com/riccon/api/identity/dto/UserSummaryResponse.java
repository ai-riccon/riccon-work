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
 * <p>{@code authSource} 与 {@code identityProvider} 由管理端账号查询/创建/绑定响应与会话摘要提供；旧调用方不传
 * 这两个值时保持向后兼容，序列化时省略字段（OpenAPI 中均为可选属性），避免把未知来源误报为 LOCAL 或伪造 IdP。
 *
 * <p>{@code identityProvider} 只承载脱敏展示名：OIDC 为配置的 provider 名或 issuer host；LOCAL 为 {@code null}。
 * 完整 issuer、subject、token 与邮箱绝不写入该字段。
 *
 * <p>{@code departmentId}/{@code departmentName} 是主部门展示字段（OpenAPI 中可空、未列入 required）：未分配部门时
 * 输出 {@code null}；部门停用后仍输出原名称，便于历史列表与审计可读。字段值来自查询时对 {@code department} 的
 * 同租户 join，不改变任何数据范围判断。
 *
 * @param id 账号 ID
 * @param username 登录名
 * @param displayName 展示名
 * @param email 联系方式，可为空
 * @param status 账号状态
 * @param roles 角色码
 * @param authSource 认证来源；为空表示调用方未提供
 * @param identityProvider 来源 IdP 脱敏展示名；为空表示 LOCAL 或调用方未提供
 * @param lastLoginAt 最近登录时间，可为空
 * @param createdAt 创建时间
 * @param departmentId 主部门 ID；未分配时为 {@code null}，序列化为 {@code null}
 * @param departmentName 主部门名；停用部门仍返回名称，未分配时为 {@code null}，序列化为 {@code null}
 */
public record UserSummaryResponse(
        UUID id,
        String username,
        String displayName,
        String email,
        UserStatus status,
        List<String> roles,
        @JsonInclude(JsonInclude.Include.NON_NULL) AuthSource authSource,
        @JsonInclude(JsonInclude.Include.NON_NULL) String identityProvider,
        Instant lastLoginAt,
        Instant createdAt,
        UUID departmentId,
        String departmentName) {

    /**
     * 兼容“已知认证来源、未知来源 IdP、未携带部门”的调用方。
     *
     * <p>保留 M02/M10 既有 10 参调用形状，部门字段按未分配（{@code null}）处理；新代码必须使用规范构造器。
     *
     * @param id 账号 ID
     * @param username 登录名
     * @param displayName 展示名
     * @param email 联系方式，可为空
     * @param status 账号状态
     * @param roles 角色码
     * @param authSource 认证来源
     * @param identityProvider 来源 IdP 脱敏展示名
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
            AuthSource authSource,
            String identityProvider,
            Instant lastLoginAt,
            Instant createdAt) {
        this(
                id,
                username,
                displayName,
                email,
                status,
                roles,
                authSource,
                identityProvider,
                lastLoginAt,
                createdAt,
                null,
                null);
    }

    /**
     * 兼容旧调用方（会话摘要早期版本）的构造器。
     *
     * <p>认证来源与来源 IdP 未知时写入 {@code null} 并由序列化省略；部门未分配时输出 {@code null}（OpenAPI 中为可空属性）。
     * 新代码必须使用规范构造器。
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
        this(id, username, displayName, email, status, roles, null, null, lastLoginAt, createdAt, null, null);
    }

    /**
     * 兼容“已知认证来源、未知来源 IdP”的调用方。
     *
     * <p>保留 M02/M10 既有 9 参调用形状，避免为补充 {@code identityProvider} 破坏外部调用点。
     *
     * @param id 账号 ID
     * @param username 登录名
     * @param displayName 展示名
     * @param email 联系方式，可为空
     * @param status 账号状态
     * @param roles 角色码
     * @param authSource 认证来源
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
            AuthSource authSource,
            Instant lastLoginAt,
            Instant createdAt) {
        this(id, username, displayName, email, status, roles, authSource, null, lastLoginAt, createdAt, null, null);
    }
}
