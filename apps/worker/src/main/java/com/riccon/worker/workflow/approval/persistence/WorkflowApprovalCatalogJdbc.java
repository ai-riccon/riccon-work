package com.riccon.worker.workflow.approval.persistence;

import com.riccon.worker.persistence.UuidBinary;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 审批目标工具的全局目录只读访问。
 *
 * <p>{@code mcp_tool} 是平台内置白名单表（ADR-0012 例外），不带 {@code tenant_id}，因此不能走
 * {@code TenantJdbc}；这里与 {@code McpCatalogJdbc} 一样只读取工具名快照，不读取授权或调用数据。
 * 审批摘要只存工具名与脱敏参数，不存端点、凭据或 schema（ADR-0024 §3.3.5/§4.7）。
 */
@Component
public class WorkflowApprovalCatalogJdbc {

    /** 只读取工具名与状态。 */
    private static final String SELECT_TOOL_NAME = """
            SELECT name, status
              FROM mcp_tool
             WHERE id = :id
            """;

    /** 全局 JDBC 客户端。 */
    private final JdbcClient jdbcClient;

    /**
     * 创建目录访问。
     *
     * @param jdbcClient JDBC 客户端
     */
    public WorkflowApprovalCatalogJdbc(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    /**
     * 读取 ACTIVE 工具名。
     *
     * @param toolId 工具 ID
     * @return 工具名；不存在或非 ACTIVE 为空
     */
    public Optional<String> findActiveToolName(UUID toolId) {
        if (toolId == null) {
            return Optional.empty();
        }
        return jdbcClient
                .sql(SELECT_TOOL_NAME)
                .param("id", UuidBinary.toBytes(toolId))
                .query((rs, rowNum) -> new ToolRow(rs.getString("name"), rs.getString("status")))
                .optional()
                .filter(row -> "ACTIVE".equals(row.status()))
                .map(ToolRow::name);
    }

    /**
     * 工具行投影。
     *
     * @param name 工具名
     * @param status 状态
     */
    private record ToolRow(String name, String status) {}
}
