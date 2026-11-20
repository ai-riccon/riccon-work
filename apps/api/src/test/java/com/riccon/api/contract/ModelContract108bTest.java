package com.riccon.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M04 配额/成本契约（IT-MODEL-108b）。
 *
 * <p>与 108a 同一份 {@code 1.3.0-m04} 全量契约：断言配额/成本端点、权限、CSRF、schema 必填字段与可见性，
 * 以及配额/成本端点没有 {@code x-riccon-deferred} 分段。B6/B7 实现不在本任务范围，因此这里只做契约校验，
 * 不调用业务端点。
 */
class ModelContract108bTest {

    /** 契约文档。 */
    private final Map<String, Object> document = OpenApiContract.load();

    /** 一次性校验 108b 全部契约断言。 */
    @Test
    @DisplayName("IT-MODEL-108b：配额/成本端点 schema 与同一版本全量契约")
    void itModel108b() {
        assertSameFullContractVersion();
        assertQuotaEndpoints();
        assertQuotaSchemas();
        assertCostEndpoints();
        assertCostSchemas();
    }

    /** 配额/成本与模型端点同属 1.3.0-m04 全量契约，没有 deferred 分段。 */
    private void assertSameFullContractVersion() {
        Map<String, Object> info = OpenApiContract.map(document.get("info"));
        assertThat(info.get("version")).isEqualTo("1.3.0-m04");
        for (String path : List.of(
                "/api/v1/quotas",
                "/api/v1/quotas/users",
                "/api/v1/admin/quotas",
                "/api/v1/admin/tenants/{tenantId}/quotas",
                "/api/v1/admin/tenants/{tenantId}/users/{userId}/quotas",
                "/api/v1/costs",
                "/api/v1/costs/invocations",
                "/api/v1/admin/costs")) {
            Map<String, Object> item = OpenApiContract.pathItem(document, path);
            for (String method : List.of("get", "post", "put", "patch")) {
                Object operation = item.get(method);
                if (operation != null) {
                    assertThat(OpenApiContract.deferred(OpenApiContract.map(operation)))
                            .as("quota/cost operation must not be deferred: " + method + " " + path)
                            .isNull();
                }
            }
        }
    }

    /** 配额端点权限与响应 schema。 */
    private void assertQuotaEndpoints() {
        Map<String, Object> tenantQuota = OpenApiContract.operation(document, "/api/v1/quotas", "get");
        assertThat(OpenApiContract.permission(tenantQuota)).isEqualTo("quota.read");
        assertThat(OpenApiContract.responseSchemaName(tenantQuota)).isEqualTo("TenantQuota");

        Map<String, Object> userQuotas = OpenApiContract.operation(document, "/api/v1/quotas/users", "get");
        assertThat(OpenApiContract.permission(userQuotas)).isEqualTo("quota.read");
        assertThat(OpenApiContract.responseSchemaName(userQuotas)).isEqualTo("UserQuotaPage");

        Map<String, Object> platformQuotas = OpenApiContract.operation(document, "/api/v1/admin/quotas", "get");
        assertThat(OpenApiContract.permission(platformQuotas)).isEqualTo("platform.quota.manage");
        assertThat(OpenApiContract.responseSchemaName(platformQuotas)).isEqualTo("PlatformQuotaPage");

        Map<String, Object> tenantUpdate =
                OpenApiContract.operation(document, "/api/v1/admin/tenants/{tenantId}/quotas", "put");
        assertThat(OpenApiContract.permission(tenantUpdate)).isEqualTo("platform.quota.manage");
        assertThat(OpenApiContract.referencesParameter(tenantUpdate, "CsrfToken"))
                .isTrue();
        assertThat(OpenApiContract.responseSchemaName(tenantUpdate)).isEqualTo("TenantQuota");

        Map<String, Object> userUpdate =
                OpenApiContract.operation(document, "/api/v1/admin/tenants/{tenantId}/users/{userId}/quotas", "put");
        assertThat(OpenApiContract.permission(userUpdate)).isEqualTo("platform.quota.manage");
        assertThat(OpenApiContract.referencesParameter(userUpdate, "CsrfToken")).isTrue();
        assertThat(OpenApiContract.responseSchemaName(userUpdate)).isEqualTo("UserQuota");
    }

