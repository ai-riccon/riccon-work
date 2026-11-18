package com.riccon.api.department.dto;

import java.util.List;

/**
 * 部门成员分页响应，对应 OpenAPI {@code DepartmentMemberPage}。
 *
 * @param page 当前页，从 1 开始
 * @param size 每页大小
 * @param total 总行数
 * @param totalPages 总页数
 * @param items 当前页成员
 */
public record DepartmentMemberPageResponse(
        int page, int size, long total, int totalPages, List<DepartmentMemberResponse> items) {}
