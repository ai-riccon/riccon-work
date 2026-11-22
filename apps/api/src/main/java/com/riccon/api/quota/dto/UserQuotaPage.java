package com.riccon.api.quota.dto;

import java.util.List;

/**
 * 租户内用户配额分页，对应 OpenAPI {@code UserQuotaPage}。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 总行数
 * @param totalPages 总页数
 * @param items 当前页用户配额
 */
public record UserQuotaPage(int page, int size, long total, int totalPages, List<UserQuota> items) {}
