package com.riccon.api.knowledge.connector.dto;

import java.util.List;

/**
 * 连接器分页响应（OpenAPI {@code ConnectorPage}）。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 总数
 * @param totalPages 总页数
 * @param items 连接器列表
 */
public record ConnectorPageResponse(int page, int size, long total, int totalPages, List<ConnectorResponse> items) {}
