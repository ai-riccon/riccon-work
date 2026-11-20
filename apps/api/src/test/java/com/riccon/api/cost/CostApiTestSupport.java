package com.riccon.api.cost;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ResearchApiTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * M04 成本报告集成测试基座。
 *
 * <p>租户、账号与工作空间通过公开接口创建；模型目录与调用记录由测试直接落库模拟 Worker 写入，
 * 不调用真实模型。每个 fixture 使用唯一租户代码与模型 key，测试之间互不干扰。
 */
public abstract class CostApiTestSupport extends ResearchApiTestSupport {

    /**
     * 创建带价格的模型。
     *
     * @param inputPrice 输入价格
     * @param outputPrice 输出价格
     * @return 模型 ID
     */
    protected UUID createPricedModel(String inputPrice, String outputPrice) {
        return createModel(new BigDecimal(inputPrice), new BigDecimal(outputPrice));
    }

    /**
     * 创建未定价模型。
     *
     * @return 模型 ID
     */
    protected UUID createUnpricedModel() {
        return createModel(null, null);
    }

    /**
     * 写入一条 PRICED 调用。
     *
     * @param tenant 租户
     * @param userId 用户
     * @param modelId 模型
     * @param startedAt 开始瞬时
     * @param costDay 成本日桶
     * @param promptTokens 输入 token
     * @param completionTokens 输出 token
     * @param inputPrice 输入价格快照
     * @param outputPrice 输出价格快照
     * @param amount 金额快照
     * @return 调用 ID
     */
    protected UUID pricedInvocation(
            String tenant,
            UUID userId,
            UUID modelId,
            Instant startedAt,
            LocalDate costDay,
            int promptTokens,
            int completionTokens,
            String inputPrice,
            String outputPrice,
            String amount) {
        return insertInvocation(
                tenant,
                userId,
                modelId,
                "SUCCEEDED",
                startedAt,
                costDay,
                promptTokens,
                completionTokens,
                new BigDecimal(inputPrice),
                new BigDecimal(outputPrice),
                "CNY",
                new BigDecimal(amount),
                "PRICED");
    }

    /**
     * 写入一条 UNPRICED 调用（模型未定价）。
     *
     * @param tenant 租户
     * @param userId 用户
     * @param modelId 模型
     * @param startedAt 开始瞬时
     * @param costDay 成本日桶
     * @return 调用 ID
     */
    protected UUID unpricedInvocation(String tenant, UUID userId, UUID modelId, Instant startedAt, LocalDate costDay) {
        return insertInvocation(
                tenant, userId, modelId, "SUCCEEDED", startedAt, costDay, 100, 50, null, null, null, null, "UNPRICED");
    }

    /**
     * 写入一条 USAGE_UNKNOWN 调用（价格齐全但无 token）。
     *
     * @param tenant 租户
     * @param userId 用户
     * @param modelId 模型
     * @param startedAt 开始瞬时
     * @param costDay 成本日桶
     * @return 调用 ID
     */
    protected UUID usageUnknownInvocation(
            String tenant, UUID userId, UUID modelId, Instant startedAt, LocalDate costDay) {
        return insertInvocation(
                tenant,
                userId,
                modelId,
                "CANCELLED",
                startedAt,
                costDay,
                null,
                null,
                new BigDecimal("3.000000"),
                new BigDecimal("6.000000"),
                "CNY",
                null,
                "USAGE_UNKNOWN");
    }

    /**
     * 写入一条历史/回滚窗口调用（cost_day 为空，计 unbucketed）。
     *
     * @param tenant 租户
     * @param userId 用户
     * @param modelId 模型
     * @param startedAt 开始瞬时
     * @return 调用 ID
     */
    protected UUID unbucketedInvocation(String tenant, UUID userId, UUID modelId, Instant startedAt) {
        return insertInvocation(
                tenant,
                userId,
                modelId,
                "SUCCEEDED",
                startedAt,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "USAGE_UNKNOWN");
    }

    /**
     * 写入一条币种不一致的 PRICED 调用（金额不计入合计）。
     *
     * @param tenant 租户
     * @param userId 用户
     * @param modelId 模型
     * @param startedAt 开始瞬时
     * @param costDay 成本日桶
     * @return 调用 ID
     */
    protected UUID currencyMismatchInvocation(
            String tenant, UUID userId, UUID modelId, Instant startedAt, LocalDate costDay) {
        return insertInvocation(
                tenant,
                userId,
                modelId,
                "SUCCEEDED",
                startedAt,
                costDay,
                1000,
                0,
                new BigDecimal("3.000000"),
                new BigDecimal("6.000000"),
                "USD",
                new BigDecimal("1.000000"),
                "PRICED");
    }

