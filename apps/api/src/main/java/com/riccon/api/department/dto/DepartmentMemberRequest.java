package com.riccon.api.department.dto;

import java.util.UUID;

/**
 * 加入部门成员请求，对应 OpenAPI {@code DepartmentMemberRequest}。
 *
 * @param userId 目标租户内账号 ID
 */
public record DepartmentMemberRequest(UUID userId) {}
