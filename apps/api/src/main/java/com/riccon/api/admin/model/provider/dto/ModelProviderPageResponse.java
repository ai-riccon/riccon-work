package com.riccon.api.admin.model.provider.dto;

import java.util.List;

/**
 * 平台供应商分页（OpenAPI {@code ModelProviderPage}）。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 总行数
 * @param totalPages 总页数
 * @param items 当前页供应商
 */
public record ModelProviderPageResponse(
        int page, int size, long total, int totalPages, List<ModelProviderResponse> items) {}
