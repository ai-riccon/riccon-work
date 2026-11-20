package com.riccon.worker.model.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.model.InvocationStatus;
import com.riccon.worker.model.domain.ModelInvocationRecord;
import com.riccon.worker.model.persistence.ModelInvocationJdbc;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 模型调用记录仓储。
 *
 * <p>只暴露 STARTED 插入与终态推进；没有修改历史记录的入口，保证调用时间线可核对。
 * M04 成本快照的计价状态与金额由服务层计算，这里只负责持久化，避免把金额规则下沉到 SQL。
 */
@Component
public class ModelInvocationRepository {

    /** 调用记录访问。 */
    private final ModelInvocationJdbc invocationJdbc;

    /**
     * 创建调用记录仓储。
     *
     * @param invocationJdbc 调用记录访问
     */
    public ModelInvocationRepository(ModelInvocationJdbc invocationJdbc) {
        this.invocationJdbc = Objects.requireNonNull(invocationJdbc, "invocationJdbc must not be null");
    }

    /**
     * 写入 STARTED。
     *
     * @param tenantId 当前租户
     * @param record 记录
     * @param userId 发起人快照，可为空
     * @param startedAt 开始瞬时
     */
    public void started(TenantId tenantId, ModelInvocationRecord record, UUID userId, Instant startedAt) {
        invocationJdbc.insertStarted(tenantId, record, userId, startedAt);
    }

    /**
     * 标记成功并写入成本快照。
     *
     * @param tenantId 当前租户
     * @param id 调用 ID
     * @param modelId 最终成功模型 ID
     * @param modelKey 最终成功模型标识快照
     * @param firstTokenMs 首字延迟
     * @param durationMs 总耗时
     * @param promptTokens 输入 token
     * @param completionTokens 输出 token
     * @param attemptCount 实际尝试次数
     * @param cost 成本快照
     */
    public void succeeded(
            TenantId tenantId,
            UUID id,
            UUID modelId,
            String modelKey,
            Integer firstTokenMs,
            long durationMs,
            Integer promptTokens,
            Integer completionTokens,
            int attemptCount,
            CostSnapshot cost) {
        Objects.requireNonNull(cost, "cost must not be null");
        invocationJdbc.markSucceeded(
                tenantId,
                id,
                modelId,
                modelKey,
                firstTokenMs,
                durationMs,
                promptTokens,
                completionTokens,
                attemptCount,
                cost.inputPricePer1M(),
                cost.outputPricePer1M(),
                cost.currency(),
                cost.pricingVersion(),
                cost.amount(),
                cost.status().name(),
                cost.day());
    }

    /**
     * 标记失败或取消。
     *
     * @param tenantId 当前租户
     * @param id 调用 ID
     * @param status FAILED 或 CANCELLED
     * @param errorCode 错误码
     * @param durationMs 总耗时
     * @param firstTokenMs 首字延迟
     * @param attemptCount 实际尝试次数
     * @param costDay Asia/Shanghai 自然日
     */
    public void terminated(
            TenantId tenantId,
            UUID id,
            InvocationStatus status,
            String errorCode,
            long durationMs,
            Integer firstTokenMs,
            int attemptCount,
            LocalDate costDay) {
        invocationJdbc.markTerminated(tenantId, id, status, errorCode, durationMs, firstTokenMs, attemptCount, costDay);
    }

    /**
     * 读取模型价格快照。
     *
     * @param modelId 最终成功模型 ID
     * @return 价格；模型不存在时为空
     */
    public Optional<ModelPricing> findPricing(UUID modelId) {
        return invocationJdbc
                .findPricing(modelId)
                .map(pricing -> new ModelPricing(pricing.inputPricePer1M(), pricing.outputPricePer1M()));
    }

    /**
     * 模型价格。
     *
     * @param inputPricePer1M 输入价格（每百万 token），可为空
     * @param outputPricePer1M 输出价格（每百万 token），可为空
     */
    public record ModelPricing(BigDecimal inputPricePer1M, BigDecimal outputPricePer1M) {}

    /**
     * 计价状态；值与数据库 {@code cost_status} 一致。
     */
    public enum CostStatus {
        /** 价格与 token 齐全，金额已计算。 */
        PRICED,
        /** 模型未定价，不计算金额。 */
        UNPRICED,
        /** 价格齐全但上游未返回 token，不伪造金额。 */
        USAGE_UNKNOWN
    }

    /**
     * 单次成功调用的成本快照。
     *
     * @param inputPricePer1M 输入价格快照，可为空
     * @param outputPricePer1M 输出价格快照，可为空
     * @param currency 币种快照，可为空
     * @param amount 6 位小数金额，可为空
     * @param status 计价状态
     * @param pricingVersion 计价公式版本
     * @param day Asia/Shanghai 自然日
     */
    public record CostSnapshot(
            BigDecimal inputPricePer1M,
            BigDecimal outputPricePer1M,
            String currency,
            BigDecimal amount,
            CostStatus status,
            String pricingVersion,
            LocalDate day) {}
}
