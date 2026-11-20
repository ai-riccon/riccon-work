package com.riccon.worker.model.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.model.domain.ModelAttemptStatus;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 模型调用尝试明细访问（`model_invocation_attempt`）。
 *
 * <p>每次上游 HTTP 调用一行，`attempt_no` 在同一调用内从 1 连续递增；行数与降级审计、
 * 指标样本一一对应（ADR-0021 §5.2）。`credential_id` 是逻辑引用，只用于内部排障，
 * 不返回员工。
 */
@Component
public class ModelAttemptJdbc {

    /** 写入 STARTED 行。 */
    private static final String INSERT_STARTED = """
            INSERT INTO model_invocation_attempt
                (tenant_id, invocation_id, attempt_no, model_id, provider_id, credential_id,
                 status, started_at)
            VALUES
                (:tenantId, :invocationId, :attemptNo, :modelId, :providerId, :credentialId,
                 'STARTED', :startedAt)
            """;

    /** 推进终态；只允许从 STARTED 迁移，重复写入不覆盖。 */
    private static final String UPDATE_TERMINAL = """
            UPDATE model_invocation_attempt
               SET status = :status,
                   error_code = :errorCode,
                   first_token_ms = :firstTokenMs,
                   finished_at = :finishedAt
             WHERE tenant_id = :tenantId
               AND invocation_id = :invocationId
               AND attempt_no = :attemptNo
               AND status = 'STARTED'
            """;

    /** 租户数据访问强制点。 */
    private final TenantJdbc tenantJdbc;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建尝试明细访问。
     *
     * @param tenantJdbc 租户数据访问层
     * @param clock UTC 时钟
     */
    public ModelAttemptJdbc(TenantJdbc tenantJdbc, Clock clock) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 写入一次 STARTED 尝试。
     *
     * @param tenantId 请求租户
     * @param invocationId 调用 ID
     * @param attemptNo 尝试序号
     * @param modelId 模型 ID
     * @param providerId 供应商 ID
     * @param credentialId 凭据 ID，可为空
     */
    public void insertStarted(
            TenantId tenantId, UUID invocationId, int attemptNo, UUID modelId, UUID providerId, UUID credentialId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("invocationId", UuidBinary.toBytes(invocationId));
        params.put("attemptNo", attemptNo);
        params.put("modelId", UuidBinary.toBytes(modelId));
        params.put("providerId", UuidBinary.toBytes(providerId));
        params.put("credentialId", credentialId == null ? null : UuidBinary.toBytes(credentialId));
        params.put("startedAt", LocalDateTime.now(clock.withZone(ZoneOffset.UTC)));
        tenantJdbc.insert(tenantId, INSERT_STARTED, params);
    }

    /**
     * 推进尝试终态。
     *
     * @param tenantId 请求租户
     * @param invocationId 调用 ID
     * @param attemptNo 尝试序号
     * @param status 终态
     * @param errorCode 稳定错误码，可为空
     * @param firstTokenMs 首字延迟，可为空
     */
    public void finish(
            TenantId tenantId,
            UUID invocationId,
            int attemptNo,
            ModelAttemptStatus status,
            String errorCode,
            Integer firstTokenMs) {
        if (status == ModelAttemptStatus.STARTED) {
            throw new IllegalArgumentException("attempt terminal status must not be STARTED");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", status.name());
        params.put("errorCode", errorCode);
        params.put("firstTokenMs", firstTokenMs);
        params.put("finishedAt", LocalDateTime.now(clock.withZone(ZoneOffset.UTC)));
        params.put("invocationId", UuidBinary.toBytes(invocationId));
        params.put("attemptNo", attemptNo);
        tenantJdbc.update(tenantId, UPDATE_TERMINAL, params);
    }
}
