package com.riccon.api.cost;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.cost.service.CostMetrics;
import com.riccon.api.support.ApiTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 租户成本报告、明细与查询保护（IT-COST-101/102/103）。
 *
 * <p>金额断言使用字符串（6 位小数）；时间边界使用固定 UTC 瞬时模拟北京时间 23:59:59.999
 * 与次日 00:00:00。成本查看不写审计，本类不做审计断言。
 */
class CostApiTest extends CostApiTestSupport {

    /** 指标注册表。 */
    @Autowired
    private MeterRegistry meterRegistry;

    /** Prometheus 注册表；用于断言导出的指标族名。 */
    @Autowired
    private PrometheusMeterRegistry prometheusMeterRegistry;

    /**
     * IT-COST-101：金额按快照列返回；更新模型价格后历史金额不变；明细返回价格快照。
     */
    @Test
    @DisplayName("IT-COST-101：成本金额使用价格快照且历史不可改写")
    void report_usesPriceSnapshotAndKeepsHistory() {
        Fixture fixture = fixture();
        UUID modelId = createPricedModel("3.000000", "6.000000");
        UUID invoker = UUID.fromString(userId(fixture.admin()));
        UUID invocationId = pricedInvocation(
                fixture.tenant(),
                invoker,
                modelId,
                Instant.parse("2026-11-20T02:00:00Z"),
                LocalDate.of(2026, 11, 20),
                1000,
                0,
                "3.000000",
                "6.000000",
                "0.003000");

        JsonNode first = tenantReport(fixture, "2026-11-20", "2026-11-20", "MODEL");
        assertThat(first.path("currency").asText()).isEqualTo("CNY");
        assertThat(first.path("currencyMinorUnit").asInt()).isEqualTo(2);
        assertThat(first.path("disclaimer").asText()).isEqualTo("成本为按调用时价格快照估算，不是供应商账单。");
        assertThat(first.path("totals").path("amount").asText()).isEqualTo("0.003000");
        assertThat(first.path("totals").path("pricedCalls").asInt()).isEqualTo(1);
        assertThat(first.path("items")).hasSize(1);
        JsonNode bucket = first.path("items").get(0);
        assertThat(bucket.path("key").asText()).isEqualTo(modelId.toString());
        assertThat(bucket.path("modelId").asText()).isEqualTo(modelId.toString());
        assertThat(bucket.path("modelDisplayName").asText()).startsWith("Cost Model");
        assertThat(bucket.path("amount").asText()).isEqualTo("0.003000");
        assertThat(bucket.has("userId")).isTrue();

        // 价格更新后历史金额与价格快照均不变：查询只读 model_invocation 快照列。
        jdbcClient
                .sql("UPDATE model SET input_price_per_1m = 30.000000, output_price_per_1m = 60.000000"
                        + " WHERE id = :id")
                .param("id", com.riccon.api.persistence.BinaryUuids.toBytes(modelId))
                .update();

        JsonNode second = tenantReport(fixture, "2026-11-20", "2026-11-20", "MODEL");
        assertThat(second.path("totals").path("amount").asText()).isEqualTo("0.003000");
        assertThat(second.path("items").get(0).path("amount").asText()).isEqualTo("0.003000");

        JsonNode detail = tenantInvocations(fixture, "2026-11-20", "2026-11-20", null, null);
        assertThat(detail.path("total").asInt()).isEqualTo(1);
        JsonNode item = detail.path("items").get(0);
        assertThat(item.path("id").asText()).isEqualTo(invocationId.toString());
        assertThat(item.path("amount").asText()).isEqualTo("0.003000");
        assertThat(item.path("inputPricePer1M").asText()).isEqualTo("3.000000");
        assertThat(item.path("outputPricePer1M").asText()).isEqualTo("6.000000");
        assertThat(item.path("priceCurrency").asText()).isEqualTo("CNY");
        assertThat(item.path("pricingStatus").asText()).isEqualTo("PRICED");
        assertThat(item.path("startedAt").asText()).isEqualTo("2026-11-20T10:00:00+08:00");
        assertThat(item.path("userDisplayName").asText()).isNotBlank();
    }

