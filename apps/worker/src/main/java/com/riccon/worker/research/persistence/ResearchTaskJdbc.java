package com.riccon.worker.research.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.research.ResearchTaskStatus;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.research.domain.ResearchTaskRecord;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 报告任务读写。
 *
 * <p>Worker 读取任务主题与工具选择，并把运行结果投影回任务行：RUNNING/WAITING_APPROVAL/SUCCEEDED/
 * FAILED/CANCELLED。所有更新都带 {@code tenant_id} 与 {@code current_run_id} 条件，避免旧运行的迟到事件
 * 覆盖新运行；API 读取前仍会从运行表同步状态，二者保持一致。
 */
@Component
public class ResearchTaskJdbc {

    /** 租户数据访问层。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建任务访问。
     *
     * @param tenantJdbc 租户数据访问层
     */
    public ResearchTaskJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 读取任务。
     *
     * @param tenantId 租户
     * @param taskId 任务 ID
     * @return 任务
     */
    public Optional<ResearchTaskRecord> find(TenantId tenantId, UUID taskId) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT id, workspace_id, topic, status, mcp_tool_id, created_by
                  FROM research_task
                 WHERE tenant_id = :tenantId
                   AND id = :taskId
                """,
                Map.of("taskId", UuidBinary.toBytes(taskId)),
                (rs, rowNum) -> new ResearchTaskRecord(
                        UuidBinary.fromBytes(rs.getBytes("id")),
                        UuidBinary.fromBytes(rs.getBytes("workspace_id")),
                        rs.getString("topic"),
                        ResearchTaskStatus.valueOf(rs.getString("status")),
                        rs.getBytes("mcp_tool_id") == null ? null : UuidBinary.fromBytes(rs.getBytes("mcp_tool_id")),
                        UuidBinary.fromBytes(rs.getBytes("created_by"))));
    }

    /**
     * 标记任务运行中并推进当前阶段。
     *
     * <p>允许从 FAILED 重新进入 RUNNING：可重试失败会在下一次 outbox 投递时恢复；CANCELLED/SUCCEEDED
     * 不再迁移，避免迟到事件复活已结束任务。
     *
     * @param tenantId 租户
     * @param taskId 任务 ID
     * @param runId 当前运行 ID
     * @param stage 当前阶段
     * @return 受影响行数
     */
    public int markRunning(TenantId tenantId, UUID taskId, UUID runId, String stage) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskId", UuidBinary.toBytes(taskId));
        params.put("runId", UuidBinary.toBytes(runId));
        params.put("stage", stage);
        return tenantJdbc.update(tenantId, """
                UPDATE research_task
                   SET status = 'RUNNING',
                       current_stage = :stage,
                       current_run_id = COALESCE(current_run_id, :runId),
                       started_at = COALESCE(started_at, NOW(6)),
                       finished_at = NULL,
                       failure_code = NULL,
                       failure_message = NULL
                 WHERE tenant_id = :tenantId
                   AND id = :taskId
                   AND (current_run_id IS NULL OR current_run_id = :runId)
                   AND status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL', 'FAILED')
                """, params);
    }

    /**
     * 标记任务等待审批。
     *
     * @param tenantId 租户
     * @param taskId 任务 ID
     * @param runId 当前运行 ID
     * @param stage 当前阶段
     * @return 受影响行数
     */
    public int markWaitingApproval(TenantId tenantId, UUID taskId, UUID runId, String stage) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskId", UuidBinary.toBytes(taskId));
        params.put("runId", UuidBinary.toBytes(runId));
        params.put("stage", stage);
        return tenantJdbc.update(tenantId, """
                UPDATE research_task
                   SET status = 'WAITING_APPROVAL',
                       current_stage = :stage,
                       current_run_id = COALESCE(current_run_id, :runId),
                       finished_at = NULL
                 WHERE tenant_id = :tenantId
                   AND id = :taskId
                   AND (current_run_id IS NULL OR current_run_id = :runId)
                   AND status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL', 'FAILED')
                """, params);
    }

    /**
     * 标记任务成功；只在产物已保存后调用。
     *
     * @param tenantId 租户
     * @param taskId 任务 ID
     * @param runId 当前运行 ID
     * @return 受影响行数
     */
    public int markSucceeded(TenantId tenantId, UUID taskId, UUID runId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskId", UuidBinary.toBytes(taskId));
        params.put("runId", UuidBinary.toBytes(runId));
        return tenantJdbc.update(tenantId, """
                UPDATE research_task
                   SET status = 'SUCCEEDED',
                       finished_at = NOW(6),
                       failure_code = NULL,
                       failure_message = NULL
                 WHERE tenant_id = :tenantId
                   AND id = :taskId
                   AND (current_run_id IS NULL OR current_run_id = :runId)
                   AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                """, params);
    }

    /**
     * 标记任务失败并记录稳定错误码。
     *
     * @param tenantId 租户
     * @param taskId 任务 ID
     * @param runId 当前运行 ID
     * @param errorCode 稳定错误码
     * @param errorMessage 脱敏错误
     * @return 受影响行数
     */
    public int markFailed(TenantId tenantId, UUID taskId, UUID runId, String errorCode, String errorMessage) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskId", UuidBinary.toBytes(taskId));
        params.put("runId", UuidBinary.toBytes(runId));
        params.put("errorCode", errorCode);
        params.put("errorMessage", truncate(errorMessage));
        return tenantJdbc.update(tenantId, """
                UPDATE research_task
                   SET status = 'FAILED',
                       failure_code = :errorCode,
                       failure_message = :errorMessage,
                       finished_at = NOW(6)
                 WHERE tenant_id = :tenantId
                   AND id = :taskId
                   AND (current_run_id IS NULL OR current_run_id = :runId)
                   AND status NOT IN ('SUCCEEDED', 'CANCELLED')
                """, params);
    }

    /**
     * 标记任务取消；可带终止原因（例如审批拒绝）。
     *
     * @param tenantId 租户
     * @param taskId 任务 ID
     * @param runId 当前运行 ID
     * @param failureCode 终止原因码，可为空
     * @param failureMessage 脱敏终止原因，可为空
     * @return 受影响行数
     */
    public int markCancelled(TenantId tenantId, UUID taskId, UUID runId, String failureCode, String failureMessage) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskId", UuidBinary.toBytes(taskId));
        params.put("runId", UuidBinary.toBytes(runId));
        params.put("failureCode", failureCode);
        params.put("failureMessage", truncate(failureMessage));
        return tenantJdbc.update(tenantId, """
                UPDATE research_task
                   SET status = 'CANCELLED',
                       failure_code = COALESCE(:failureCode, failure_code),
                       failure_message = COALESCE(:failureMessage, failure_message),
                       finished_at = NOW(6)
                 WHERE tenant_id = :tenantId
                   AND id = :taskId
                   AND (current_run_id IS NULL OR current_run_id = :runId)
                   AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                """, params);
    }

    /**
     * 截断错误摘要，避免超过 {@code failure_message} 列宽。
     *
     * @param value 原始摘要
     * @return 最长 500 字符
     */
    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
