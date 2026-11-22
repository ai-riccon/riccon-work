package com.riccon.worker.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.model.ModelPurpose;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.model.repository.ModelInvocationRepository.CostStatus;
import com.riccon.worker.model.service.ModelInvocationService;
import com.riccon.worker.model.service.ModelInvocationService.InvocationHandle;
import com.riccon.worker.model.support.ModelFailoverFixture;
import com.riccon.worker.persistence.UuidBinary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * B7 成本快照写入集成测试（IT-COST-101/102 的写入侧）。
 *
 * <p>使用真实 MySQL 与真实调用记录服务验证：6 位小数公式、价格/币种快照、PRICED/UNPRICED/
 * USAGE_UNKNOWN 状态、失败调用不写金额、历史快照不被后续价格更新改写，以及
 * {@code Asia/Shanghai} 日桶与发起人快照。
 */
class ModelCostSnapshotIntegrationTest extends WorkerIntegrationTestBase {

    /** 调用记录服务。 */
    @Autowired
    private ModelInvocationService invocationService;

    /** 模型目录夹具。 */
    private ModelFailoverFixture fixture;

    /** 每个用例重建夹具；恢复全局模型基线。 */
    @BeforeEach
    void setUp() {
        resetModelGatewayState();
        fixture = new ModelFailoverFixture(jdbcClient);
    }

    /**
     * IT-COST-101：6 位小数公式与价格/币种快照；更新模型价格后历史金额不变。
     */
    @Test
    @DisplayName("IT-COST-101：成功调用写入 6 位小数成本快照且历史不可改写")
    void succeeded_writesCostSnapshotAndKeepsHistoryImmutable() {
        TenantId tenant = tenant("cost-priced");
        UUID userId = data.createUser(tenant.value());
        UUID modelId = fixture.createModel("https://model.invalid/v1", "cost-model");
        setPrices(modelId, "3.000000", "6.000000");

        InvocationHandle first = start(tenant, userId, modelId);
        succeed(tenant, first, 1000, 500);

        Map<String, Object> firstRow = invocationRow(tenant, first);
        assertThat(firstRow.get("status")).isEqualTo("SUCCEEDED");
        assertThat(firstRow.get("userId")).isEqualTo(userId);
        assertThat(firstRow.get("inputPrice")).isEqualTo(new BigDecimal("3.000000"));
        assertThat(firstRow.get("outputPrice")).isEqualTo(new BigDecimal("6.000000"));
        assertThat(firstRow.get("priceCurrency")).isEqualTo("CNY");
        assertThat(firstRow.get("pricingVersion")).isEqualTo("v1");
        // 1000×3 + 500×6 = 6000；6000/1_000_000 = 0.006000。
        assertThat(firstRow.get("costAmount")).isEqualTo(new BigDecimal("0.006000"));
        assertThat(firstRow.get("costStatus")).isEqualTo(CostStatus.PRICED.name());
        assertThat(firstRow.get("costDay")).isEqualTo(expectedDay(first.startedAt()));
        assertThat(firstRow.get("startedAt")).isEqualTo(java.time.Instant.ofEpochMilli(first.startedAt()));

        setPrices(modelId, "30.000000", "60.000000");

        assertThat(invocationRow(tenant, first)).isEqualTo(firstRow);

        InvocationHandle second = start(tenant, userId, modelId);
        succeed(tenant, second, 1000, 500);
        assertThat(invocationRow(tenant, second).get("costAmount")).isEqualTo(new BigDecimal("0.060000"));
    }

    /**
     * IT-COST-101：6 位 HALF_UP 舍入；小于一个最小单位的调用保持 0.000000。
     */
    @Test
    @DisplayName("IT-COST-101：成本按 6 位 HALF_UP 舍入")
    void succeeded_roundsHalfUpAtSixDecimals() {
        TenantId tenant = tenant("cost-rounding");
        UUID userId = data.createUser(tenant.value());
        UUID modelId = fixture.createModel("https://model.invalid/v1", "cost-rounding-model");
        setPrices(modelId, "0.500000", "0.000000");

        InvocationHandle five = start(tenant, userId, modelId);
        succeed(tenant, five, 5, 0);
        // 5×0.5/1_000_000 = 0.0000025；6 位 HALF_UP = 0.000003。
        assertThat(invocationRow(tenant, five).get("costAmount")).isEqualTo(new BigDecimal("0.000003"));

        InvocationHandle subUnit = start(tenant, userId, modelId);
        succeed(tenant, subUnit, 1, 0);
        // 1×0.5/1_000_000 = 0.0000005；6 位 HALF_UP = 0.000001。
        assertThat(invocationRow(tenant, subUnit).get("costAmount")).isEqualTo(new BigDecimal("0.000001"));
    }

