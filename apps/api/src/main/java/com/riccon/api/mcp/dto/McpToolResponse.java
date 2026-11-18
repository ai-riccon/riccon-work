package com.riccon.api.mcp.dto;

import com.riccon.api.mcp.domain.McpToolSnapshot;
import com.riccon.platform.domain.mcp.McpToolRisk;
import java.util.UUID;

/**
 * 已授权 MCP 工具响应，对应 OpenAPI {@code McpTool}。
 *
 * <p>只输出目录展示字段；工具入参 schema、server 端点与凭据不返回给前端。前端据此渲染工具下拉并提交
 * {@code mcpToolId} 创建报告任务。
 *
 * @param id 工具 ID
 * @param serverId 所属 MCP server ID
 * @param name 工具名
 * @param description 工具说明
 * @param riskLevel 风险级别
 * @param requiresApproval 是否需要人工审批
 * @param status 工具状态
 */
public record McpToolResponse(
        UUID id,
        UUID serverId,
        String name,
        String description,
        McpToolRisk riskLevel,
        boolean requiresApproval,
        String status) {

    /**
     * 将工具快照映射为响应。
     *
     * @param tool 工具快照
     * @return 响应
     */
    public static McpToolResponse from(McpToolSnapshot tool) {
        return new McpToolResponse(
                tool.id(),
                tool.serverId(),
                tool.name(),
                tool.description(),
                tool.riskLevel(),
                tool.requiresApproval(),
                tool.status());
    }
}
