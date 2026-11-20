package com.riccon.api.admin.cost;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.cost.CostApiTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    /**
     * IT-COST-104：平台聚合无用户字段，且可选租户过滤仍只返回聚合。
     */
    @Test
    @DisplayName("IT-COST-104：平台成本聚合不含用户维度")
    void platformReport_hasNoUserFieldsAndFiltersByTenant() {
        Fixture fixture = fixture();
        TenantOther other = createOtherTenant(fixture);
        UUID modelId = createPricedModel("3.000000", "6.000000");
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
        UUID modelId = createPricedModel("3.000000", "6.000000");
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
     * 断言 403。
     *
     * @param response 响应
     */
    private void assertForbidden(ApiTestClient.ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(403);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-AUTH-403");
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