    /**
     * IT-COST-102：模型未定价时 PRICED 不成立，金额与币种为空。
     */
    @Test
    @DisplayName("IT-COST-102：未定价模型写入 UNPRICED 且不计算金额")
    void succeeded_withoutPrices_marksUnpriced() {
        TenantId tenant = tenant("cost-unpriced");
        UUID userId = data.createUser(tenant.value());
        UUID modelId = fixture.createModel("https://model.invalid/v1", "cost-unpriced-model");

        InvocationHandle handle = start(tenant, userId, modelId);
        succeed(tenant, handle, 100, 50);

        Map<String, Object> row = invocationRow(tenant, handle);
        assertThat(row.get("costStatus")).isEqualTo(CostStatus.UNPRICED.name());
        assertThat(row.get("costAmount")).isNull();
        assertThat(row.get("priceCurrency")).isNull();
        assertThat(row.get("inputPrice")).isNull();
        assertThat(row.get("costDay")).isEqualTo(expectedDay(handle.startedAt()));
    }

    /**
     * IT-COST-102：价格齐全但上游未返回 usage 时记 USAGE_UNKNOWN，不估算 token。
     */
    @Test
    @DisplayName("IT-COST-102：缺少 usage 时写入 USAGE_UNKNOWN 且不伪造金额")
    void succeeded_withoutUsage_marksUsageUnknown() {
        TenantId tenant = tenant("cost-usage-unknown");
        UUID userId = data.createUser(tenant.value());
        UUID modelId = fixture.createModel("https://model.invalid/v1", "cost-usage-model");
        setPrices(modelId, "3.000000", "6.000000");

        InvocationHandle handle = start(tenant, userId, modelId);
        succeed(tenant, handle, null, null);

        Map<String, Object> row = invocationRow(tenant, handle);
        assertThat(row.get("costStatus")).isEqualTo(CostStatus.USAGE_UNKNOWN.name());
        assertThat(row.get("costAmount")).isNull();
        assertThat(row.get("priceCurrency")).isEqualTo("CNY");
        assertThat(row.get("inputPrice")).isEqualTo(new BigDecimal("3.000000"));
        assertThat(row.get("promptTokens")).isNull();
        assertThat(row.get("costDay")).isEqualTo(expectedDay(handle.startedAt()));
    }

