package com.riccon.api.admin.cost;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.cost.CostApiTestSupport;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 平台/租户成本可见性边界（IT-COST-104 + TEN-033 成本子集）。
 *
 * <p>平台报告只到租户/模型/日聚合，结构上不含用户字段；租户报告只含本租户；
 * 明细的用户过滤跨租户统一 404；员工无入口，平台与租户端点互不越权。
 */
class CostAdminApiTest extends CostApiTestSupport {

    /** 本类创建的模型 ID；用于 {@link #cleanupCostInvocations()} 精确删除对应调用行。 */
    private final List<UUID> createdModelIds = new ArrayList<>();

    /**
     * 清理本测试写入的成本调用行。
     *
     * <p>平台报告按固定日期窗口跨租户汇总；同 namespace 第二次全量时，上次运行留下的调用行会重复计入
     * {@code totals} 与租户桶。这里只删除本测试新建模型的调用行（模型 ID 全局唯一，不会命中其他测试），
     * 不修改任何断言口径。
     */
    @AfterEach
    void cleanupCostInvocations() {
        for (UUID modelId : createdModelIds) {
            jdbcClient
                    .sql("DELETE FROM model_invocation WHERE model_id = :modelId")
                    .param("modelId", BinaryUuids.toBytes(modelId))
                    .update();
        }
        createdModelIds.clear();
    }

    /**
     * 清理保留窗口内的遗留调用行。
     *
     * <p>2027-02-03 只归 {@link #platformTotals_partitionFiveTerminalCategoriesAndFilterByTenant()} 使用；
     * 若上次运行在 {@link #cleanupCostInvocations()} 之前中断，遗留行会让“五类各 1”重复计数。
     * 删除范围是精确的北京时间自然日半开区间，不触及本套件其他测试使用的日期。
     */
    @BeforeEach
    void clearReservedCostWindow() {
        jdbcClient
                .sql("""
                        DELETE FROM model_invocation
                         WHERE started_at >= :fromInstant
                           AND started_at < :toInstant
                        """)
                .param("fromInstant", utc("2027-02-02T16:00:00Z"))
                .param("toInstant", utc("2027-02-03T16:00:00Z"))
                .update();
    }

    /**
     * IT-COST-104：平台聚合无用户字段，且可选租户过滤仍只返回聚合。
     */
    @Test
    @DisplayName("IT-COST-104：平台成本聚合不含用户维度")
    void platformReport_hasNoUserFieldsAndFiltersByTenant() {
        Fixture fixture = fixture();
        TenantOther other = createOtherTenant(fixture);
        UUID modelId = createTrackedPricedModel("3.000000", "6.000000");
        UUID ownUser = UUID.fromString(userId(fixture.admin()));
        pricedInvocation(
                fixture.tenant(),
                ownUser,
                modelId,
                Instant.parse("2026-10-05T02:00:00Z"),
                LocalDate.of(2026, 10, 5),
                1000,
                0,
                "3.000000",
                "6.000000",
                "0.003000");
        pricedInvocation(
                other.tenant(),
                other.userId(),
                modelId,
                Instant.parse("2026-10-05T03:00:00Z"),
                LocalDate.of(2026, 10, 5),
                2000,
                0,
                "3.000000",
                "6.000000",
                "0.006000");

        JsonNode tenantReport = platformReport(fixture, "2026-10-05", "2026-10-05", "TENANT", null);
        assertThat(tenantReport.path("totals").path("amount").asText()).isEqualTo("0.009000");
        assertThat(tenantReport.path("items")).hasSize(2);
        assertThat(tenantReport.path("items")).allSatisfy(item -> {
            assertThat(item.has("userId")).isFalse();
            assertThat(item.has("userDisplayName")).isFalse();
        });
        assertThat(findTenant(tenantReport, fixture.tenant()).path("amount").asText())
                .isEqualTo("0.003000");
        assertThat(findTenant(tenantReport, other.tenant()).path("amount").asText())
                .isEqualTo("0.006000");

        JsonNode filtered = platformReport(fixture, "2026-10-05", "2026-10-05", "TENANT", fixture.tenant());
        assertThat(filtered.path("items")).hasSize(1);
        assertThat(filtered.path("items").get(0).path("tenantId").asText()).isEqualTo(fixture.tenant());
        assertThat(filtered.path("totals").path("amount").asText()).isEqualTo("0.003000");

        JsonNode modelReport = platformReport(fixture, "2026-10-05", "2026-10-05", "MODEL", null);
        assertThat(modelReport.path("items")).allSatisfy(item -> {
            assertThat(item.has("userId")).isFalse();
            assertThat(item.has("userDisplayName")).isFalse();
        });
        JsonNode dayReport = platformReport(fixture, "2026-10-05", "2026-10-05", "DAY", null);
        assertThat(dayReport.path("items")).allSatisfy(item -> {
            assertThat(item.has("userId")).isFalse();
            assertThat(item.has("userDisplayName")).isFalse();
        });
    }

