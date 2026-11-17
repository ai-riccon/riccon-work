package com.riccon.api.identity.dto;

import com.riccon.platform.domain.identity.UserStatus;

/**
 * 启用/禁用账号请求。
 *
 * @param status 目标状态
 */
public record UserStatusRequest(UserStatus status) {}
