package com.riccon.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M04 模型/路由/凭据/开关契约（IT-MODEL-108a）。
 *
 * <p>读取 {@code contracts/openapi.yaml} 断言：当前契约版本为 {@code 1.4.0-m05}（M04 端点保持全量、无回归）；
 * 三个模型端点存在且权限正确；员工 {@code EmployeeModel} 不含价格/provider/baseUrl；平台 {@code AdminModel} 含价格与 pricingStatus；
 * provider PUT 只允许 name；路由/凭据/开关端点与权限齐全；MCP 目录仍标 {@code x-riccon-deferred: M07}；
 * M02/M03/M10 路径零回归。
 */
class ModelContract108aTest {

    /** 契约文档。 */
    private final Map<String, Object> document = OpenApiContract.load();

    /** 一次性校验 108a 全部契约断言；单个 ID 只对应一个契约用例（ADR-0021 §11.1）。 */
    @Test
    @DisplayName("IT-MODEL-108a：三个模型端点与路由/凭据/开关契约")
    void itModel108a() {
        assertVersionAndRegressionSurface();
        assertEmployeeCatalogContract();
        assertAdminProviderContract();
        assertAdminModelContract();
        assertRoutingCredentialSwitchContract();
        assertMcpDeferredAndProblemDetail();
    }

    /** 版本保持 M04 全量设计，并在 M05 契约内继续存在；M02/M03/M10 代表路径仍在。 */
    private void assertVersionAndRegressionSurface() {
        Map<String, Object> info = OpenApiContract.map(document.get("info"));
        assertThat(info.get("version")).isEqualTo("1.4.0-m05");
        for (String path : List.of(
                "/api/v1/auth/csrf",
                "/api/v1/conversations/{conversationId}/messages",
                "/api/v1/departments",
                "/api/v1/knowledge/documents",
                "/api/v1/audit-events",
                "/api/v1/admin/tenants/{tenantId}/audit-exports",
                "/api/v1/admin/tenants/{tenantId}/departments")) {
            assertThat(OpenApiContract.pathItem(document, path))
                    .as("regression surface: " + path)
                    .isNotEmpty();
        }
    }

    /** 员工目录使用独立 EmployeeModel，不含价格/provider 字段。 */
    private void assertEmployeeCatalogContract() {
        Map<String, Object> operation = OpenApiContract.operation(document, "/api/v1/models", "get");
        assertThat(OpenApiContract.permission(operation)).isEqualTo("model.read");
        assertThat(OpenApiContract.responseSchemaName(operation)).isEqualTo("ModelPage");

        Map<String, Object> employee = OpenApiContract.schema(document, "EmployeeModel");
        assertThat(OpenApiContract.additionalPropertiesFalse(employee)).isTrue();
        assertThat(OpenApiContract.required(employee))
                .containsExactlyInAnyOrder(
                        "id", "modelKey", "displayName", "contextWindow", "maxOutputTokens", "status");
        assertThat(OpenApiContract.propertyNames(employee))
                .doesNotContain(
                        "inputPricePer1M",
                        "outputPricePer1M",
                        "priceCurrency",
                        "pricingStatus",
                        "providerId",
                        "providerCode",
                        "baseUrl",
                        "environmentLabel");
    }

