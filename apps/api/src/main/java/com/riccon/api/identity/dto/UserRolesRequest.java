package com.riccon.api.identity.dto;

import java.util.List;

/**
 * 替换账号角色请求。
 *
 * @param roles 角色码，至少 1 个且不重复
 */
public record UserRolesRequest(List<String> roles) {}
