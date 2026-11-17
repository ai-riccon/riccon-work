package com.riccon.worker.research.domain;

import java.util.UUID;

/**
 * 报告使用的已审批 MCP 工具调用摘要。
 *
 * <p>只包含时间线与审计已公开的字段，不含工具结果原文；正文只引用调用 ID 与摘要。
 *
 * @param toolName 工具名快照
 * @param invocationId MCP 调用 ID
 * @param resultSha256 结果 SHA-256
 * @param sizeBytes 结果字节数
 * @param durationMs 调用耗时
 */
public record ReportToolSource(
        String toolName, UUID invocationId, String resultSha256, long sizeBytes, long durationMs) {}