    /** 平台 provider 目录含标签/允许状态/key 数量；PUT 只允许 name。 */
    private void assertAdminProviderContract() {
        Map<String, Object> list = OpenApiContract.operation(document, "/api/v1/admin/model-providers", "get");
        assertThat(OpenApiContract.permission(list)).isEqualTo("platform.model.read");
        assertThat(OpenApiContract.responseSchemaName(list)).isEqualTo("ModelProviderPage");

        Map<String, Object> update =
                OpenApiContract.operation(document, "/api/v1/admin/model-providers/{providerId}", "put");
        assertThat(OpenApiContract.permission(update)).isEqualTo("platform.model.manage");
        assertThat(OpenApiContract.referencesParameter(update, "CsrfToken")).isTrue();
        Map<String, Object> requestBody = OpenApiContract.map(update.get("requestBody"));
        Map<String, Object> content = OpenApiContract.map(requestBody.get("content"));
        Map<String, Object> json = OpenApiContract.map(content.get("application/json"));
        Map<String, Object> schema = OpenApiContract.map(json.get("schema"));
        assertThat(schema.get("$ref")).isEqualTo("#/components/schemas/ModelProviderUpdateRequest");

        Map<String, Object> provider = OpenApiContract.schema(document, "ModelProvider");
        assertThat(OpenApiContract.additionalPropertiesFalse(provider)).isTrue();
        assertThat(OpenApiContract.required(provider)).contains("environmentLabel", "allowed", "status");
        assertThat(OpenApiContract.propertyNames(provider))
                .contains("baseUrl", "environmentLabel", "allowed", "activeCredentialCount", "modelCount");

        Map<String, Object> updateRequest = OpenApiContract.schema(document, "ModelProviderUpdateRequest");
        assertThat(OpenApiContract.propertyNames(updateRequest)).contains("name");
        Map<String, Object> properties = OpenApiContract.map(updateRequest.get("properties"));
        for (String field : List.of("baseUrl", "environmentLabel", "protocol")) {
            Map<String, Object> property = OpenApiContract.map(properties.get(field));
            assertThat(property.get("readOnly")).as(field + " must be readOnly").isEqualTo(Boolean.TRUE);
        }
    }

    /** 平台模型目录含价格与 pricingStatus；PATCH 只改 status。 */
    private void assertAdminModelContract() {
        Map<String, Object> list = OpenApiContract.operation(document, "/api/v1/admin/models", "get");
        assertThat(OpenApiContract.permission(list)).isEqualTo("platform.model.read");
        assertThat(OpenApiContract.responseSchemaName(list)).isEqualTo("AdminModelPage");

        Map<String, Object> patch = OpenApiContract.operation(document, "/api/v1/admin/models/{modelId}", "patch");
        assertThat(OpenApiContract.permission(patch)).isEqualTo("platform.model.manage");
        assertThat(OpenApiContract.referencesParameter(patch, "CsrfToken")).isTrue();
        assertThat(OpenApiContract.responseSchemaName(patch)).isEqualTo("AdminModel");

        Map<String, Object> admin = OpenApiContract.schema(document, "AdminModel");
        assertThat(OpenApiContract.additionalPropertiesFalse(admin)).isTrue();
        assertThat(OpenApiContract.required(admin))
                .contains("providerId", "providerCode", "environmentLabel", "pricingStatus");
        assertThat(OpenApiContract.propertyNames(admin))
                .contains("inputPricePer1M", "outputPricePer1M", "priceCurrency", "pricingStatus", "environmentLabel");

        Map<String, Object> statusRequest = OpenApiContract.schema(document, "ModelStatusUpdateRequest");
        assertThat(OpenApiContract.required(statusRequest)).containsExactly("status");
        Map<String, Object> status = OpenApiContract.map(
                OpenApiContract.map(statusRequest.get("properties")).get("status"));
        assertThat(OpenApiContract.stringList(status.get("enum"))).containsExactlyInAnyOrder("ACTIVE", "DISABLED");
    }