    /**
     * IT-COST-102：失败调用写 USAGE_UNKNOWN 与日桶，不写价格或金额；同一调用不产生第二行。
     */
    @Test
    @DisplayName("IT-COST-102：失败调用不写成本且只保留一行")
    void failed_writesUsageUnknownWithoutCost() {
        TenantId tenant = tenant("cost-failed");
        UUID userId = data.createUser(tenant.value());
        UUID modelId = fixture.createModel("https://model.invalid/v1", "cost-failed-model");
        setPrices(modelId, "3.000000", "6.000000");

        InvocationHandle handle = start(tenant, userId, modelId);
        TenantContext.runWithTenant(
                tenant,
                () -> invocationService.failed(handle, "trace-cost", null, "RICCON-MODEL-502", true, null, true, 1));

        Map<String, Object> row = invocationRow(tenant, handle);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("costStatus")).isEqualTo(CostStatus.USAGE_UNKNOWN.name());
        assertThat(row.get("costAmount")).isNull();
        assertThat(row.get("priceCurrency")).isNull();
        assertThat(row.get("inputPrice")).isNull();
        assertThat(row.get("costDay")).isEqualTo(expectedDay(handle.startedAt()));
        assertThat(countInvocations(tenant, handle)).isEqualTo(1);
    }

    /**
     * 更新模型价格。
     *
     * @param modelId 模型 ID
     * @param input 输入价格
     * @param output 输出价格
     */
    private void setPrices(UUID modelId, String input, String output) {
        jdbcClient
                .sql("""
                        UPDATE model
                           SET input_price_per_1m = :input, output_price_per_1m = :output
                         WHERE id = :id
                        """)
                .param("input", new BigDecimal(input))
                .param("output", new BigDecimal(output))
                .param("id", UuidBinary.toBytes(modelId))
                .update();
    }

    /**
     * 在租户作用域内开始一次调用。
     *
     * @param tenant 租户
     * @param userId 发起人
     * @param modelId 模型
     * @return 调用句柄
     */
    private InvocationHandle start(TenantId tenant, UUID userId, UUID modelId) {
        return TenantContext.callWithTenant(
                tenant,
                () -> invocationService.start(
                        tenant,
                        null,
                        null,
                        null,
                        userId,
                        modelId,
                        "cost-model-key",
                        ModelPurpose.CHAT,
                        "trace-cost",
                        null));
    }

    /**
     * 在租户作用域内写成功终态。
     *
     * @param tenant 租户
     * @param handle 调用句柄
     * @param promptTokens 输入 token
     * @param completionTokens 输出 token
     */
    private void succeed(TenantId tenant, InvocationHandle handle, Integer promptTokens, Integer completionTokens) {
        TenantContext.runWithTenant(
                tenant,
                () -> invocationService.succeeded(
                        handle,
                        handle.modelId(),
                        "cost-model-key",
                        "trace-cost",
                        null,
                        10,
                        promptTokens,
                        completionTokens,
                        1));
    }

    /**
     * 读取调用记录的成本快照列。
     *
     * @param tenant 租户
     * @param handle 调用句柄
     * @return 列名到值的映射；允许 null 值
     */
    private Map<String, Object> invocationRow(TenantId tenant, InvocationHandle handle) {
        return jdbcClient
                .sql("""
                        SELECT status, user_id, prompt_tokens, input_price_per_1m, output_price_per_1m,
                               price_currency, pricing_version, cost_amount, cost_status, cost_day, started_at
                          FROM model_invocation
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenant.value())
                .param("id", UuidBinary.toBytes(handle.id()))
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("status", rs.getString("status"));
                    row.put("userId", UuidBinary.fromBytes(rs.getBytes("user_id")));
                    row.put("promptTokens", rs.getObject("prompt_tokens", Integer.class));
                    row.put("inputPrice", rs.getBigDecimal("input_price_per_1m"));
                    row.put("outputPrice", rs.getBigDecimal("output_price_per_1m"));
                    row.put("priceCurrency", rs.getString("price_currency"));
                    row.put("pricingVersion", rs.getString("pricing_version"));
                    row.put("costAmount", rs.getBigDecimal("cost_amount"));
                    row.put("costStatus", rs.getString("cost_status"));
                    row.put("costDay", rs.getObject("cost_day", LocalDate.class));
                    row.put(
                            "startedAt",
                            rs.getObject("started_at", LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC));
                    return row;
                })
                .single();
    }

    /**
     * 统计同一调用的行数，验证失败/重试不重复插入成本行。
     *
     * @param tenant 租户
     * @param handle 调用句柄
     * @return 行数
     */
    private long countInvocations(TenantId tenant, InvocationHandle handle) {
        return jdbcClient
                .sql("""
                        SELECT COUNT(*) FROM model_invocation
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenant.value())
                .param("id", UuidBinary.toBytes(handle.id()))
                .query(Long.class)
                .single();
    }

    /**
     * 创建测试租户；每个测试唯一。
     *
     * @param tenantPrefix 可读租户前缀
     * @return 租户
     */
    private TenantId tenant(String tenantPrefix) {
        String tenantId = uniqueTenant(tenantPrefix);
        data.createTenant(tenantId);
        return TenantId.of(tenantId);
    }

    /**
     * 独立计算期望日桶，避免测试复用被测转换逻辑。
     *
     * @param epochMillis 开始瞬时
     * @return 北京时间自然日
     */
    private static LocalDate expectedDay(long epochMillis) {
        return java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .toLocalDate();
    }
}
