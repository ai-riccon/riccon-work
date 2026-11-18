package com.riccon.api.mcp.dto;

import java.util.List;

/**
 * 已授权 MCP 工具分页响应，对应 OpenAPI {@code McpToolPage}。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 总行数
 * @param totalPages 总页数
 * @param items 当前页工具
 */
public record McpToolPageResponse(int page, int size, long total, int totalPages, List<McpToolResponse> items) {}
