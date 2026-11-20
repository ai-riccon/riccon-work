package com.riccon.worker.model.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.model.InvocationStatus;
import com.riccon.worker.model.domain.ModelInvocationRecord;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 模型调用记录访问。
 *
 * <p>调用记录是运行详情与时间线的数据来源，不替代审计。所有语句带 {@code tenant_id}，
 * 由 {@link TenantJdbc} 强制；失败更新只允许从 STARTED 迁移，重复投递不会覆盖终态。
 *
 * <p>M04 成本快照：成功终态一次写入价格、币种、金额、状态与 {@code Asia/Shanghai} 日桶；
 * 失败/取消只写 {@code USAGE_UNKNOWN} 与日桶，不写价格或金额（ADR-0022 §2.1–§2.3）。
 * 价格只从快照列读取，后续更新 {@code model} 价格不会改写历史金额。
 */
@Component
public class ModelInvocationJdbc {

    /** 全局模型价格查询；只读目录价格，不写回。 */
    private static final String SELECT_PRICING = """
            SELECT input_price_per_1m, output_price_per_1m
              FROM model
             WHERE id = :id
            """;

    /** 租户数据访问层。 */
    private final TenantJdbc tenantJdbc;

    /** 全局目录只读客户端（{@code model} 是全局表，ADR-0012 例外）。 */
    private final JdbcClient jdbcClient;

    /**
     * 创建调用记录访问。
     *
     * @param tenantJdbc 租户数据访问层
     * @param jdbcClient 全局目录只读客户端
     */
    public ModelInvocationJdbc(TenantJdbc tenantJdbc, JdbcClient jdbcClient) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    /**
     * 写入一条 STARTED 调用记录。
     *
     * <p>{@code started_at} 显式取调用句柄的时钟值，而不是数据库 {@code NOW(6)}：
     * 成本日桶由同一瞬时推导，避免两端时钟差在午夜边界产生跨日不一致。
     *
     * @param tenantId 当前租户
     * @param record 调用记录
     * @param userId 发起人快照，可为空（历史/系统调用）
     * @param startedAt 调用开始瞬时
     */
    public void insertStarted(TenantId tenantId, ModelInvocationRecord record, UUID userId, Instant startedAt) {
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
        params.put("userId", userId == null ? null : UuidBinary.toBytes(userId));
        params.put("startedAt", LocalDateTime.ofInstant(startedAt, ZoneOffset.UTC));
        tenantJdbc.insert(tenantId, """
                INSERT INTO model_invocation
                    (tenant_id, id, run_id, conversation_id, message_id, model_id,
                     model_key_snapshot, purpose, status, trace_id, user_id, started_at)
                VALUES
                    (:tenantId, :id, :runId, :conversationId, :messageId, :modelId,
                     :modelKey, :purpose, 'STARTED', :traceId, :userId, :startedAt)
                """, params);
    }

