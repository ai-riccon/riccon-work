package com.riccon.worker.mcp.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 租户 MCP 工具授权访问。
 *
 * <p>授权表带 {@code tenant_id}，必须通过 {@link TenantJdbc} 读取；未授权等同于工具不存在，
 * 不向调用方区分“未授权”和“工具不存在”，避免枚举平台目录。
 */
@Component
public class McpGrantJdbc {

    /** 授权查询。 */
    private static final String SELECT_GRANT = """
            SELECT enabled
              FROM mcp_tool_grant
             WHERE tenant_id = :tenantId
               AND mcp_tool_id = :mcpToolId
            """;

    /** 第一个启用的工具 ID。 */
    private static final String SELECT_FIRST_GRANTED = """
            SELECT mcp_tool_id
              FROM mcp_tool_grant
             WHERE tenant_id = :tenantId
               AND enabled = TRUE
             ORDER BY granted_at, mcp_tool_id
             LIMIT 1
            """;

    /** 租户数据访问层。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建授权访问。
     *
     * @param tenantJdbc 租户数据访问层
     */
    public McpGrantJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 判断工具是否已授权给租户。
     *
     * @param tenantId 租户
     * @param toolId 工具 ID
     * @return 是否启用
     */
    public boolean isEnabled(TenantId tenantId, UUID toolId) {
        return tenantJdbc
                .queryOne(
                        tenantId,
                        SELECT_GRANT,
                        Map.of("mcpToolId", UuidBinary.toBytes(toolId)),
                        (rs, rowNum) -> rs.getBoolean("enabled"))
                .orElse(false);
    }

    /**
     * 返回租户最早授权的工具 ID。
     *
     * @param tenantId 租户
     * @return 工具 ID；没有授权时为空
     */
    public Optional<UUID> findFirstEnabledTool(TenantId tenantId) {
        return tenantJdbc.queryOne(
                tenantId,
                SELECT_FIRST_GRANTED,
                Map.of(),
                (rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes("mcp_tool_id")));
    }
}