    /**
     * 把 ISO UTC 瞬时转换为数据库 UTC 时间。
     *
     * @param instant ISO 瞬时
     * @return UTC LocalDateTime
     */
    protected LocalDateTime utc(String instant) {
        return LocalDateTime.ofInstant(Instant.parse(instant), ZoneOffset.UTC);
    }

    /**
     * 创建模型目录行。
     *
     * @param inputPrice 输入价格，可为空
     * @param outputPrice 输出价格，可为空
     * @return 模型 ID
     */
    private UUID createModel(BigDecimal inputPrice, BigDecimal outputPrice) {
        UUID providerId = UuidV7.generate();
        UUID modelId = UuidV7.generate();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcClient
                .sql("""
                        INSERT INTO model_provider (id, code, name, protocol, base_url, status, created_at, updated_at)
                        VALUES (:id, :code, 'Cost Provider', 'OPENAI_COMPATIBLE',
                                'https://model.invalid/v1', 'ACTIVE', NOW(6), NOW(6))
                        """)
                .param("id", BinaryUuids.toBytes(providerId))
                .param("code", "cost-" + suffix)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO model (
                            id, provider_id, model_key, display_name, context_window, max_output_tokens,
                            input_price_per_1m, output_price_per_1m, status, created_at, updated_at
                        ) VALUES (
                            :id, :providerId, :modelKey, :displayName, 8192, 1024,
                            :inputPrice, :outputPrice, 'ACTIVE', NOW(6), NOW(6)
                        )
                        """)
                .param("id", BinaryUuids.toBytes(modelId))
                .param("providerId", BinaryUuids.toBytes(providerId))
                .param("modelKey", "cost-model-" + suffix)
                .param("displayName", "Cost Model " + suffix)
                .param("inputPrice", inputPrice)
                .param("outputPrice", outputPrice)
                .update();
        return modelId;
    }

    /**
     * 写入调用记录。
     *
     * @param tenant 租户
     * @param userId 用户，可为空
     * @param modelId 模型
     * @param status 调用状态
     * @param startedAt 开始瞬时
     * @param costDay 成本日桶，可为空
     * @param promptTokens 输入 token，可为空
     * @param completionTokens 输出 token，可为空
     * @param inputPrice 输入价格，可为空
     * @param outputPrice 输出价格，可为空
     * @param currency 币种，可为空
     * @param amount 金额，可为空
     * @param costStatus 计价状态
     * @return 调用 ID
     */
    private UUID insertInvocation(
            String tenant,
            UUID userId,
            UUID modelId,
            String status,
            Instant startedAt,
            LocalDate costDay,
            Integer promptTokens,
            Integer completionTokens,
            BigDecimal inputPrice,
            BigDecimal outputPrice,
            String currency,
            BigDecimal amount,
            String costStatus) {
        UUID id = UuidV7.generate();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenant);
        params.put("id", BinaryUuids.toBytes(id));
        params.put("userId", userId == null ? null : BinaryUuids.toBytes(userId));
        params.put("modelId", BinaryUuids.toBytes(modelId));
        params.put("modelKey", "cost-model-snapshot");
        params.put("status", status);
        params.put("startedAt", LocalDateTime.ofInstant(startedAt, ZoneOffset.UTC));
        params.put("costDay", costDay);
        params.put("promptTokens", promptTokens);
        params.put("completionTokens", completionTokens);
        params.put("inputPrice", inputPrice);
        params.put("outputPrice", outputPrice);
        params.put("currency", currency);
        params.put("amount", amount);
        params.put("costStatus", costStatus);
        jdbcClient.sql("""
                        INSERT INTO model_invocation (
                            tenant_id, id, run_id, conversation_id, message_id, model_id,
                            model_key_snapshot, purpose, status, first_token_ms, duration_ms,
                            prompt_tokens, completion_tokens, trace_id, started_at, finished_at,
                            user_id, cost_day, input_price_per_1m, output_price_per_1m,
                            price_currency, pricing_version, cost_amount, cost_status, attempt_count
                        ) VALUES (
                            :tenantId, :id, NULL, NULL, NULL, :modelId,
                            :modelKey, 'CHAT', :status, 10, 100,
                            :promptTokens, :completionTokens, 'cost-trace', :startedAt, :startedAt,
                            :userId, :costDay, :inputPrice, :outputPrice,
                            :currency, 'v1', :amount, :costStatus, 1
                        )
                        """).params(params).update();
        return id;
    }
}