    /**
     * 推进调用为成功并写入成本快照。
     *
     * <p>只在最终成功调用上写入一次；同一次调用内部的失败重试不写价格或金额，因此不会重复计成本。
     *
     * @param tenantId 当前租户
     * @param id 调用 ID
     * @param modelId 最终成功模型 ID（降级后与首个尝试不同）
     * @param modelKey 最终成功模型标识快照
     * @param firstTokenMs 首字延迟，可为空
     * @param durationMs 总耗时
     * @param promptTokens 输入 token，可为空
     * @param completionTokens 输出 token，可为空
     * @param attemptCount 实际尝试次数
     * @param inputPricePer1M 输入价格快照，可为空
     * @param outputPricePer1M 输出价格快照，可为空
     * @param priceCurrency 币种快照，可为空
     * @param pricingVersion 计价公式版本
     * @param costAmount 6 位小数金额，可为空
     * @param costStatus PRICED、UNPRICED 或 USAGE_UNKNOWN
     * @param costDay Asia/Shanghai 自然日
     */
    public void markSucceeded(
            TenantId tenantId,
            UUID id,
            UUID modelId,
            String modelKey,
            Integer firstTokenMs,
            long durationMs,
            Integer promptTokens,
            Integer completionTokens,
            int attemptCount,
            BigDecimal inputPricePer1M,
            BigDecimal outputPricePer1M,
            String priceCurrency,
            String pricingVersion,
            BigDecimal costAmount,
            String costStatus,
            LocalDate costDay) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(id));
        params.put("modelId", UuidBinary.toBytes(modelId));
        params.put("modelKey", modelKey);
        params.put("firstTokenMs", firstTokenMs);
        params.put("durationMs", durationMs);
        params.put("promptTokens", promptTokens);
        params.put("completionTokens", completionTokens);
        params.put("attemptCount", attemptCount);
        params.put("inputPricePer1M", inputPricePer1M);
        params.put("outputPricePer1M", outputPricePer1M);
        params.put("priceCurrency", priceCurrency);
        params.put("pricingVersion", pricingVersion);
        params.put("costAmount", costAmount);
        params.put("costStatus", costStatus);
        params.put("costDay", costDay);
        tenantJdbc.update(tenantId, """
                UPDATE model_invocation
                   SET status = 'SUCCEEDED',
                       model_id = :modelId,
                       model_key_snapshot = :modelKey,
                       first_token_ms = :firstTokenMs,
                       duration_ms = :durationMs,
                       prompt_tokens = :promptTokens,
                       completion_tokens = :completionTokens,
                       attempt_count = :attemptCount,
                       input_price_per_1m = :inputPricePer1M,
                       output_price_per_1m = :outputPricePer1M,
                       price_currency = :priceCurrency,
                       pricing_version = :pricingVersion,
                       cost_amount = :costAmount,
                       cost_status = :costStatus,
                       cost_day = :costDay,
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
     * <p>失败/取消也计入成本报告中的调用量：写 {@code USAGE_UNKNOWN} 与日桶，但价格、币种和金额保持空，
     * 不伪造 token 或成本（ADR-0022 §2.3）。
     *
     * @param tenantId 当前租户
     * @param id 调用 ID
     * @param status FAILED 或 CANCELLED
     * @param errorCode 稳定错误码，可为空（取消时）
     * @param durationMs 总耗时
     * @param firstTokenMs 首字延迟，可为空
     * @param attemptCount 实际尝试次数
     * @param costDay Asia/Shanghai 自然日
     */
    public void markTerminated(
            TenantId tenantId,
            UUID id,
            InvocationStatus status,
            String errorCode,
            long durationMs,
            Integer firstTokenMs,
            int attemptCount,
            LocalDate costDay) {
        if (status != InvocationStatus.FAILED && status != InvocationStatus.CANCELLED) {
            throw new IllegalArgumentException("terminal status must be FAILED or CANCELLED");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(id));
        params.put("status", status.name());
        params.put("errorCode", errorCode);
        params.put("durationMs", durationMs);
        params.put("firstTokenMs", firstTokenMs);
        params.put("attemptCount", attemptCount);
        params.put("costDay", costDay);
        tenantJdbc.update(tenantId, """
                UPDATE model_invocation
                   SET status = :status,
                       first_token_ms = :firstTokenMs,
                       duration_ms = :durationMs,
                       error_code = :errorCode,
                       attempt_count = :attemptCount,
                       cost_status = 'USAGE_UNKNOWN',
                       cost_day = :costDay,
                       finished_at = NOW(6)
                 WHERE tenant_id = :tenantId
                   AND id = :id
                   AND status = 'STARTED'
                """, params);
    }

    /**
     * 读取模型当前价格；只在成功终态读取一次作为调用快照。
     *
     * <p>M04 不提供价格编辑端点，价格只由部署种子/运维维护；一次成功调用只读一次价格，
     * 因此同一回答不会混用两个价格。
     *
     * @param modelId 最终成功模型 ID
     * @return 价格行；模型不存在时为空
     */
    public Optional<ModelPricing> findPricing(UUID modelId) {
        return jdbcClient
                .sql(SELECT_PRICING)
                .param("id", UuidBinary.toBytes(modelId))
                .query((rs, rowNum) -> new ModelPricing(
                        rs.getBigDecimal("input_price_per_1m"), rs.getBigDecimal("output_price_per_1m")))
                .optional();
    }

    /**
     * 模型价格快照（可为空，表示未定价）。
     *
     * @param inputPricePer1M 输入价格
     * @param outputPricePer1M 输出价格
     */
    public record ModelPricing(BigDecimal inputPricePer1M, BigDecimal outputPricePer1M) {}
}
