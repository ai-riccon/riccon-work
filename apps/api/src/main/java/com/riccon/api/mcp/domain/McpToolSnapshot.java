package com.riccon.api.mcp.domain;

import com.riccon.platform.domain.mcp.McpToolRisk;
import java.util.Objects;
import java.util.UUID;

/**
 * 租户已授权 MCP 工具快照。
 *
 * <p>只承载目录展示所需字段：工具的全局 ID、所属平台 server、展示名、说明、风险级别、审批要求与状态。
 * 工具入参 schema 与凭据不进入本快照；J2 创建任务只引用工具 ID，完整参数由 Worker 在审批前生成脱敏预览。
 *
 * @param id 工具 ID
 * @param serverId 所属 MCP server ID
 * @param name 工具名，审批展示使用原始值
 * @param description 工具说明；视为不可信数据，不进入系统指令
 * @param riskLevel 风险级别；M02 只允许 READ
 * @param requiresApproval 是否每次调用都需要人工审批；M02 固定为 true
 * @param status 工具状态，对应 {@code mcp_tool.status}
 */
public record McpToolSnapshot(
        UUID id,
        UUID serverId,
        String name,
        String description,
        McpToolRisk riskLevel,
        boolean requiresApproval,
        String status) {

    /** 校验必填字段。 */
    public McpToolSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
