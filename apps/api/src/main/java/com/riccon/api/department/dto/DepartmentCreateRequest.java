package com.riccon.api.department.dto;

/**
 * 创建部门请求，对应 OpenAPI {@code DepartmentCreateRequest}。
 *
 * @param name 部门名，1–100 字符
 */
public record DepartmentCreateRequest(String name) {}
