package com.riccon.api.knowledge.connector.dto;

import java.util.List;

/**
 * 连接器条目分页响应（OpenAPI {@code ConnectorItemPage}）。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 总数
 * @param totalPages 总页数
 * @param items 条目列表
 */
public record ConnectorItemPageResponse(
        int page, int size, long total, int totalPages, List<ConnectorItemResponse> items) {}
