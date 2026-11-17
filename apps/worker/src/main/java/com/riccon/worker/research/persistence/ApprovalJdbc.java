package com.riccon.worker.research.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.research.ApprovalStatus;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.research.domain.ApprovalRecord;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 审批持久化。
 *
 * <p>Worker 只创建待审批记录并读取审批结论；决定由 API 在审批事务内写入（ADR-0008）。
 * 唯一键 {@code (tenant_id, run_id, stage, attempt_no)} 保证重试不会产生第二个审批。
 */
@Component
public class ApprovalJdbc {

    /** 租户数据访问层。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建审批访问。
     *
     * @param tenantJdbc 租户数据访问层
     */
    public ApprovalJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 读取指定运行阶段尝试的审批。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param stage 阶段
     * @param attemptNo 尝试号
     * @return 审批；不存在时为空
     */
    public Optional<ApprovalRecord> find(TenantId tenantId, UUID runId, String stage, int attemptNo) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT id, research_task_id, stage, attempt_no, status, mcp_tool_id, tool_name,
                       params_preview, params_ciphertext, params_key_id, params_sha256, requested_by
                  FROM approval
                 WHERE tenant_id = :tenantId
                   AND run_id = :runId
                   AND stage = :stage
                   AND attempt_no = :attemptNo
                """,
                Map.of(
                        "runId", UuidBinary.toBytes(runId),
                        "stage", stage,
                        "attemptNo", attemptNo),
                (rs, rowNum) -> new ApprovalRecord(
                        UuidBinary.fromBytes(rs.getBytes("id")),
                        runId,
                        UuidBinary.fromBytes(rs.getBytes("research_task_id")),
                        rs.getString("stage"),
                        rs.getInt("attempt_no"),
                        ApprovalStatus.valueOf(rs.getString("status")),
                        UuidBinary.fromBytes(rs.getBytes("mcp_tool_id")),
                        rs.getString("tool_name"),
                        rs.getString("params_preview"),
                        rs.getBytes("params_ciphertext"),
                        rs.getString("params_key_id"),
                        rs.getString("params_sha256"),
                        UuidBinary.fromBytes(rs.getBytes("requested_by"))));
    }

    /**
     * 按 ID 读取审批。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @return 审批
     */
    public Optional<ApprovalRecord> findById(TenantId tenantId, UUID approvalId) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT run_id, research_task_id, stage, attempt_no, status, mcp_tool_id, tool_name,
                       params_preview, params_ciphertext, params_key_id, params_sha256, requested_by
                  FROM approval
                 WHERE tenant_id = :tenantId
                   AND id = :approvalId
                """,
                Map.of("approvalId", UuidBinary.toBytes(approvalId)),
                (rs, rowNum) -> new ApprovalRecord(
                        approvalId,
                        UuidBinary.fromBytes(rs.getBytes("run_id")),
                        UuidBinary.fromBytes(rs.getBytes("research_task_id")),
                        rs.getString("stage"),
                        rs.getInt("attempt_no"),
                        ApprovalStatus.valueOf(rs.getString("status")),
                        UuidBinary.fromBytes(rs.getBytes("mcp_tool_id")),
                        rs.getString("tool_name"),
                        rs.getString("params_preview"),
                        rs.getBytes("params_ciphertext"),
                        rs.getString("params_key_id"),
                        rs.getString("params_sha256"),
                        UuidBinary.fromBytes(rs.getBytes("requested_by"))));
    }

    /**
     * 创建待审批记录。
     *
     * @param tenantId 租户
     * @param id 审批 ID
     * @param runId 运行 ID
     * @param researchTaskId 任务 ID
     * @param stage 阶段
     * @param attemptNo 尝试号
     * @param toolId 工具 ID
     * @param toolName 工具名
     * @param paramsPreview 脱敏预览 JSON
     * @param paramsCiphertext 完整参数密文
     * @param paramsKeyId 加密所用 DEK 标识
     * @param paramsSha256 入参摘要
     * @param requestedBy 发起人
     */
    public void insertPending(
            TenantId tenantId,
            UUID id,
            UUID runId,
            UUID researchTaskId,
            String stage,
            int attemptNo,
            UUID toolId,
            String toolName,
            String paramsPreview,
            byte[] paramsCiphertext,
            String paramsKeyId,
            String paramsSha256,
            UUID requestedBy) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(id));
        params.put("runId", UuidBinary.toBytes(runId));
        params.put("researchTaskId", UuidBinary.toBytes(researchTaskId));
        params.put("stage", stage);
        params.put("attemptNo", attemptNo);
        params.put("toolId", UuidBinary.toBytes(toolId));
        params.put("toolName", toolName);
        params.put("paramsPreview", paramsPreview);
        params.put("paramsCiphertext", paramsCiphertext);
        params.put("paramsKeyId", paramsKeyId);
        params.put("paramsSha256", paramsSha256);
        params.put("requestedBy", UuidBinary.toBytes(requestedBy));
        tenantJdbc.insert(tenantId, """
                INSERT INTO approval
                    (tenant_id, id, research_task_id, run_id, stage, attempt_no, status,
                     action_type, mcp_tool_id, tool_name, params_preview, params_ciphertext, params_key_id,
                     params_sha256, requested_by)
                VALUES
                    (:tenantId, :id, :researchTaskId, :runId, :stage, :attemptNo, 'PENDING',
                     'MCP_TOOL_CALL', :toolId, :toolName, :paramsPreview, :paramsCiphertext, :paramsKeyId,
                     :paramsSha256, :requestedBy)
                """, params);
    }

    /**
     * 取消运行下所有 PENDING 审批。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     */
    public void cancelPending(TenantId tenantId, UUID runId) {
        tenantJdbc.update(tenantId, """
                UPDATE approval
                   SET status = 'CANCELLED'
                 WHERE tenant_id = :tenantId
                   AND run_id = :runId
                   AND status = 'PENDING'
                """, Map.of("runId", UuidBinary.toBytes(runId)));
    }
}
