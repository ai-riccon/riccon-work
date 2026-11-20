package com.riccon.api.admin.model.catalog.dto;

import java.util.List;

/**
 * 平台模型分页（OpenAPI {@code AdminModelPage}）。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 总行数
 * @param totalPages 总页数
 * @param items 当前页模型
 */
public record AdminModelPageResponse(int page, int size, long total, int totalPages, List<AdminModelResponse> items) {}
