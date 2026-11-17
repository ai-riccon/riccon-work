package com.riccon.worker.model.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.model.InvocationStatus;
import com.riccon.worker.model.domain.ModelInvocationRecord;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 模型调用记录访问。
 *
 * <p>调用记录是运行详情与时间线的数据来源，不替代审计。所有语句带 {@code tenant_id}，
 * 由 {@link TenantJdbc} 强制；失败更新只允许从 STARTED 迁移，重复投递不会覆盖终态。
 */
@Component
public class ModelInvocationJdbc {

    /** 租户数据访问层。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建调用记录访问。
     *
     * @param tenantJdbc 租户数据访问层
     */
    public ModelInvocationJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 写入一条 STARTED 调用记录。
     *
     * @param tenantId 当前租户
     * @param record 调用记录
     */
    public void insertStarted(TenantId tenantId, ModelInvocationRecord record) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(record.id()));
        params.put("runId", record.runId() == null ? null : UuidBinary.toBytes(record.runId()));
        params.put(
                "conversationId", record.conversationId() == null ? null : UuidBinary.toBytes(record.conversationId()));
        params.put("messageId", record.messageId() == null ? null : UuidBinary.toBytes(record.messageId()));
        params.put("modelId", UuidBinary.toBytes(record.modelId()));
        params.put("modelKey", record.modelKey());
        params.put("purpose", record.purpose().name());
        params.put("traceId", record.traceId());
        tenantJdbc.insert(tenantId, """
                INSERT INTO model_invocation
                    (tenant_id, id, run_id, conversation_id, message_id, model_id,
                     model_key_snapshot, purpose, status, trace_id)
                VALUES
                    (:tenantId, :id, :runId, :conversationId, :messageId, :modelId,
                     :modelKey, :purpose, 'STARTED', :traceId)
                """, params);
    }

    /**
     * 推进调用为成功。
     *
     * @param tenantId 当前租户
     * @param id 调用 ID
     * @param firstTokenMs 首字延迟，可为空
     * @param durationMs 总耗时
     * @param promptTokens 输入 token，可为空
     * @param completionTokens 输出 token，可为空
     */
    public void markSucceeded(
            TenantId tenantId,
            UUID id,
            Integer firstTokenMs,
            long durationMs,
            Integer promptTokens,
            Integer completionTokens) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(id));
        params.put("firstTokenMs", firstTokenMs);
        params.put("durationMs", durationMs);
        params.put("promptTokens", promptTokens);
        params.put("completionTokens", completionTokens);
        tenantJdbc.update(tenantId, """
                UPDATE model_invocation
                   SET status = 'SUCCEEDED',
                       first_token_ms = :firstTokenMs,
                       duration_ms = :durationMs,
                       prompt_tokens = :promptTokens,
                       completion_tokens = :completionTokens,
                       finished_at = NOW(6),
                       error_code = NULL
                 WHERE tenant_id = :tenantId
                   AND id = :id
                   AND status = 'STARTED'
                """, params);
    }

    /**
     * 推进调用为失败或取消。
     *
     * @param tenantId 当前租户
     * @param id 调用 ID
     * @param status FAILED 或 CANCELLED
     * @param errorCode 稳定错误码，可为空（取消时）
     * @param durationMs 总耗时
     * @param firstTokenMs 首字延迟，可为空
     */
    public void markTerminated(
            TenantId tenantId,
            UUID id,
            InvocationStatus status,
            String errorCode,
            long durationMs,
            Integer firstTokenMs) {
        if (status != InvocationStatus.FAILED && status != InvocationStatus.CANCELLED) {
            throw new IllegalArgumentException("terminal status must be FAILED or CANCELLED");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(id));
        params.put("status", status.name());
        params.put("errorCode", errorCode);
        params.put("durationMs", durationMs);
        params.put("firstTokenMs", firstTokenMs);
        tenantJdbc.update(tenantId, """
                UPDATE model_invocation
                   SET status = :status,
                       first_token_ms = :firstTokenMs,
                       duration_ms = :durationMs,
                       error_code = :errorCode,
                       finished_at = NOW(6)
                 WHERE tenant_id = :tenantId
                   AND id = :id
                   AND status = 'STARTED'
                """, params);
    }
}