    /** 路由、凭据与开关端点/权限齐全，写端点都带 CSRF。 */
    private void assertRoutingCredentialSwitchContract() {
        Map<String, Object> routingList = OpenApiContract.operation(document, "/api/v1/admin/model-routing", "get");
        assertThat(OpenApiContract.permission(routingList)).isEqualTo("platform.model.read");
        Map<String, Object> routingUpdate =
                OpenApiContract.operation(document, "/api/v1/admin/model-routing/{purpose}", "put");
        assertThat(OpenApiContract.permission(routingUpdate)).isEqualTo("platform.model.manage");
        assertThat(OpenApiContract.referencesParameter(routingUpdate, "CsrfToken"))
                .isTrue();
        Map<String, Object> routingRequest = OpenApiContract.schema(document, "ModelRoutingUpdateRequest");
        Map<String, Object> modelIds = OpenApiContract.map(
                OpenApiContract.map(routingRequest.get("properties")).get("modelIds"));
        assertThat(modelIds.get("minItems")).isEqualTo(1);
        assertThat(modelIds.get("maxItems")).isEqualTo(20);
        assertThat(modelIds.get("uniqueItems")).isEqualTo(Boolean.TRUE);

        assertCredentialMethod("get", "platform.model.read");
        assertCredentialMethod("post", "platform.model.manage");
        for (String suffix : List.of("rotate", "disable", "enable")) {
            Map<String, Object> operation = OpenApiContract.operation(
                    document,
                    "/api/v1/admin/model-providers/{providerId}/credentials/{credentialId}/" + suffix,
                    "post");
            assertThat(OpenApiContract.permission(operation)).isEqualTo("platform.model.manage");
            assertThat(OpenApiContract.referencesParameter(operation, "CsrfToken"))
                    .isTrue();
        }

        Map<String, Object> switchGet =
                OpenApiContract.operation(document, "/api/v1/admin/model-gateway/switches", "get");
        assertThat(OpenApiContract.permission(switchGet)).isEqualTo("platform.model.read");
        Map<String, Object> switchPut =
                OpenApiContract.operation(document, "/api/v1/admin/model-gateway/switches", "put");
        assertThat(OpenApiContract.permission(switchPut)).isEqualTo("platform.model.manage");
        assertThat(OpenApiContract.referencesParameter(switchPut, "CsrfToken")).isTrue();
    }

    /** 凭据列表/新增端点权限与 schema。 */
    private void assertCredentialMethod(String method, String permission) {
        Map<String, Object> operation =
                OpenApiContract.operation(document, "/api/v1/admin/model-providers/{providerId}/credentials", method);
        assertThat(OpenApiContract.permission(operation)).isEqualTo(permission);
        assertThat(OpenApiContract.referencesParameter(operation, "CsrfToken")).isEqualTo("post".equals(method));
        OpenApiContract.schema(document, "ModelCredential");
    }

    /** MCP 目录保留 M07 deferred；ProblemDetail 支持字段级 field/reason。 */
    private void assertMcpDeferredAndProblemDetail() {
        Map<String, Object> mcpServers = OpenApiContract.operation(document, "/api/v1/admin/mcp-servers", "get");
        Map<String, Object> mcpTools = OpenApiContract.operation(document, "/api/v1/admin/mcp-tools", "get");
        assertThat(OpenApiContract.deferred(mcpServers)).isEqualTo("M07");
        assertThat(OpenApiContract.deferred(mcpTools)).isEqualTo("M07");
        for (String path : List.of(
                "/api/v1/models",
                "/api/v1/admin/model-providers",
                "/api/v1/admin/models",
                "/api/v1/admin/model-routing",
                "/api/v1/admin/model-providers/{providerId}/credentials",
                "/api/v1/admin/model-gateway/switches")) {
            Map<String, Object> item = OpenApiContract.pathItem(document, path);
            assertThat(item.get("get") == null
                            || OpenApiContract.deferred(OpenApiContract.map(item.get("get"))) == null)
                    .as("deferred must not cover M04 model path: " + path)
                    .isTrue();
        }

        Object deferred = document.get("x-riccon-deferred");
        assertThat(String.valueOf(deferred)).contains("byo-model-credentials").contains("model-price-editing");
        assertThat(String.valueOf(deferred)).doesNotContain("model-routing-quota");

        Map<String, Object> problem = OpenApiContract.schema(document, "ProblemDetail");
        assertThat(OpenApiContract.additionalPropertiesFalse(problem)).isTrue();
        assertThat(OpenApiContract.propertyNames(problem)).contains("code", "traceId", "field", "reason");
    }
}
