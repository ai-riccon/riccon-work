package com.riccon.worker.mcp.domain;

/**
 * MCP 调用结果（只含脱敏摘要与供模型使用的文本）。
 *
 * <p>文本会作为不可信工具结果返回给模型；方法返回后不写日志、不写审计原文。
 *
 * @param text 工具返回文本
 * @param resultSha256 结果 SHA-256
 * @param sizeBytes UTF-8 字节数
 * @param durationMs 调用耗时
 */
public record McpCallResult(String text, String resultSha256, long sizeBytes, long durationMs) {}