    /**
     * IT-COST-104：租户报告只含本租户用户；跨租户用户过滤统一 404。
     */
    @Test
    @DisplayName("IT-COST-104：租户明细跨租户 404")
    void tenantReport_isScopedAndCrossTenantUserIsNotFound() {
        Fixture fixture = fixture();
        TenantOther other = createOtherTenant(fixture);
        UUID modelId = createTrackedPricedModel("3.000000", "6.000000");
        UUID ownUser = UUID.fromString(userId(fixture.admin()));
        pricedInvocation(
                fixture.tenant(),
                ownUser,
                modelId,
                Instant.parse("2026-11-22T02:00:00Z"),
                LocalDate.of(2026, 11, 22),
                1000,
                0,
                "3.000000",
                "6.000000",
                "0.003000");
        pricedInvocation(
                other.tenant(),
                other.userId(),
                modelId,
                Instant.parse("2026-11-22T02:30:00Z"),
                LocalDate.of(2026, 11, 22),
                1000,
                0,
                "3.000000",
                "6.000000",
                "0.003000");

        ApiTestClient.ApiResponse reportResponse = client.get(
                "/api/v1/costs?from=2026-11-22&to=2026-11-22&groupBy=USER",
                fixture.admin().sessionCookie(),
                Map.of());
        assertThat(reportResponse.status()).as(reportResponse.body()).isEqualTo(200);
        JsonNode report = client.json(reportResponse);
        assertThat(report.path("items")).hasSize(1);
        assertThat(report.path("items").get(0).path("userId").asText()).isEqualTo(ownUser.toString());

        ApiTestClient.ApiResponse ownDetail = client.get(
                "/api/v1/costs/invocations?from=2026-11-22&to=2026-11-22&userId=" + ownUser,
                fixture.admin().sessionCookie(),
                Map.of());
        assertThat(ownDetail.status()).as(ownDetail.body()).isEqualTo(200);
        assertThat(client.json(ownDetail).path("total").asInt()).isEqualTo(1);

        ApiTestClient.ApiResponse foreignDetail = client.get(
                "/api/v1/costs/invocations?from=2026-11-22&to=2026-11-22&userId=" + other.userId(),
                fixture.admin().sessionCookie(),
                Map.of());
        assertThat(foreignDetail.status()).as(foreignDetail.body()).isEqualTo(404);
        assertThat(client.json(foreignDetail).path("code").asText()).isEqualTo("RICCON-TENANT-404");
    }

