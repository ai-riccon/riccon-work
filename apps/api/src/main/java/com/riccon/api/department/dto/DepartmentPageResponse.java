package com.riccon.api.department.dto;

import java.util.List;

/**
 * 部门分页响应，对应 OpenAPI {@code DepartmentPage}。
 *
 * @param page 当前页，从 1 开始
 * @param size 每页大小
 * @param total 总行数
 * @param totalPages 总页数
 * @param items 当前页部门
 */
public record DepartmentPageResponse(int page, int size, long total, int totalPages, List<DepartmentResponse> items) {}
