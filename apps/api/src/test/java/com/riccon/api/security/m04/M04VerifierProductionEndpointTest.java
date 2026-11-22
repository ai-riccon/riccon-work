package com.riccon.api.security.m04;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * M04-V1 独立验收：生产模式下的演示端点边界（AC-15 的“生产拒绝仅演示端点”段）。
 *
 * <p>部署配置同时给出 PRODUCTION 与 DEMO_ONLY 两个端点；生产模式必须在保存路由时拒绝演示端点，
 * 员工目录也不得出现演示模型。与 `SEC-M04-002`（Builder 的目录标签测试）互补：本类验证写路径，
 * 不依赖 Builder 的断言；端点均为私网字面量，不发真实出网。
 */
@TestPropertySource(
        properties = {
            "riccon.model.deployment-mode=PRODUCTION",
            "riccon.model.endpoints=[{\"code\":\"m04-vp-prod\",\"baseUrl\":\"https://10.20.41.10/v1\",\"environmentLabel\":\"PRODUCTION\"},{\"code\":\"m04-vp-demo\",\"baseUrl\":\"https://10.20.41.20/v1\",\"environmentLabel\":\"DEMO_ONLY\"}]",
            "riccon.model.allow-legacy-endpoints=false",
            "riccon.model.allow-http=false"
        })
@Tag("security")
class M04VerifierProductionEndpointTest extends ApiIntegrationTestSupport {

    /** 生产端点代码。 */
    private static final String PROD_CODE = "m04-vp-prod";

    /** 生产端点地址。 */
    private static final String PROD_URL = "https://10.20.41.10/v1";

    /** 演示端点代码。 */
    private static final String DEMO_CODE = "m04-vp-demo";

    /** 演示端点地址。 */
    private static final String DEMO_URL = "https://10.20.41.20/v1";

    /** 生产端点 provider。 */
    private UUID prodProviderId;

    /** 演示端点 provider。 */
    private UUID demoProviderId;

    /** 生产模型。 */
    private UUID prodModelId;

    /** 演示模型。 */
    private UUID demoModelId;

    /** 生产模型 key。 */
    private String prodModelKey;

    /** 演示模型 key。 */
    private String demoModelKey;

    /** 每个用例重建目录。 */
    @BeforeEach
    void seedCatalog() {
        cleanup();
        prodProviderId = UuidV7.generate();
        demoProviderId = UuidV7.generate();
        prodModelKey = "m04-vp-prod-model";
        demoModelKey = "m04-vp-demo-model";
        insertProvider(prodProviderId, PROD_CODE, PROD_URL);
        insertProvider(demoProviderId, DEMO_CODE, DEMO_URL);
        prodModelId = insertModel(prodProviderId, prodModelKey);
        demoModelId = insertModel(demoProviderId, demoModelKey);
    }

    /** 用例后清理。 */
    @AfterEach
    void cleanupAfterTest() {
        cleanup();
    }

    /**
     * AC-15：生产模式拒绝演示端点参与路由，员工目录也不返回演示模型。
     */
    @Test
    @DisplayName("AC-15 生产模式拒绝 DEMO_ONLY 端点路由且员工目录排除")
    void productionModeRejectsDemoEndpointRouting() {
        LoginSession platform = loginPlatformAdmin();
        String tenantCode = uniqueTenantCode("m04vprod");
        createTenant(platform, tenantCode);
        LoginSession member = createAndLoginMember(platform, tenantCode);

        // 平台目录显示演示端点 allowed=false、标签来自部署配置。
        JsonNode providers = client.json(
                client.get("/api/v1/admin/model-providers?page=1&size=100", platform.sessionCookie(), Map.of()));
        JsonNode demo = findByProperty(providers.path("items"), "code", DEMO_CODE);
        assertThat(demo).isNotNull();
        assertThat(demo.path("environmentLabel").asText()).isEqualTo("DEMO_ONLY");
        assertThat(demo.path("allowed").asBoolean()).isFalse();

        // 员工目录只含生产模型。
        JsonNode employee = client.json(client.get("/api/v1/models?page=1&size=100", member.sessionCookie(), Map.of()));
        assertThat(findByProperty(employee.path("items"), "modelKey", prodModelKey))
                .isNotNull();
        assertThat(findByProperty(employee.path("items"), "modelKey", demoModelKey))
                .isNull();

        // 保存只含演示模型的路由链：400 ENDPOINT_NOT_ALLOWED，链保持原样，响应不回显地址。
        int version = chain(getRouting(platform), "CHAT").path("version").asInt();
        ApiTestClient.ApiResponse rejected = client.put(
                "/api/v1/admin/model-routing/CHAT",
                client.jsonBody("version", version, "modelIds", List.of(demoModelId.toString())),
                platform.sessionCookie(),
                platform.csrfToken());
        assertThat(rejected.status()).as(rejected.body()).isEqualTo(400);
        JsonNode problem = client.json(rejected);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-MODEL-400");
        assertThat(problem.path("field").asText()).isEqualTo("modelIds");
        assertThat(problem.path("reason").asText()).isEqualTo("ENDPOINT_NOT_ALLOWED");
        assertThat(rejected.body()).doesNotContain(DEMO_URL);
        assertThat(chain(getRouting(platform), "CHAT").path("version").asInt()).isEqualTo(version);

        // 生产端点可以正常保存；演示模型无法混入。
        ApiTestClient.ApiResponse accepted = client.put(
                "/api/v1/admin/model-routing/CHAT",
                client.jsonBody("version", version, "modelIds", List.of(prodModelId.toString())),
                platform.sessionCookie(),
                platform.csrfToken());
        assertThat(accepted.status()).as(accepted.body()).isEqualTo(200);
        assertThat(chain(getRouting(platform), "CHAT")
                        .path("entries")
                        .get(0)
                        .path("modelId")
                        .asText())
                .isEqualTo(prodModelId.toString());
    }

