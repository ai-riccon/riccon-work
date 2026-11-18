package com.riccon.api.department.dto;

import java.util.UUID;

/**
 * 任命/移交部门负责人请求，对应 OpenAPI {@code DepartmentManagerRequest}。
 *
 * @param userId 目标负责人账号 ID；必须为本部门当前启用成员
 */
public record DepartmentManagerRequest(UUID userId) {}