    /**
     * IT-COST-104：平台/租户/员工三层权限互斥；平台不接受 USER 维度。
     */
    @Test
    @DisplayName("IT-COST-104：成本端点权限与维度边界")
    void costEndpoints_enforceRoleBoundaries() {
        Fixture fixture = fixture();

        assertForbidden(
                client.get("/api/v1/admin/costs?groupBy=DAY", fixture.admin().sessionCookie(), Map.of()));
        assertForbidden(
                client.get("/api/v1/admin/costs?groupBy=DAY", fixture.member().sessionCookie(), Map.of()));
        assertForbidden(client.get("/api/v1/costs?groupBy=DAY", fixture.member().sessionCookie(), Map.of()));
        assertForbidden(client.get("/api/v1/costs/invocations", fixture.member().sessionCookie(), Map.of()));
        assertForbidden(
                client.get("/api/v1/costs?groupBy=DAY", fixture.platform().sessionCookie(), Map.of()));

        ApiTestClient.ApiResponse userGrouping = client.get(
                "/api/v1/admin/costs?groupBy=USER", fixture.platform().sessionCookie(), Map.of());
        assertThat(userGrouping.status()).as(userGrouping.body()).isEqualTo(400);
        assertThat(client.json(userGrouping).path("code").asText()).isEqualTo("RICCON-COST-400");

        ApiTestClient.ApiResponse forgedTenant =
                client.get("/api/v1/costs?tenantId=other", fixture.admin().sessionCookie(), Map.of());
        assertThat(forgedTenant.status()).as(forgedTenant.body()).isEqualTo(403);
        assertThat(client.json(forgedTenant).path("code").asText()).isEqualTo("RICCON-AUTH-403");
    }

    /**
     * F-010：平台 {@code tenantId} 过滤的契约边界。
     *
     * <p>空串与纯空白不再归一为“未提供”（否则会返回全租户聚合）；超过 64 字符同样拒绝。
     * 两者都返回 400 {@code RICCON-COST-400}，与 OpenAPI {@code minLength: 1}/{@code maxLength: 64}
     * 和 ADR-0022 §2.4 一致。
     */
    @Test
    @DisplayName("平台成本边界：空白或超长 tenantId 返回 400 RICCON-COST-400")
    void platformReport_rejectsBlankOrOverlongTenantFilter() {
        Fixture fixture = fixture();

        assertPlatformCostBadRequest(fixture, "/api/v1/admin/costs?tenantId=", "tenantId must not be blank");
        assertPlatformCostBadRequest(fixture, "/api/v1/admin/costs?tenantId=%20%20", "tenantId must not be blank");
        assertPlatformCostBadRequest(
                fixture, "/api/v1/admin/costs?tenantId=" + "t".repeat(65), "tenantId must not exceed 64 characters");

        // 64 字符是包含上限：合法过滤值；未命中租户时返回空聚合而不是 400。
        JsonNode accepted = platformReport(fixture, "2026-11-01", "2026-11-30", "TENANT", "t".repeat(64));
        assertThat(accepted.path("totals").path("pricedCalls").asInt()).isZero();
        assertThat(accepted.path("items")).isEmpty();
    }

    /**
     * F-007/F-010：平台端点使用成本专用 {@code CostPage}，页码上限 200。
     */
    @Test
    @DisplayName("平台成本边界：page=201 返回 400 RICCON-COST-400")
    void platformReport_rejectsPageAbove200() {
        Fixture fixture = fixture();

        ApiTestClient.ApiResponse tooDeep = client.get(
                "/api/v1/admin/costs?from=2026-11-01&to=2026-11-30&groupBy=DAY&page=201",
                fixture.platform().sessionCookie(),
                Map.of());
        assertThat(tooDeep.status()).as(tooDeep.body()).isEqualTo(400);
        JsonNode problem = client.json(tooDeep);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-COST-400");
        assertThat(problem.path("detail").asText()).contains("page must be between 1 and 200");

        ApiTestClient.ApiResponse deepest = client.get(
                "/api/v1/admin/costs?from=2026-11-01&to=2026-11-30&groupBy=DAY&page=200",
                fixture.platform().sessionCookie(),
                Map.of());
        assertThat(deepest.status()).as(deepest.body()).isEqualTo(200);
    }

