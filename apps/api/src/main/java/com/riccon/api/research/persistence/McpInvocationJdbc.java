package com.riccon.api.research.persistence;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.api.research.domain.McpInvocationSnapshot;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.mcp.McpInvocationStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * MCP 调用记录 SQL。
 *
 * <p>只返回工具名、状态、hash、耗时与错误码；请求/响应原文不在表中，也不通过 API 返回。
 */
@Component
public class McpInvocationJdbc {

    /** 按任务读取调用记录。 */
    private static final String LIST_BY_TASK = """
            SELECT id, tool_name, status, params_sha256, result_sha256, duration_ms,
                   error_code, started_at, finished_at
              FROM mcp_invocation
             WHERE tenant_id = :tenantId
               AND research_task_id = :taskId
             ORDER BY started_at ASC, id ASC
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建 MCP 调用 SQL 组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public McpInvocationJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 读取任务下的 MCP 调用记录。
     *
     * @param tenantId 当前租户
     * @param taskId 报告任务 ID
     * @return 调用记录
     */
    public List<McpInvocationSnapshot> listByTask(TenantId tenantId, UUID taskId) {
        return tenantJdbc.query(
                tenantId,
                LIST_BY_TASK,
                Map.of("taskId", BinaryUuids.toBytes(taskId)),
                (rs, rowNum) -> new McpInvocationSnapshot(
                        BinaryUuids.fromBytes(rs.getBytes("id")),
                        rs.getString("tool_name"),
                        McpInvocationStatus.valueOf(rs.getString("status")),
                        rs.getString("params_sha256"),
                        rs.getString("result_sha256"),
                        rs.getObject("duration_ms", Long.class),
                        rs.getString("error_code"),
                        SqlTimestamps.toInstant(rs.getObject("started_at", LocalDateTime.class)),
                        SqlTimestamps.toInstant(rs.getObject("finished_at", LocalDateTime.class))));
    }
}
