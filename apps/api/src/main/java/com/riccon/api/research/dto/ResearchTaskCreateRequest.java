package com.riccon.api.research.dto;

import java.util.UUID;

/**
 * 创建报告任务请求，对应 OpenAPI {@code ResearchTaskCreateRequest}。
 *
 * <p>M02 只允许选择一个已授权、只读且要求审批的 MCP 工具；幂等键来自 {@code Idempotency-Key} 头，
 * 不放在请求体中，避免客户端把幂等键写进业务数据。
 *
 * @param topic 调研主题，4–500 字符
 * @param mcpToolId 已授权的 MCP 工具 ID
 */
public record ResearchTaskCreateRequest(String topic, UUID mcpToolId) {}