    /**
     * F-005：平台合计的五类互斥计数。
     *
     * <p>在真实 MySQL/HTTP 下写入 PRICED、UNPRICED、USAGE_UNKNOWN、币种不一致与未分桶五类终态调用
     * （跨两个唯一租户），断言：五类各 1、金额只含匹配币种的 PRICED、五项之和等于范围内终态调用总数；
     * 并验证 {@code tenantId} 过滤后计数只统计该租户。窗口取未被其他测试使用的 2027-02-03，
     * 结束时按模型 ID 清理调用行。
     */
    @Test
    @DisplayName("IT-COST-102（平台侧）：五类互斥计数、金额币种与租户过滤")
    void platformTotals_partitionFiveTerminalCategoriesAndFilterByTenant() {
        Fixture fixture = fixture();
        TenantOther other = createOtherTenant(fixture);
        UUID modelId = createTrackedPricedModel("3.000000", "6.000000");
        UUID ownUser = UUID.fromString(userId(fixture.admin()));
        LocalDate day = LocalDate.of(2027, 2, 3);
        pricedInvocation(
                fixture.tenant(),
                ownUser,
                modelId,
                Instant.parse("2027-02-03T01:00:00Z"),
                day,
                1000,
                0,
                "3.000000",
                "6.000000",
                "0.003000");
        unpricedInvocation(other.tenant(), other.userId(), modelId, Instant.parse("2027-02-03T01:01:00Z"), day);
        usageUnknownInvocation(fixture.tenant(), ownUser, modelId, Instant.parse("2027-02-03T01:02:00Z"), day);
        currencyMismatchInvocation(other.tenant(), other.userId(), modelId, Instant.parse("2027-02-03T01:03:00Z"), day);
        unbucketedInvocation(fixture.tenant(), ownUser, modelId, Instant.parse("2027-02-03T01:04:00Z"));

        JsonNode report = platformReport(fixture, "2027-02-03", "2027-02-03", "TENANT", null);
        JsonNode totals = report.path("totals");
        assertThat(totals.path("amount").asText()).isEqualTo("0.003000");
        assertThat(totals.path("pricedCalls").asInt()).isEqualTo(1);
        assertThat(totals.path("unpricedCalls").asInt()).isEqualTo(1);
        assertThat(totals.path("usageUnknownCalls").asInt()).isEqualTo(1);
        assertThat(totals.path("currencyMismatchCalls").asInt()).isEqualTo(1);
        assertThat(totals.path("unbucketedCalls").asInt()).isEqualTo(1);
        long partitioned = totals.path("pricedCalls").asLong()
                + totals.path("unpricedCalls").asLong()
                + totals.path("usageUnknownCalls").asLong()
                + totals.path("currencyMismatchCalls").asLong()
                + totals.path("unbucketedCalls").asLong();
        assertThat(partitioned).isEqualTo(5L);
        assertThat(partitioned).isEqualTo(terminalInvocationCount(null));

        // 金额只含匹配币种的 PRICED：币种不一致租户桶金额为 0，但仍单列未定价调用。
        JsonNode otherBucket = findTenant(report, other.tenant());
        assertThat(otherBucket.path("amount").asText()).isEqualTo("0.000000");
        assertThat(otherBucket.path("pricedCalls").asInt()).isZero();
        assertThat(otherBucket.path("unpricedCalls").asInt()).isEqualTo(1);
        assertThat(findTenant(report, fixture.tenant()).path("amount").asText()).isEqualTo("0.003000");

        JsonNode filtered = platformReport(fixture, "2027-02-03", "2027-02-03", "TENANT", other.tenant());
        JsonNode filteredTotals = filtered.path("totals");
        assertThat(filteredTotals.path("amount").asText()).isEqualTo("0.000000");
        assertThat(filteredTotals.path("pricedCalls").asInt()).isZero();
        assertThat(filteredTotals.path("unpricedCalls").asInt()).isEqualTo(1);
        assertThat(filteredTotals.path("usageUnknownCalls").asInt()).isZero();
        assertThat(filteredTotals.path("currencyMismatchCalls").asInt()).isEqualTo(1);
        assertThat(filteredTotals.path("unbucketedCalls").asInt()).isZero();
        long filteredPartitioned = filteredTotals.path("pricedCalls").asLong()
                + filteredTotals.path("unpricedCalls").asLong()
                + filteredTotals.path("usageUnknownCalls").asLong()
                + filteredTotals.path("currencyMismatchCalls").asLong()
                + filteredTotals.path("unbucketedCalls").asLong();
        assertThat(filteredPartitioned).isEqualTo(2L);
        assertThat(filteredPartitioned).isEqualTo(terminalInvocationCount(other.tenant()));
    }

