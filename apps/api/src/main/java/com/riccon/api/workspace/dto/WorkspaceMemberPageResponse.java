package com.riccon.api.workspace.dto;

import java.util.List;

/**
 * CUSTOM 空间显式成员分页响应，对应 OpenAPI {@code WorkspaceMemberPage}。
 *
 * @param page 当前页，从 1 开始
 * @param size 每页大小
 * @param total 总行数
 * @param totalPages 总页数
 * @param items 当前页成员
 */
public record WorkspaceMemberPageResponse(
        int page, int size, long total, int totalPages, List<WorkspaceMemberResponse> items) {}
