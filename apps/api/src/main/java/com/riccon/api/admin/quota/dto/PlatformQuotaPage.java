package com.riccon.api.admin.quota.dto;

import java.util.List;

/**
 * 平台租户配额分页，对应 OpenAPI {@code PlatformQuotaPage}。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 总行数
 * @param totalPages 总页数
 * @param items 当前页租户配额
 */
public record PlatformQuotaPage(int page, int size, long total, int totalPages, List<PlatformQuotaRow> items) {}
