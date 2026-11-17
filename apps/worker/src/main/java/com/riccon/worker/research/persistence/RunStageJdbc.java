package com.riccon.worker.research.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.research.StageStatus;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.research.domain.RunStageRecord;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 运行阶段时间线访问。
 *
 * <p>每个阶段每次尝试一条记录；重复投递复用同一行，不产生重复时间线。details 只允许
 * 白名单摘要（hash、计数、审批 ID），禁止提示词与工具原文。
 */
@Component
public class RunStageJdbc {

    /** 租户数据访问层。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建阶段访问。
     *
     * @param tenantJdbc 租户数据访问层
     */
    public RunStageJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 读取指定阶段尝试。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param stage 阶段
     * @param attemptNo 尝试号
     * @return 记录
     */
    public Optional<RunStageRecord> find(TenantId tenantId, UUID runId, String stage, int attemptNo) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT id, stage, attempt_no, status, details
                  FROM agent_run_stage
                 WHERE tenant_id = :tenantId
                   AND run_id = :runId
                   AND stage = :stage
                   AND attempt_no = :attemptNo
                """,
                Map.of(
                        "runId", UuidBinary.toBytes(runId),
                        "stage", stage,
                        "attemptNo", attemptNo),
                (rs, rowNum) -> new RunStageRecord(
                        UuidBinary.fromBytes(rs.getBytes("id")),
                        rs.getString("stage"),
                        rs.getInt("attempt_no"),
                        StageStatus.valueOf(rs.getString("status")),
                        rs.getString("details")));
    }

    /**
     * 启动阶段；已存在时重置为 RUNNING，保证重复投递幂等。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param stage 阶段
     * @param attemptNo 尝试号
     * @return 阶段记录 ID
     */
    public UUID start(TenantId tenantId, UUID runId, String stage, int attemptNo) {
        Optional<RunStageRecord> existing = find(tenantId, runId, stage, attemptNo);
        if (existing.isPresent()) {
            tenantJdbc.update(
                    tenantId,
                    """
                    UPDATE agent_run_stage
                       SET status = 'RUNNING',
                           started_at = COALESCE(started_at, NOW(6)),
                           finished_at = NULL,
                           error_code = NULL,
                           error_message = NULL
                     WHERE tenant_id = :tenantId
                       AND id = :id
                    """,
                    Map.of("id", UuidBinary.toBytes(existing.get().id())));
            return existing.get().id();
        }
        UUID id = UuidBinary.newV7();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(id));
        params.put("runId", UuidBinary.toBytes(runId));
        params.put("stage", stage);
        params.put("attemptNo", attemptNo);
        tenantJdbc.insert(tenantId, """
                INSERT INTO agent_run_stage
                    (tenant_id, id, run_id, stage, attempt_no, status, started_at)
                VALUES
                    (:tenantId, :id, :runId, :stage, :attemptNo, 'RUNNING', NOW(6))
                """, params);
        return id;
    }

    /**
     * 更新运行中阶段的详情；不改变状态与时间。
     *
     * <p>用于审批等待期间先把脱敏预览写入时间线；阶段结束时由 {@link #finish} 写入最终详情。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param stage 阶段
     * @param attemptNo 尝试号
     * @param detailsJson 白名单摘要 JSON，可为空
     */
    public void updateDetails(TenantId tenantId, UUID runId, String stage, int attemptNo, String detailsJson) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("runId", UuidBinary.toBytes(runId));
        params.put("stage", stage);
        params.put("attemptNo", attemptNo);
        params.put("details", detailsJson);
        tenantJdbc.update(tenantId, """
                UPDATE agent_run_stage
                   SET details = :details
                 WHERE tenant_id = :tenantId
                   AND run_id = :runId
                   AND stage = :stage
                   AND attempt_no = :attemptNo
                """, params);
    }

    /**
     * 结束阶段。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param stage 阶段
     * @param attemptNo 尝试号
     * @param status 终态
     * @param detailsJson 白名单摘要 JSON，可为空
     * @param errorCode 错误码，可为空
     * @param errorMessage 脱敏错误，可为空
     */
    public void finish(
            TenantId tenantId,
            UUID runId,
            String stage,
            int attemptNo,
            StageStatus status,
            String detailsJson,
            String errorCode,
            String errorMessage) {
        if (status == StageStatus.RUNNING) {
            throw new IllegalArgumentException("stage finish status must be terminal");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("runId", UuidBinary.toBytes(runId));
        params.put("stage", stage);
        params.put("attemptNo", attemptNo);
        params.put("status", status.name());
        params.put("details", detailsJson);
        params.put("errorCode", errorCode);
        params.put("errorMessage", errorMessage);
        tenantJdbc.update(tenantId, """
                UPDATE agent_run_stage
                   SET status = :status,
                       details = :details,
                       error_code = :errorCode,
                       error_message = :errorMessage,
                       finished_at = NOW(6)
                 WHERE tenant_id = :tenantId
                   AND run_id = :runId
                   AND stage = :stage
                   AND attempt_no = :attemptNo
                """, params);
    }
}