    /**
     * IT-COST-102：PRICED/UNPRICED/USAGE_UNKNOWN/币种不一致/unbucketed 分桶与金额口径。
     */
    @Test
    @DisplayName("IT-COST-102：未定价、用量未知、币种不一致与未分桶单列且不计金额")
    void report_separatesUnpricedUsageUnknownAndUnbucketed() {
        Fixture fixture = fixture();
        UUID modelId = createPricedModel("3.000000", "6.000000");
        UUID invoker = UUID.fromString(userId(fixture.admin()));
        LocalDate day = LocalDate.of(2026, 11, 21);
        pricedInvocation(
                fixture.tenant(),
                invoker,
                modelId,
                Instant.parse("2026-11-21T01:00:00Z"),
                day,
                1000,
                0,
                "3.000000",
                "6.000000",
                "0.003000");
        unpricedInvocation(fixture.tenant(), invoker, modelId, Instant.parse("2026-11-21T01:01:00Z"), day);
        usageUnknownInvocation(fixture.tenant(), invoker, modelId, Instant.parse("2026-11-21T01:02:00Z"), day);
        currencyMismatchInvocation(fixture.tenant(), invoker, modelId, Instant.parse("2026-11-21T01:03:00Z"), day);
        unbucketedInvocation(fixture.tenant(), invoker, modelId, Instant.parse("2026-11-21T01:04:00Z"));

        JsonNode report = tenantReport(fixture, "2026-11-21", "2026-11-21", "DAY");
        JsonNode totals = report.path("totals");
        assertThat(totals.path("amount").asText()).isEqualTo("0.003000");
        assertThat(totals.path("pricedCalls").asInt()).isEqualTo(1);
        assertThat(totals.path("unpricedCalls").asInt()).isEqualTo(1);
        assertThat(totals.path("usageUnknownCalls").asInt()).isEqualTo(1);
        assertThat(totals.path("currencyMismatchCalls").asInt()).isEqualTo(1);
        assertThat(totals.path("unbucketedCalls").asInt()).isEqualTo(1);
        assertThat(report.path("items")).hasSize(1);
        JsonNode bucket = report.path("items").get(0);
        assertThat(bucket.path("key").asText()).isEqualTo("2026-11-21");
        assertThat(bucket.path("amount").asText()).isEqualTo("0.003000");
        assertThat(bucket.path("pricedCalls").asInt()).isEqualTo(1);
        assertThat(bucket.path("unpricedCalls").asInt()).isEqualTo(1);
        assertThat(bucket.path("usageUnknownCalls").asInt()).isEqualTo(1);

        JsonNode detail = tenantInvocations(fixture, "2026-11-21", "2026-11-21", null, null);
        assertThat(detail.path("total").asInt()).isEqualTo(5);
        assertThat(detail.path("items"))
                .anySatisfy(
                        item -> assertThat(item.path("pricingStatus").asText()).isEqualTo("UNPRICED"));
        assertThat(detail.path("items"))
                .anySatisfy(
                        item -> assertThat(item.path("pricingStatus").asText()).isEqualTo("USAGE_UNKNOWN"));
        JsonNode unpriced = itemByStatus(detail, "UNPRICED");
        assertThat(unpriced.path("amount").isNull()).isTrue();
        assertThat(unpriced.path("priceCurrency").isNull()).isTrue();
        JsonNode unknown = itemByStatus(detail, "USAGE_UNKNOWN");
        assertThat(unknown.path("amount").isNull()).isTrue();
    }

