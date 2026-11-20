package com.riccon.api.model.catalog.dto;

import java.util.List;

/**
 * 员工模型分页（OpenAPI {@code ModelPage}）。
 *
 * @param page 当前页，从 1 开始
 * @param size 每页大小
 * @param total 可路由模型总数
 * @param totalPages 总页数
 * @param items 当前页模型
 */
public record EmployeeModelPageResponse(
        int page, int size, long total, int totalPages, List<EmployeeModelResponse> items) {}
