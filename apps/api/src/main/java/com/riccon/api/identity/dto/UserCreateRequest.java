package com.riccon.api.identity.dto;

import java.util.List;

/**
 * 创建账号请求。
 *
 * @param username 登录名，符合 {@code ^[A-Za-z0-9][A-Za-z0-9._-]{2,99}$}
 * @param displayName 展示名
 * @param password 初始密码，至少 12 位
 * @param roles 角色码，至少 1 个
 */
public record UserCreateRequest(String username, String displayName, String password, List<String> roles) {}
