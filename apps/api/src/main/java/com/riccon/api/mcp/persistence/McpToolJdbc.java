package com.riccon.api.mcp.persistence;

import com.riccon.api.mcp.domain.McpToolSnapshot;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.mcp.McpToolRisk;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 租户已授权 MCP 工具 SQL。
 *
 * <p>工具目录是平台全局表，租户可见性只由 {@code mcp_tool_grant} 决定。查询同时要求授权启用、工具可用、
 * server 可用，并与创建任务时的授权校验保持同一口径（READ + requires_approval），避免 UI 列出工具后再创建
 * 被 400 拒绝。所有 SQL 经 {@link TenantJdbc} 强制租户条件；跨租户不会出现在结果中。
 */
@Component
public class McpToolJdbc {

    /** 授权与目录过滤条件；列表与计数共用，保证分页总数与条目一致。 */
    private static final String FILTER = """
              FROM mcp_tool_grant tool_grant
              JOIN mcp_tool tool ON tool.id = tool_grant.mcp_tool_id
              JOIN mcp_server server ON server.id = tool.server_id
             WHERE tool_grant.tenant_id = :tenantId
               AND tool_grant.enabled = TRUE
               AND tool.status = 'ACTIVE'
               AND server.status = 'ACTIVE'
               AND tool.risk_level = 'READ'
               AND tool.requires_approval = TRUE
            """;

    /** 统计本租户可用工具数。 */
    private static final String COUNT = "SELECT COUNT(*) " + FILTER;

    /** 分页列出本租户可用工具；名称排序保证 UI 下拉顺序稳定。 */
    private static final String LIST = "SELECT tool.id, tool.server_id, tool.name, tool.description, "
            + "tool.risk_level, tool.requires_approval, tool.status "
            + FILTER
            + " ORDER BY tool.name ASC, tool.id ASC LIMIT :limit OFFSET :offset";

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建工具 SQL 组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public McpToolJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 统计本租户已授权且可用的工具。
     *
     * @param tenantId 当前租户
     * @return 工具数
     */
    public long count(TenantId tenantId) {
        return tenantJdbc.count(tenantId, COUNT, Map.of());
    }

    /**
     * 分页列出本租户已授权且可用的工具。
     *
     * @param tenantId 当前租户
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 工具列表
     */
    public List<McpToolSnapshot> list(TenantId tenantId, int limit, int offset) {
        return tenantJdbc.query(tenantId, LIST, Map.of("limit", limit, "offset", offset), McpToolJdbc::mapRow);
    }

    /**
     * 映射工具行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 工具快照
     * @throws SQLException 列读取失败
     */
    private static McpToolSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new McpToolSnapshot(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                rs.getBytes("server_id") == null ? null : BinaryUuids.fromBytes(rs.getBytes("server_id")),
                rs.getString("name"),
                rs.getString("description"),
                McpToolRisk.valueOf(rs.getString("risk_level")),
                rs.getBoolean("requires_approval"),
                rs.getString("status"));
    }
}
