package com.riccon.api.department.dto;

/**
 * 重命名部门请求，对应 OpenAPI {@code DepartmentUpdateRequest}。
 *
 * @param name 新部门名，1–100 字符
 */
public record DepartmentUpdateRequest(String name) {}
