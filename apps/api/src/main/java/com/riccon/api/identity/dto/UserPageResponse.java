package com.riccon.api.identity.dto;

import java.util.List;

/**
 * 账号分页响应。
 *
 * @param page 当前页，从 1 开始
 * @param size 每页大小
 * @param total 总行数
 * @param totalPages 总页数
 * @param items 当前页账号
 */
public record UserPageResponse(int page, int size, long total, int totalPages, List<UserSummaryResponse> items) {}
