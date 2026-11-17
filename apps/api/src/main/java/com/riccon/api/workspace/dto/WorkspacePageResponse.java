package com.riccon.api.workspace.dto;

import java.util.List;

/**
 * 工作空间分页响应，对应 OpenAPI {@code WorkspacePage}。
 *
 * @param page 当前页，从 1 开始
 * @param size 每页大小
 * @param total 总行数
 * @param totalPages 总页数
 * @param items 当前页工作空间
 */
public record WorkspacePageResponse(int page, int size, long total, int totalPages, List<WorkspaceResponse> items) {}
