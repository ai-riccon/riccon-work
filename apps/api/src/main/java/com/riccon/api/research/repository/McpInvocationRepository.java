package com.riccon.api.research.repository;

import com.riccon.api.research.domain.McpInvocationSnapshot;
import com.riccon.api.research.persistence.McpInvocationJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * MCP 调用记录仓储。
 */
@Repository
public class McpInvocationRepository {

    /** MCP 调用 SQL。 */
    private final McpInvocationJdbc mcpInvocationJdbc;

    /**
     * 创建仓储。
     *
     * @param mcpInvocationJdbc MCP 调用 SQL
     */
    public McpInvocationRepository(McpInvocationJdbc mcpInvocationJdbc) {
        this.mcpInvocationJdbc = Objects.requireNonNull(mcpInvocationJdbc, "mcpInvocationJdbc must not be null");
    }

    /**
     * 读取任务下的调用记录。
     *
     * @param tenantId 当前租户
     * @param taskId 报告任务 ID
     * @return 调用记录
     */
    public List<McpInvocationSnapshot> listByTask(TenantId tenantId, UUID taskId) {
        return mcpInvocationJdbc.listByTask(tenantId, taskId);
    }
}