    /**
     * 查询平台报告并断言 200。
     *
     * @param fixture 夹具
     * @param from 起始日
     * @param to 结束日
     * @param groupBy 维度
     * @param tenantId 租户过滤，可为空
     * @return 报告
     */
    private JsonNode platformReport(Fixture fixture, String from, String to, String groupBy, String tenantId) {
        String path = "/api/v1/admin/costs?from=" + from + "&to=" + to + "&groupBy=" + groupBy;
        if (tenantId != null) {
            path += "&tenantId=" + tenantId;
        }
        ApiTestClient.ApiResponse response = client.get(path, fixture.platform().sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 按租户查找平台桶。
     *
     * @param report 平台报告
     * @param tenant 租户代码
     * @return 匹配桶
     */
    private JsonNode findTenant(JsonNode report, String tenant) {
        for (JsonNode item : report.path("items")) {
            if (tenant.equals(item.path("tenantId").asText())) {
                return item;
            }
        }
        throw new AssertionError("tenant bucket not found: " + tenant + " in " + report);
    }

    /**
     * 断言平台成本请求返回 400 + {@code RICCON-COST-400} 的指定说明。
     *
     * @param fixture 夹具
     * @param path 请求路径（含查询参数）
     * @param expectedDetail 期望出现在 detail 中的说明
     */
    private void assertPlatformCostBadRequest(Fixture fixture, String path, String expectedDetail) {
        ApiTestClient.ApiResponse response = client.get(path, fixture.platform().sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(400);
        JsonNode problem = client.json(response);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-COST-400");
        assertThat(problem.path("detail").asText()).contains(expectedDetail);
    }

    /**
     * 统计 2027-02-03（北京时间）窗口内的终态调用数。
     *
     * <p>用于校验五类互斥计数覆盖范围内全部终态行；窗口边界与 {@code CostRange} 的北京时间自然日
     * 转 UTC 半开区间一致（2027-02-03 00:00+08:00 起，次日 00:00+08:00 止）。
     *
     * @param tenant 租户；为 {@code null} 时跨租户统计
     * @return 终态调用数
     */
    private long terminalInvocationCount(String tenant) {
        String sql = """
                SELECT COUNT(*) FROM model_invocation
                 WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                   AND started_at >= :fromInstant
                   AND started_at < :toInstant
                """ + (tenant == null ? "" : "   AND tenant_id = :tenantId\n");
        Map<String, ?> params = tenant == null
                ? Map.of("fromInstant", utc("2027-02-02T16:00:00Z"), "toInstant", utc("2027-02-03T16:00:00Z"))
                : Map.of(
                        "fromInstant", utc("2027-02-02T16:00:00Z"),
                        "toInstant", utc("2027-02-03T16:00:00Z"),
                        "tenantId", tenant);
        return jdbcClient.sql(sql).params(params).query(Long.class).single();
    }

    /**
     * 断言 403。
     *
     * @param response 响应
     */
    private void assertForbidden(ApiTestClient.ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(403);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-AUTH-403");
    }

    /**
     * 创建带价格的模型并登记到清理列表。
     *
     * <p>模型 ID 只在本测试内使用，{@link #cleanupCostInvocations()} 按该 ID 删除调用行不会影响其他测试。
     *
     * @param inputPrice 输入价格
     * @param outputPrice 输出价格
     * @return 模型 ID
     */
    private UUID createTrackedPricedModel(String inputPrice, String outputPrice) {
        UUID modelId = createPricedModel(inputPrice, outputPrice);
        createdModelIds.add(modelId);
        return modelId;
    }

    /**
     * 创建第二个租户及其员工。
     *
     * @param fixture 主夹具
     * @return 其他租户
     */
    private TenantOther createOtherTenant(Fixture fixture) {
        String tenant = uniqueTenantCode("tcost");
        createTenant(fixture.platform(), tenant);
        String username =
                "costother" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        JsonNode user = createUser(fixture.platform(), tenant, username, "member-password-12", List.of("MEMBER"));
        return new TenantOther(tenant, UUID.fromString(user.path("id").asText()));
    }

    /**
     * 其他租户与员工。
     *
     * @param tenant 租户代码
     * @param userId 员工 ID
     */
    private record TenantOther(String tenant, UUID userId) {}
}
