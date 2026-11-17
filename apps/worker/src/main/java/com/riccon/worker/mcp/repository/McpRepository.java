package com.riccon.worker.mcp.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.mcp.McpInvocationStatus;
import com.riccon.worker.mcp.domain.McpInvocationRecord;
import com.riccon.worker.mcp.domain.McpToolHandle;
import com.riccon.worker.mcp.persistence.McpCatalogJdbc;
import com.riccon.worker.mcp.persistence.McpGrantJdbc;
import com.riccon.worker.mcp.persistence.McpInvocationJdbc;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * MCP 目录、授权与调用记录仓储。
 *
 * <p>工具必须先通过租户授权检查才返回；server 不属于工具时视为不存在。所有调用记录写入
 * 都经过 {@code TenantJdbc}。
 */
@Component
public class McpRepository {

    /** 目录访问。 */
    private final McpCatalogJdbc catalogJdbc;

    /** 授权访问。 */
    private final McpGrantJdbc grantJdbc;

    /** 调用记录访问。 */
    private final McpInvocationJdbc invocationJdbc;

    /**
     * 创建 MCP 仓储。
     *
     * @param catalogJdbc 目录访问
     * @param grantJdbc 授权访问
     * @param invocationJdbc 调用记录访问
     */
    public McpRepository(McpCatalogJdbc catalogJdbc, McpGrantJdbc grantJdbc, McpInvocationJdbc invocationJdbc) {
        this.catalogJdbc = Objects.requireNonNull(catalogJdbc, "catalogJdbc must not be null");
        this.grantJdbc = Objects.requireNonNull(grantJdbc, "grantJdbc must not be null");
        this.invocationJdbc = Objects.requireNonNull(invocationJdbc, "invocationJdbc must not be null");
    }

    /**
     * 读取已授权工具及其 server。
     *
     * @param tenantId 租户
     * @param toolId 工具 ID
     * @return 工具与 server；未授权、不存在或状态不可用时为空
     */
    public Optional<McpToolHandle> findAuthorizedTool(TenantId tenantId, UUID toolId) {
        if (!grantJdbc.isEnabled(tenantId, toolId)) {
            return Optional.empty();
        }
        return catalogJdbc
                .findTool(toolId)
                .flatMap(
                        tool -> catalogJdbc.findServer(tool.serverId()).map(server -> new McpToolHandle(tool, server)));
    }

    /**
     * 读取租户第一个已授权工具。
     *
     * @param tenantId 租户
     * @return 工具与 server；没有授权时为空
     */
    public Optional<McpToolHandle> findFirstAuthorizedTool(TenantId tenantId) {
        return grantJdbc.findFirstEnabledTool(tenantId).flatMap(toolId -> findAuthorizedTool(tenantId, toolId));
    }

    /**
     * 写入 STARTED 调用记录。
     *
     * @param tenantId 租户
     * @param record 记录
     */
    public void invocationStarted(TenantId tenantId, McpInvocationRecord record) {
        invocationJdbc.insertStarted(tenantId, record);
    }

    /**
     * 标记调用成功。
     *
     * @param tenantId 租户
     * @param id 调用 ID
     * @param resultSha256 结果摘要
     * @param resultSizeBytes 结果大小
     * @param resultPreview 脱敏预览
     * @param durationMs 耗时
     */
    public void invocationSucceeded(
            TenantId tenantId,
            UUID id,
            String resultSha256,
            long resultSizeBytes,
            String resultPreview,
            long durationMs) {
        invocationJdbc.markSucceeded(tenantId, id, resultSha256, resultSizeBytes, resultPreview, durationMs);
    }

    /**
     * 标记调用失败或拒绝。
     *
     * @param tenantId 租户
     * @param id 调用 ID
     * @param status FAILED 或 DENIED
     * @param errorCode 错误码
     * @param durationMs 耗时
     */
    public void invocationTerminated(
            TenantId tenantId, UUID id, McpInvocationStatus status, String errorCode, long durationMs) {
        invocationJdbc.markTerminated(tenantId, id, status, errorCode, durationMs);
    }
}
