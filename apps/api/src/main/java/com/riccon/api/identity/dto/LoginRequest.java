package com.riccon.api.identity.dto;

/**
 * 登录请求。
 *
 * @param tenantCode 租户代码（{@code tenant.id}）
 * @param username 登录名
 * @param password 明文密码；只在请求生命周期内存在
 */
public record LoginRequest(String tenantCode, String username, String password) {}
