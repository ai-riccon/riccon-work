package com.riccon.worker.run.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.research.RunKind;
import com.riccon.platform.domain.research.RunStatus;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.run.domain.RunDetail;
import com.riccon.worker.run.domain.RunRecord;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 运行状态仓库。
 *
 * <p>所有读写都带 {@code tenant_id}；状态推进使用条件更新，重复投递或终态运行不会再次执行。
 * 运行状态是 outbox 之外的权威记录，文件解析与索引成功/失败必须同步推进。
 */
@Component
public class RunRepository {

    /** last_error 列与 error_message 列长度。 */
    private static final int MAX_ERROR_LENGTH = 500;

    /** 租户数据访问强制点。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建运行仓库。
     *
     * @param tenantJdbc 租户数据访问层
     */
    public RunRepository(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 按租户读取运行快照。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @return 运行快照；不存在或不属于该租户时为空
     */
    public Optional<RunRecord> find(TenantId tenantId, UUID runId) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT kind, status, attempt, max_attempts
                  FROM agent_run
                 WHERE tenant_id = :tenantId
                   AND id = :runId
                """,
                Map.of("runId", UuidBinary.toBytes(runId)),
                (rs, rowNum) -> new RunRecord(
                        runId,
                        RunKind.valueOf(rs.getString("kind")),
                        RunStatus.valueOf(rs.getString("status")),
                        rs.getInt("attempt"),
                        rs.getInt("max_attempts")));
    }

    /**
     * 读取运行详情，供服务令牌校验与 Agent 恢复使用。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @return 详情；不存在或不属于该租户时为空
     */
    public Optional<RunDetail> findDetail(TenantId tenantId, UUID runId) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT kind, status, attempt, max_attempts, conversation_id, research_task_id,
                       user_id, trace_id
                  FROM agent_run
                 WHERE tenant_id = :tenantId
                   AND id = :runId
                """,
                Map.of("runId", UuidBinary.toBytes(runId)),
                (rs, rowNum) -> new RunDetail(
                        runId,
                        RunKind.valueOf(rs.getString("kind")),
                        RunStatus.valueOf(rs.getString("status")),
                        rs.getInt("attempt"),
                        rs.getInt("max_attempts"),
                        rs.getBytes("conversation_id") == null
                                ? null
                                : UuidBinary.fromBytes(rs.getBytes("conversation_id")),
                        rs.getBytes("research_task_id") == null
                                ? null
                                : UuidBinary.fromBytes(rs.getBytes("research_task_id")),
                        UuidBinary.fromBytes(rs.getBytes("user_id")),
                        rs.getString("trace_id")));
    }

    /**
     * 条件推进到 RUNNING。
     *
     * <p>允许 PENDING、RETRY_WAIT 与 RUNNING：RUNNING 用于租约过期后的重试；终态运行返回 false，
     * 调用方应把重复事件标记完成。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param attempt 本次尝试号
     * @return 是否推进成功
     */
    public boolean markRunning(TenantId tenantId, UUID runId, int attempt) {
        int updated = tenantJdbc.update(tenantId, """
                UPDATE agent_run
                   SET status = 'RUNNING',
                       attempt = GREATEST(attempt, :attempt),
                       started_at = COALESCE(started_at, NOW(6)),
                       error_code = NULL,
                       error_message = NULL
                 WHERE tenant_id = :tenantId
                   AND id = :runId
                   AND status IN ('PENDING', 'RETRY_WAIT', 'RUNNING', 'WAITING_APPROVAL')
                """, Map.of("runId", UuidBinary.toBytes(runId), "attempt", attempt));
        return updated > 0;
    }

    /**
     * 对话流专用：仅允许从 PENDING/RETRY_WAIT 进入 RUNNING，防止同一运行并发两个流。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param attempt 本次尝试号
     * @return 是否推进成功
     */
    public boolean markStreamRunning(TenantId tenantId, UUID runId, int attempt) {
        int updated = tenantJdbc.update(tenantId, """
                UPDATE agent_run
                   SET status = 'RUNNING',
                       attempt = GREATEST(attempt, :attempt),
                       started_at = COALESCE(started_at, NOW(6)),
                       error_code = NULL,
                       error_message = NULL
                 WHERE tenant_id = :tenantId
                   AND id = :runId
                   AND status IN ('PENDING', 'RETRY_WAIT')
                """, Map.of("runId", UuidBinary.toBytes(runId), "attempt", attempt));
        return updated > 0;
    }

    /**
     * 更新当前阶段。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param stage 阶段名
     */
    public void markStage(TenantId tenantId, UUID runId, String stage) {
        tenantJdbc.update(tenantId, """
                UPDATE agent_run
                   SET current_stage = :stage
                 WHERE tenant_id = :tenantId
                   AND id = :runId
                """, Map.of("runId", UuidBinary.toBytes(runId), "stage", stage));
    }

    /**
     * 标记运行等待人工审批；审批不占用 Worker 租约。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     */
    public void markWaitingApproval(TenantId tenantId, UUID runId) {
        tenantJdbc.update(tenantId, """
                UPDATE agent_run
                   SET status = 'WAITING_APPROVAL',
                       lease_until = NULL,
                       lease_owner = NULL
                 WHERE tenant_id = :tenantId
                   AND id = :runId
                   AND status = 'RUNNING'
                """, Map.of("runId", UuidBinary.toBytes(runId)));
    }

    /**
     * 标记运行取消；终态运行不迁移。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     */
    public void markCancelled(TenantId tenantId, UUID runId) {
        tenantJdbc.update(tenantId, """
                UPDATE agent_run
                   SET status = 'CANCELLED',
                       finished_at = NOW(6),
                       lease_until = NULL,
                       lease_owner = NULL
                 WHERE tenant_id = :tenantId
                   AND id = :runId
                   AND status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL', 'RETRY_WAIT')
                """, Map.of("runId", UuidBinary.toBytes(runId)));
    }

    /**
     * 标记运行成功。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     */
    public void markSucceeded(TenantId tenantId, UUID runId) {
        tenantJdbc.update(tenantId, """
                UPDATE agent_run
                   SET status = 'SUCCEEDED',
                       finished_at = NOW(6),
                       error_code = NULL,
                       error_message = NULL
                 WHERE tenant_id = :tenantId
                   AND id = :runId
                   AND status IN ('RUNNING', 'RETRY_WAIT')
                """, Map.of("runId", UuidBinary.toBytes(runId)));
    }

    /**
     * 标记可重试失败，等待下一轮。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param attempt 本次尝试号
     * @param errorCode 稳定错误码
     * @param errorMessage 脱敏摘要
     */
    public void markRetryWait(TenantId tenantId, UUID runId, int attempt, String errorCode, String errorMessage) {
        tenantJdbc.update(
                tenantId,
                """
                UPDATE agent_run
                   SET status = 'RETRY_WAIT',
                       attempt = GREATEST(attempt, :attempt),
                       error_code = :errorCode,
                       error_message = :errorMessage
                 WHERE tenant_id = :tenantId
                   AND id = :runId
                   AND status IN ('RUNNING', 'RETRY_WAIT')
                """,
                Map.of(
                        "runId",
                        UuidBinary.toBytes(runId),
                        "attempt",
                        attempt,
                        "errorCode",
                        errorCode,
                        "errorMessage",
                        truncate(errorMessage)));
    }

    /**
     * 标记不可重试失败或超过最大尝试次数。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param errorCode 稳定错误码
     * @param errorMessage 脱敏摘要
     */
    public void markFailed(TenantId tenantId, UUID runId, String errorCode, String errorMessage) {
        tenantJdbc.update(
                tenantId,
                """
                UPDATE agent_run
                   SET status = 'FAILED',
                       finished_at = NOW(6),
                       error_code = :errorCode,
                       error_message = :errorMessage
                 WHERE tenant_id = :tenantId
                   AND id = :runId
                   AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')
                """,
                Map.of(
                        "runId", UuidBinary.toBytes(runId),
                        "errorCode", errorCode,
                        "errorMessage", truncate(errorMessage)));
    }

    /**
     * 截断错误摘要，避免超过数据库列长度。
     *
     * @param message 错误摘要
     * @return 最长 500 字符
     */
    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
