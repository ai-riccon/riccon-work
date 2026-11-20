package com.riccon.api.model.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * 生产模式端点标签策略（SEC-M04-002）。
 *
 * <p>生产模式允许列表同时包含一个 PRODUCTION 端点与一个 DEMO_ONLY 端点：员工目录只返回生产端点；
 * 平台目录显示演示端点 {@code allowed=false}，证明“生产模式拒绝演示端点”且不需要端点写 API。
 * 端点均使用私网字面量地址，不依赖 DNS 或出网。
 */
@TestPropertySource(
        properties = {
            "riccon.model.deployment-mode=PRODUCTION",
            "riccon.model.endpoints=[{\"code\":\"m04-prod\",\"baseUrl\":\"https://10.20.31.40/v1\",\"environmentLabel\":\"PRODUCTION\"},{\"code\":\"m04-demo\",\"baseUrl\":\"https://10.20.31.50/v1\",\"environmentLabel\":\"DEMO_ONLY\"}]",
            "riccon.model.allow-legacy-endpoints=false",
            "riccon.model.allow-http=false"
        })
class ProductionEndpointPolicyApiTest extends ApiIntegrationTestSupport {

    /** 生产端点代码。 */
    private static final String PRODUCTION_CODE = "m04-prod";

    /** 演示端点代码。 */
    private static final String DEMO_CODE = "m04-demo";

    /** 生产端点模型 key。 */
    private String productionModelKey;

    /** 演示端点模型 key。 */
    private String demoModelKey;

    /** 每个测试重建两个端点与模型。 */
    @BeforeEach
    void seedCatalog() {
        cleanup();
        productionModelKey = seedProviderModel(PRODUCTION_CODE, "https://10.20.31.40/v1", "M04 Production Model");
        demoModelKey = seedProviderModel(DEMO_CODE, "https://10.20.31.50/v1", "M04 Demo Model");
    }

    /** 员工目录只包含生产标签端点。 */
    @Test
    @DisplayName("生产模式员工目录排除演示端点")
    void employeeCatalogExcludesDemoEndpointInProduction() {
        LoginSession member = createMember();

        ApiTestClient.ApiResponse response = client.get("/api/v1/models", member.sessionCookie(), Map.of());

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode items = client.json(response).path("items");
        assertThat(findItem(items, productionModelKey)).isNotNull();
        assertThat(findItem(items, demoModelKey)).isNull();
    }

    /** 平台目录显示演示端点标签与 allowed=false。 */
    @Test
    @DisplayName("生产模式平台目录标记演示端点 allowed=false")
    void adminCatalogMarksDemoEndpointNotAllowed() {
        LoginSession admin = loginPlatformAdmin();

        ApiTestClient.ApiResponse response =
                client.get("/api/v1/admin/model-providers", admin.sessionCookie(), Map.of());

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode items = client.json(response).path("items");
        JsonNode production = findByCode(items, PRODUCTION_CODE);
        JsonNode demo = findByCode(items, DEMO_CODE);
        assertThat(production).isNotNull();
        assertThat(production.path("environmentLabel").asText()).isEqualTo("PRODUCTION");
        assertThat(production.path("allowed").asBoolean()).isTrue();
        assertThat(demo).isNotNull();
        assertThat(demo.path("environmentLabel").asText()).isEqualTo("DEMO_ONLY");
        assertThat(demo.path("allowed").asBoolean()).isFalse();
    }

    /** 创建并登录员工。 */
    private LoginSession createMember() {
        LoginSession admin = loginPlatformAdmin();
        String tenantCode = uniqueTenantCode("m04prod");
        createTenant(admin, tenantCode);
        return createAndLoginMember(admin, tenantCode);
    }

    /** 插入一个 provider 及其模型，返回 modelKey。 */
    private String seedProviderModel(String code, String baseUrl, String displayName) {
        UUID providerId = UuidV7.generate();
        UUID modelId = UuidV7.generate();
        String modelKey = code + "-model";
        jdbcClient
                .sql("""
                        INSERT INTO model_provider (id, code, name, protocol, base_url, status)
                        VALUES (:id, :code, :name, 'OPENAI_COMPATIBLE', :baseUrl, 'ACTIVE')
                        """)
                .param("id", BinaryUuids.toBytes(providerId))
                .param("code", code)
                .param("name", displayName + " Provider")
                .param("baseUrl", baseUrl)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO model (
                            id, provider_id, model_key, display_name, context_window, max_output_tokens, status
                        ) VALUES (:id, :providerId, :modelKey, :displayName, 8192, 1024, 'ACTIVE')
                        """)
                .param("id", BinaryUuids.toBytes(modelId))
                .param("providerId", BinaryUuids.toBytes(providerId))
                .param("modelKey", modelKey)
                .param("displayName", displayName)
                .update();
        return modelKey;
    }

    /** 清理本测试端点。 */
    private void cleanup() {
        for (String code : new String[] {PRODUCTION_CODE, DEMO_CODE}) {
            jdbcClient
                    .sql("SELECT id FROM model_provider WHERE code = :code")
                    .param("code", code)
                    .query((rs, rowNum) -> BinaryUuids.fromBytes(rs.getBytes("id")))
                    .optional()
                    .ifPresent(id -> {
                        byte[] providerBytes = BinaryUuids.toBytes(id);
                        jdbcClient
                                .sql("DELETE FROM provider_credential WHERE provider_id = :providerId")
                                .param("providerId", providerBytes)
                                .update();
                        jdbcClient
                                .sql("DELETE FROM model WHERE provider_id = :providerId")
                                .param("providerId", providerBytes)
                                .update();
                        jdbcClient
                                .sql("DELETE FROM model_provider WHERE id = :providerId")
                                .param("providerId", providerBytes)
                                .update();
                    });
        }
    }

    /** 按 modelKey 查找模型项。 */
    private static JsonNode findItem(JsonNode items, String key) {
        for (JsonNode item : items) {
            if (key.equals(item.path("modelKey").asText())) {
                return item;
            }
        }
        return null;
    }

    /** 按 code 查找供应商。 */
    private static JsonNode findByCode(JsonNode items, String code) {
        for (JsonNode item : items) {
            if (code.equals(item.path("code").asText())) {
                return item;
            }
        }
        return null;
    }
}