    /**
     * IT-COST-103：Asia/Shanghai 日桶边界、历史 cost_day 为空与查询范围校验。
     */
    @Test
    @DisplayName("IT-COST-103：日桶按北京时间且历史未分桶单列")
    void report_usesShanghaiDayBoundaryAndCountsUnbucketed() {
        Fixture fixture = fixture();
        UUID modelId = createPricedModel("3.000000", "6.000000");
        UUID invoker = UUID.fromString(userId(fixture.admin()));
        pricedInvocation(
                fixture.tenant(),
                invoker,
                modelId,
                Instant.parse("2026-11-30T15:59:59.999Z"),
                LocalDate.of(2026, 11, 30),
                1000,
                0,
                "3.000000",
                "6.000000",
                "0.003000");
        pricedInvocation(
                fixture.tenant(),
                invoker,
                modelId,
                Instant.parse("2026-11-30T16:00:00Z"),
                LocalDate.of(2026, 12, 1),
                1000,
                0,
                "3.000000",
                "6.000000",
                "0.003000");
        unbucketedInvocation(fixture.tenant(), invoker, modelId, Instant.parse("2026-12-01T02:00:00Z"));

        JsonNode both = tenantReport(fixture, "2026-11-30", "2026-12-01", "DAY");
        assertThat(both.path("items")).hasSize(2);
        assertThat(both.path("items").get(0).path("key").asText()).isEqualTo("2026-11-30");
        assertThat(both.path("items").get(1).path("key").asText()).isEqualTo("2026-12-01");
        assertThat(both.path("totals").path("unbucketedCalls").asInt()).isEqualTo(1);
        assertThat(both.path("totals").path("amount").asText()).isEqualTo("0.006000");

        JsonNode lastNovember = tenantReport(fixture, "2026-11-30", "2026-11-30", "DAY");
        assertThat(lastNovember.path("items")).hasSize(1);
        assertThat(lastNovember.path("items").get(0).path("key").asText()).isEqualTo("2026-11-30");

        JsonNode firstDecember = tenantReport(fixture, "2026-12-01", "2026-12-01", "DAY");
        assertThat(firstDecember.path("items")).hasSize(1);
        assertThat(firstDecember.path("items").get(0).path("key").asText()).isEqualTo("2026-12-01");

        assertCostBadRequest(fixture, "2026-11-30", "2026-11-29", "DAY", "from must be on or before to");
        assertCostBadRequest(fixture, "2026-01-01", "2026-06-30", "DAY", "cost range must not exceed 180 days");
        assertCostBadRequest(fixture, "2026-13-01", "2026-12-01", "DAY", "from must be an ISO date");
        assertCostBadRequest(fixture, "2026-11-01", "2026-11-30", "TENANT", "groupBy must be USER, MODEL or DAY");
        ApiTestClient.ApiResponse pageTooDeep = client.get(
                "/api/v1/costs?from=2026-11-01&to=2026-11-30&groupBy=DAY&page=201",
                fixture.admin().sessionCookie(),
                Map.of());
        assertThat(pageTooDeep.status()).as(pageTooDeep.body()).isEqualTo(400);
        assertThat(client.json(pageTooDeep).path("code").asText()).isEqualTo("RICCON-COST-400");
        ApiTestClient.ApiResponse sizeTooBig = client.get(
                "/api/v1/costs?from=2026-11-01&to=2026-11-30&groupBy=DAY&size=101",
                fixture.admin().sessionCookie(),
                Map.of());
        assertThat(sizeTooBig.status()).as(sizeTooBig.body()).isEqualTo(400);
        assertThat(client.json(sizeTooBig).path("code").asText()).isEqualTo("RICCON-COST-400");
    }