    /** 读取路由链。 */
    private JsonNode getRouting(LoginSession platform) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/admin/model-routing", platform.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /** 按 purpose 取链。 */
    private static JsonNode chain(JsonNode routing, String purpose) {
        for (JsonNode item : routing.path("items")) {
            if (purpose.equals(item.path("purpose").asText())) {
                return item;
            }
        }
        throw new AssertionError("missing routing chain: " + purpose);
    }

    /** 按属性查条目。 */
    private static JsonNode findByProperty(JsonNode items, String property, String value) {
        for (JsonNode item : items) {
            if (value.equals(item.path(property).asText())) {
                return item;
            }
        }
        return null;
    }

    /** 插入 provider。 */
    private void insertProvider(UUID id, String code, String baseUrl) {
        jdbcClient
                .sql("""
                        INSERT INTO model_provider (id, code, name, protocol, base_url, status)
                        VALUES (:id, :code, :name, 'OPENAI_COMPATIBLE', :baseUrl, 'ACTIVE')
                        """)
                .param("id", BinaryUuids.toBytes(id))
                .param("code", code)
                .param("name", "M04 V " + code)
                .param("baseUrl", baseUrl)
                .update();
    }

    /** 插入模型并返回 ID。 */
    private UUID insertModel(UUID providerId, String modelKey) {
        UUID modelId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO model (
                            id, provider_id, model_key, display_name, context_window, max_output_tokens,
                            capabilities, input_price_per_1m, output_price_per_1m, status
                        ) VALUES (
                            :id, :providerId, :modelKey, :displayName, 32768, 2048,
                            JSON_OBJECT('chat', TRUE, 'stream', TRUE), 1.500000, 3.000000, 'ACTIVE'
                        )
                        """)
                .param("id", BinaryUuids.toBytes(modelId))
                .param("providerId", BinaryUuids.toBytes(providerId))
                .param("modelKey", modelKey)
                .param("displayName", "M04 V " + modelKey)
                .update();
        return modelId;
    }

    /** 清理路由条目、模型、provider 与遗留 DEK。 */
    private void cleanup() {
        for (UUID modelId : java.util.stream.Stream.of(prodModelId, demoModelId)
                .filter(java.util.Objects::nonNull)
                .toList()) {
            jdbcClient
                    .sql("DELETE FROM model_routing_entry WHERE model_id = :modelId")
                    .param("modelId", BinaryUuids.toBytes(modelId))
                    .update();
        }
        for (UUID providerId : java.util.stream.Stream.of(prodProviderId, demoProviderId)
                .filter(java.util.Objects::nonNull)
                .toList()) {
            byte[] bytes = BinaryUuids.toBytes(providerId);
            jdbcClient
                    .sql("DELETE FROM provider_credential WHERE provider_id = :providerId")
                    .param("providerId", bytes)
                    .update();
            jdbcClient
                    .sql("DELETE FROM model WHERE provider_id = :providerId")
                    .param("providerId", bytes)
                    .update();
            jdbcClient
                    .sql("DELETE FROM model_provider WHERE id = :providerId")
                    .param("providerId", bytes)
                    .update();
        }
    }
}
