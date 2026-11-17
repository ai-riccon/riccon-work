package com.riccon.worker.mcp.domain;

import java.util.UUID;

/**
 * MCP 调用结果（只含脱敏摘要与供模型使用的文本）。
 *
 * <p>文本会作为不可信工具结果返回给模型；方法返回后不写日志、不写审计原文。
 * {@code invocationId} 对应 {@code mcp_invocation.id}，用于阶段时间线对齐。
 *
 * @param invocationId MCP 调用 ID
 * @param text 工具返回文本
 * @param resultSha256 结果 SHA-256
 * @param sizeBytes UTF-8 字节数
 * @param durationMs 调用耗时
 */
public record McpCallResult(UUID invocationId, String text, String resultSha256, long sizeBytes, long durationMs) {}