    /**
     * IT-COST-104：租户 USER 维度含用户字段，未返回其他租户用户；权限与指标已注册。
     */
    @Test
    @DisplayName("IT-COST-104：租户用户聚合与成本查询指标")
    void report_userGroupingAndMetric() {
        Fixture fixture = fixture();
        UUID modelId = createPricedModel("3.000000", "6.000000");
        UUID adminId = UUID.fromString(userId(fixture.admin()));
        pricedInvocation(
                fixture.tenant(),
                adminId,
                modelId,
                Instant.parse("2026-11-22T02:00:00Z"),
                LocalDate.of(2026, 11, 22),
                1000,
                0,
                "3.000000",
                "6.000000",
                "0.003000");

        JsonNode report = tenantReport(fixture, "2026-11-22", "2026-11-22", "USER");
        assertThat(report.path("items")).hasSize(1);
        JsonNode bucket = report.path("items").get(0);
        assertThat(bucket.path("key").asText()).isEqualTo(adminId.toString());
        assertThat(bucket.path("userId").asText()).isEqualTo(adminId.toString());
        assertThat(bucket.path("userDisplayName").asText()).isNotBlank();

        io.micrometer.core.instrument.Counter reportCounter = meterRegistry
                .find(CostMetrics.REPORT_QUERIES)
                .tag("group_by", "USER")
                .counter();
        assertThat(reportCounter).isNotNull();
        assertThat(reportCounter.count()).isGreaterThanOrEqualTo(1.0d);
        assertThat(prometheusMeterRegistry.scrape())
                .contains("riccon_cost_report_queries_total{group_by=\"USER\"")
                .contains("riccon_cost_report_queries_total{group_by=\"DAY\"");

        ApiTestClient.ApiResponse member =
                client.get("/api/v1/costs?groupBy=DAY", fixture.member().sessionCookie(), Map.of());
        assertThat(member.status()).as(member.body()).isEqualTo(403);
        assertThat(client.json(member).path("code").asText()).isEqualTo("RICCON-AUTH-403");

        ApiTestClient.ApiResponse tenantOnPlatform =
                client.get("/api/v1/admin/costs?groupBy=DAY", fixture.admin().sessionCookie(), Map.of());
        assertThat(tenantOnPlatform.status()).as(tenantOnPlatform.body()).isEqualTo(403);
    }

    /**
     * 查询租户报告并断言 200。
     *
     * @param fixture 租户夹具
     * @param from 起始日
     * @param to 结束日
     * @param groupBy 维度
     * @return 报告
     */
    private JsonNode tenantReport(Fixture fixture, String from, String to, String groupBy) {
        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/costs?from=" + from + "&to=" + to + "&groupBy=" + groupBy.replace(" ", ""),
                fixture.admin().sessionCookie(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 查询租户明细并断言 200。
     *
     * @param fixture 租户夹具
     * @param from 起始日
     * @param to 结束日
     * @param userId 用户过滤，可为空
     * @param modelId 模型过滤，可为空
     * @return 明细分页
     */
    private JsonNode tenantInvocations(Fixture fixture, String from, String to, String userId, String modelId) {
        StringBuilder path =
                new StringBuilder("/api/v1/costs/invocations?from=" + from + "&to=" + to + "&page=1&size=100");
        if (userId != null) {
            path.append("&userId=").append(userId);
        }
        if (modelId != null) {
            path.append("&modelId=").append(modelId);
        }
        ApiTestClient.ApiResponse response =
                client.get(path.toString(), fixture.admin().sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 按 pricingStatus 查找明细项。
     *
     * @param page 明细分页
     * @param status PRICED/UNPRICED/USAGE_UNKNOWN
     * @return 匹配项
     */
    private JsonNode itemByStatus(JsonNode page, String status) {
        for (JsonNode item : page.path("items")) {
            if (status.equals(item.path("pricingStatus").asText())) {
                return item;
            }
        }
        throw new AssertionError("no invocation with pricingStatus " + status + ": " + page);
    }

    /**
     * 断言范围错误统一为 400 + RICCON-COST-400。
     *
     * @param fixture 租户夹具
     * @param from 起始日
     * @param to 结束日
     * @param groupBy 维度
     * @param detail 期望包含的说明；为空时不校验
     */
    private void assertCostBadRequest(Fixture fixture, String from, String to, String groupBy, String detail) {
        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/costs?from=" + from + "&to=" + to + "&groupBy=" + groupBy.replace(" ", ""),
                fixture.admin().sessionCookie(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(400);
        JsonNode problem = client.json(response);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-COST-400");
        if (detail != null) {
            assertThat(problem.path("detail").asText()).contains(detail);
        }
    }
}
