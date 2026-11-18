package com.riccon.api.workspace.dto;

import java.util.UUID;

/**
 * 加入 CUSTOM 空间显式成员请求，对应 OpenAPI {@code WorkspaceMemberAddRequest}。
 *
 * @param userId 目标本部门启用成员账号 ID
 */
public record WorkspaceMemberAddRequest(UUID userId) {}
