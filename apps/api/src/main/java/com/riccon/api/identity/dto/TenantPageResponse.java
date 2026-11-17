package com.riccon.api.identity.dto;

import java.util.List;

/**
 * 租户分页响应。
 *
 * @param page 当前页，从 1 开始
 * @param size 每页大小
 * @param total 总行数
 * @param totalPages 总页数
 * @param items 当前页租户
 */
public record TenantPageResponse(int page, int size, long total, int totalPages, List<TenantSummaryResponse> items) {}
