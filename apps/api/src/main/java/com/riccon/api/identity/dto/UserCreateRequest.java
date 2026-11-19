package com.riccon.api.identity.dto;

import com.riccon.platform.domain.identity.AuthSource;
import java.util.List;

/**
 * 创建账号请求。
 *
 * <p>{@code authSource} 省略视为 {@link AuthSource#LOCAL}：LOCAL 必须提供 12–200 字符的
 * {@code password}；{@code authSource=OIDC} 必须提供 {@code issuer} 与 {@code subject}，且不得提供
 * {@code password}（ADR-0019 第 3 节、OpenAPI {@code UserCreateRequest}）。
 *
 * @param username 登录名，符合 {@code ^[A-Za-z0-9][A-Za-z0-9._-]{2,99}$}
 * @param displayName 展示名
 * @param authSource 认证来源；为空时按 LOCAL 处理
 * @param password 初始密码；OIDC 账号必须为空
 * @param issuer OIDC issuer；必须等于租户当前启用的 IdP issuer（规范化后）
 * @param subject IdP 的 subject（sub）；不按邮箱匹配
 * @param roles 角色码，至少 1 个
 */
public record UserCreateRequest(
        String username,
        String displayName,
        AuthSource authSource,
        String password,
        String issuer,
        String subject,
        List<String> roles) {}
