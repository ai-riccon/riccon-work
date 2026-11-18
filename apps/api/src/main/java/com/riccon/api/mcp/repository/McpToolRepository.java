package com.riccon.api.mcp.repository;

import com.riccon.api.mcp.domain.McpToolSnapshot;
import com.riccon.api.mcp.persistence.McpToolJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/**
 * 租户 MCP 工具目录仓储。
 *
 * <p>只读：M02 的工具与授权由平台目录和运维预置维护（ADR-0012），租户端不提供增删改。
 */
@Repository
public class McpToolRepository {

    /** 工具 SQL。 */
    private final McpToolJdbc mcpToolJdbc;

    /**
     * 创建仓储。
     *
     * @param mcpToolJdbc 工具 SQL
     */
    public McpToolRepository(McpToolJdbc mcpToolJdbc) {
        this.mcpToolJdbc = Objects.requireNonNull(mcpToolJdbc, "mcpToolJdbc must not be null");
    }

    /**
     * 统计本租户可用工具。
     *
     * @param tenantId 当前租户
     * @return 工具数
     */
    public long count(TenantId tenantId) {
        return mcpToolJdbc.count(tenantId);
    }

    /**
     * 分页列出本租户可用工具。
     *
     * @param tenantId 当前租户
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 工具列表
     */
    public List<McpToolSnapshot> list(TenantId tenantId, int limit, int offset) {
        return mcpToolJdbc.list(tenantId, limit, offset);
    }
}
