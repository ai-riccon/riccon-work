package com.riccon.worker.mcp.domain;

import java.util.UUID;

/**
 * MCP 调用记录插入参数。
 *
 * @param id 调用 ID
 * @param runId 运行 ID
 * @param researchTaskId 报告任务 ID，可为空
 * @param approvalId 审批 ID，可为空
 * @param toolId 工具 ID
 * @param toolName 工具名
 * @param paramsSha256 入参摘要
 * @param paramsPreview 脱敏预览 JSON
 */
public record McpInvocationRecord(
        UUID id,
        UUID runId,
        UUID researchTaskId,
        UUID approvalId,
        UUID toolId,
        String toolName,
        String paramsSha256,
        String paramsPreview) {}
