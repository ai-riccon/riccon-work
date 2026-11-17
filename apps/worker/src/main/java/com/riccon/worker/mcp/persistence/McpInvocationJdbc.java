package com.riccon.worker.mcp.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.mcp.McpInvocationStatus;
import com.riccon.worker.mcp.domain.McpInvocationRecord;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * MCP 调用记录访问。
 *
 * <p>不保存请求/响应原文，只保存 SHA-256、大小与脱敏预览；所有语句带 {@code tenant_id}。
 * 审计由调用服务单独写入，二者不互相替代。
 */
@Component
public class McpInvocationJdbc {

    /** 租户数据访问层。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建调用记录访问。
     *
     * @param tenantJdbc 租户数据访问层
     */
    public McpInvocationJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 写入 STARTED 调用记录。
     *
     * @param tenantId 租户
     * @param record 调用记录
     */
    public void insertStarted(TenantId tenantId, McpInvocationRecord record) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(record.id()));
        params.put("runId", UuidBinary.toBytes(record.runId()));
        params.put(
                "researchTaskId", record.researchTaskId() == null ? null : UuidBinary.toBytes(record.researchTaskId()));
        params.put("approvalId", record.approvalId() == null ? null : UuidBinary.toBytes(record.approvalId()));
        params.put("mcpToolId", UuidBinary.toBytes(record.toolId()));
        params.put("toolName", record.toolName());
        params.put("paramsSha256", record.paramsSha256());
        params.put("paramsPreview", record.paramsPreview());
        tenantJdbc.insert(tenantId, """
                INSERT INTO mcp_invocation
                    (tenant_id, id, run_id, research_task_id, approval_id, mcp_tool_id,
                     tool_name, status, params_sha256, params_preview)
                VALUES
                    (:tenantId, :id, :runId, :researchTaskId, :approvalId, :mcpToolId,
                     :toolName, 'STARTED', :paramsSha256, :paramsPreview)
                """, params);
    }

    /**
     * 标记成功结果。
     *
     * @param tenantId 租户
     * @param id 调用 ID
     * @param resultSha256 结果摘要
     * @param resultSizeBytes 结果字节数
     * @param resultPreview 脱敏预览
     * @param durationMs 耗时
     */
    public void markSucceeded(
            TenantId tenantId,
            UUID id,
            String resultSha256,
            long resultSizeBytes,
            String resultPreview,
            long durationMs) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(id));
        params.put("resultSha256", resultSha256);
        params.put("resultSizeBytes", resultSizeBytes);
        params.put("resultPreview", resultPreview);
        params.put("durationMs", durationMs);
        tenantJdbc.update(tenantId, """
                UPDATE mcp_invocation
                   SET status = 'SUCCEEDED',
                       result_sha256 = :resultSha256,
                       result_size_bytes = :resultSizeBytes,
                       result_preview = :resultPreview,
                       duration_ms = :durationMs,
                       finished_at = NOW(6),
                       error_code = NULL
                 WHERE tenant_id = :tenantId
                   AND id = :id
                   AND status = 'STARTED'
                """, params);
    }

    /**
     * 标记失败或拒绝。
     *
     * @param tenantId 租户
     * @param id 调用 ID
     * @param status FAILED 或 DENIED
     * @param errorCode 稳定错误码
     * @param durationMs 耗时
     */
    public void markTerminated(
            TenantId tenantId, UUID id, McpInvocationStatus status, String errorCode, long durationMs) {
        if (status != McpInvocationStatus.FAILED && status != McpInvocationStatus.DENIED) {
            throw new IllegalArgumentException("terminal status must be FAILED or DENIED");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(id));
        params.put("status", status.name());
        params.put("errorCode", errorCode);
        params.put("durationMs", durationMs);
        tenantJdbc.update(tenantId, """
                UPDATE mcp_invocation
                   SET status = :status,
                       duration_ms = :durationMs,
                       error_code = :errorCode,
                       finished_at = NOW(6)
                 WHERE tenant_id = :tenantId
                   AND id = :id
                   AND status = 'STARTED'
                """, params);
    }
}