    /** 配额 schema：窗口、来源、整体替换与 429 判别。 */
    private void assertQuotaSchemas() {
        Map<String, Object> window = OpenApiContract.schema(document, "QuotaWindowState");
        assertThat(OpenApiContract.additionalPropertiesFalse(window)).isTrue();
        assertThat(OpenApiContract.required(window))
                .containsExactlyInAnyOrder("window", "limit", "used", "resetsAt", "source");
        Map<String, Object> windowProperties = OpenApiContract.map(window.get("properties"));
        assertThat(OpenApiContract.map(windowProperties.get("window")).get("$ref"))
                .isEqualTo("#/components/schemas/QuotaWindowKind");
        assertThat(OpenApiContract.stringList(
                        OpenApiContract.schema(document, "QuotaWindowKind").get("enum")))
                .containsExactlyInAnyOrder("DAILY", "MONTHLY");
        assertThat(OpenApiContract.map(windowProperties.get("source")).get("$ref"))
                .isEqualTo("#/components/schemas/QuotaLimitSource");
        assertThat(OpenApiContract.stringList(
                        OpenApiContract.schema(document, "QuotaLimitSource").get("enum")))
                .containsExactlyInAnyOrder("EXPLICIT", "EXPLICIT_UNLIMITED", "DEPLOYMENT_DEFAULT");

        Map<String, Object> update = OpenApiContract.schema(document, "QuotaUpdateRequest");
        assertThat(OpenApiContract.required(update)).containsExactly("limits");
        Map<String, Object> limits = OpenApiContract.map(
                OpenApiContract.map(update.get("properties")).get("limits"));
        assertThat(OpenApiContract.required(limits)).containsExactlyInAnyOrder("daily", "monthly");

        Map<String, Object> platformQuota = OpenApiContract.schema(document, "PlatformQuotaRow");
        assertThat(OpenApiContract.propertyNames(platformQuota))
                .doesNotContain("userId", "username", "displayName", "items");

        Map<String, Object> exceeded = OpenApiContract.schema(document, "QuotaExceededProblem");
        Map<String, Object> exceededProperties = OpenApiContract.map(exceeded.get("properties"));
        assertThat(OpenApiContract.map(exceededProperties.get("code")).get("const"))
                .isEqualTo("RICCON-QUOTA-429");
        assertThat(OpenApiContract.required(exceeded)).contains("scopeType", "window", "limit", "used", "resetsAt");

        Map<String, Object> rateLimited = OpenApiContract.schema(document, "RateLimitedProblem");
        Map<String, Object> rateProperties = OpenApiContract.map(rateLimited.get("properties"));
        assertThat(OpenApiContract.stringList(
                        OpenApiContract.map(rateProperties.get("code")).get("enum")))
                .containsExactlyInAnyOrder("RICCON-RATE-429", "RICCON-QUEUE-429");
    }

    /** 成本端点权限与响应 schema。 */
    private void assertCostEndpoints() {
        Map<String, Object> tenantCosts = OpenApiContract.operation(document, "/api/v1/costs", "get");
        assertThat(OpenApiContract.permission(tenantCosts)).isEqualTo("cost.read");
        assertThat(OpenApiContract.responseSchemaName(tenantCosts)).isEqualTo("TenantCostReport");

        Map<String, Object> invocations = OpenApiContract.operation(document, "/api/v1/costs/invocations", "get");
        assertThat(OpenApiContract.permission(invocations)).isEqualTo("cost.read");
        assertThat(OpenApiContract.responseSchemaName(invocations)).isEqualTo("CostInvocationPage");

        Map<String, Object> platformCosts = OpenApiContract.operation(document, "/api/v1/admin/costs", "get");
        assertThat(OpenApiContract.permission(platformCosts)).isEqualTo("platform.cost.read");
        assertThat(OpenApiContract.responseSchemaName(platformCosts)).isEqualTo("PlatformCostReport");
    }

    /** 成本 schema：6 位小数、三类状态、平台无用户字段、unbucketed 显式。 */
    private void assertCostSchemas() {
        Map<String, Object> totals = OpenApiContract.schema(document, "CostTotals");
        assertThat(OpenApiContract.required(totals))
                .containsExactlyInAnyOrder(
                        "currency",
                        "amount",
                        "pricedCalls",
                        "unpricedCalls",
                        "usageUnknownCalls",
                        "currencyMismatchCalls",
                        "unbucketedCalls");
        Map<String, Object> amount = OpenApiContract.map(
                OpenApiContract.map(totals.get("properties")).get("amount"));
        assertThat(amount.get("pattern")).isEqualTo("^\\d{1,14}(\\.\\d{1,6})?$");
        assertThat(amount.get("type").toString()).contains("string");

        Map<String, Object> tenantBucket = OpenApiContract.schema(document, "TenantCostBucket");
        assertThat(OpenApiContract.propertyNames(tenantBucket)).contains("userId", "userDisplayName");
        Map<String, Object> platformBucket = OpenApiContract.schema(document, "PlatformCostBucket");
        assertThat(OpenApiContract.propertyNames(platformBucket)).doesNotContain("userId", "userDisplayName");
        assertThat(OpenApiContract.additionalPropertiesFalse(platformBucket)).isTrue();

        Map<String, Object> invocation = OpenApiContract.schema(document, "CostInvocation");
        Map<String, Object> priceCurrency = OpenApiContract.map(
                OpenApiContract.map(invocation.get("properties")).get("priceCurrency"));
        assertThat(priceCurrency.get("type").toString()).contains("null");
        Map<String, Object> pricingStatus = OpenApiContract.map(
                OpenApiContract.map(invocation.get("properties")).get("pricingStatus"));
        assertThat(pricingStatus.get("$ref")).isEqualTo("#/components/schemas/CostPricingStatus");
        Map<String, Object> pricingStatusSchema = OpenApiContract.schema(document, "CostPricingStatus");
        assertThat(OpenApiContract.stringList(pricingStatusSchema.get("enum")))
                .containsExactlyInAnyOrder("PRICED", "UNPRICED", "USAGE_UNKNOWN");

        Map<String, Object> platformReport =
                OpenApiContract.mergedSchema(document, OpenApiContract.schema(document, "PlatformCostReport"));
        assertThat(OpenApiContract.propertyNames(platformReport)).contains("totals", "items");
        Map<String, Object> platformItems = OpenApiContract.map(
                OpenApiContract.map(platformReport.get("properties")).get("items"));
        assertThat(String.valueOf(platformItems.get("items")))
                .contains("PlatformCostBucket")
                .doesNotContain("TenantCostBucket");
    }
}
