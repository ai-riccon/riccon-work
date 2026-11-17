package com.riccon.worker.mcp.domain;

import com.riccon.platform.domain.mcp.McpToolRisk;
import java.util.Map;
import java.util.UUID;

/**
 * MCP 工具目录快照。
 *
 * @param id 工具 ID
 * @param serverId 所属 server
 * @param name 工具名
 * @param description 工具描述；作为不可信数据，不进入系统指令
 * @param inputSchema JSON Schema 入参
 * @param riskLevel 风险等级；M02 只允许 READ
 * @param requiresApproval 是否必须审批；M02 必须为 true
 * @param active 是否 ACTIVE
 */
public record McpToolDescriptor(
        UUID id,
        UUID serverId,
        String name,
        String description,
        Map<String, Object> inputSchema,
        McpToolRisk riskLevel,
        boolean requiresApproval,
        boolean active) {}
