package com.riccon.api.model.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * 三个模型端点的行为与契约形状（IT-MODEL-108a 的运行时部分）。
 *
 * <p>测试上下文使用 DEMO 模式与唯一受管端点；DB 目录行与部署配置按 code + baseUrl 对齐。
 * 覆盖：员工目录不含 provider/baseUrl/价格，平台目录含标签/允许状态/key 数量/价格，模型启停审计，
 * provider 只改名，只读字段 400，以及“没有端点创建 API”的 405。
 */
@TestPropertySource(
        properties = {
            "riccon.model.deployment-mode=DEMO",
            "riccon.model.endpoints=[{\"code\":\"m04-catalog\",\"baseUrl\":\"https://10.20.30.40/v1\",\"environmentLabel\":\"PRODUCTION\"}]",
            "riccon.model.allow-legacy-endpoints=false",
            "riccon.model.allow-http=false"
        })
class ModelCatalogApiTest extends ApiIntegrationTestSupport {

    /** 测试端点代码。 */
    private static final String PROVIDER_CODE = "m04-catalog";

    /** 测试端点地址。 */
    private static final String PROVIDER_BASE_URL = "https://10.20.30.40/v1";

    /** 供应商 ID。 */
    private UUID providerId;

    /** 模型 ID。 */
    private UUID modelId;

    /** 模型标识。 */
    private String modelKey;

    /** 每个测试重建目录、平台 key 与租户。 */
    @BeforeEach
    void seedCatalog() {
        cleanup();
        providerId = UuidV7.generate();
        modelId = UuidV7.generate();
        modelKey = "m04-model-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String keyId =
                "m04-key-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        jdbcClient
                .sql("""
                        INSERT INTO model_provider (id, code, name, protocol, base_url, status)
                        VALUES (:id, :code, 'M04 Catalog Provider', 'OPENAI_COMPATIBLE', :baseUrl, 'ACTIVE')
                        """)
                .param("id", BinaryUuids.toBytes(providerId))
                .param("code", PROVIDER_CODE)
                .param("baseUrl", PROVIDER_BASE_URL)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO model (
                            id, provider_id, model_key, display_name, context_window, max_output_tokens,
                            capabilities, input_price_per_1m, output_price_per_1m, status
                        ) VALUES (
                            :id, :providerId, :modelKey, 'M04 Catalog Model', 32768, 2048,
                            JSON_OBJECT('chat', TRUE, 'stream', TRUE), 3.000000, 6.000000, 'ACTIVE'
                        )
                        """)
                .param("id", BinaryUuids.toBytes(modelId))
                .param("providerId", BinaryUuids.toBytes(providerId))
                .param("modelKey", modelKey)
                .update();
        // 平台托管 ACTIVE key：只用于目录计数，密文是测试占位，不解密、不调用。
        jdbcClient.sql("""
                        INSERT INTO tenant_data_key (
                            tenant_id, key_id, purpose, wrapped_dek, wrap_nonce, algorithm, master_key_id
                        ) VALUES ('platform', :keyId, 'model-credential', X'00', X'000000000000',
                                  'AES-256-GCM', 'mk-test')
                        """).param("keyId", keyId).update();
        jdbcClient
                .sql("""
                        INSERT INTO provider_credential (
                            tenant_id, id, provider_id, credential_type, ciphertext, key_id,
                            master_key_id, version, status
                        ) VALUES ('platform', :id, :providerId, 'API_KEY', 'enc:v1:test:AAAA', :keyId,
                                  'mk-test', 1, 'ACTIVE')
                        """)
                .param("id", BinaryUuids.toBytes(UuidV7.generate()))
                .param("providerId", BinaryUuids.toBytes(providerId))
                .param("keyId", keyId)
                .update();
    }

    /** 员工目录只返回可路由模型，且不含 provider/baseUrl/价格。 */
    @Test
    @DisplayName("员工 /models 不含 provider、地址与价格")
    void employeeCatalogExcludesAdminFields() {
        LoginSession member = createMember();

        ApiTestClient.ApiResponse response = client.get("/api/v1/models", member.sessionCookie(), Map.of());

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = client.json(response);
        assertThat(body.path("items").isArray()).isTrue();
        JsonNode item = findItem(body.path("items"), modelKey);
        assertThat(item).as("seeded model must be routable in DEMO mode").isNotNull();
        assertThat(item.path("id").asText()).isEqualTo(modelId.toString());
        assertThat(item.path("displayName").asText()).isEqualTo("M04 Catalog Model");
        assertThat(item.path("contextWindow").asInt()).isEqualTo(32768);
        assertThat(item.path("maxOutputTokens").asInt()).isEqualTo(2048);
        assertThat(item.path("status").asText()).isEqualTo("ACTIVE");
        // 员工视图不得出现任何平台字段：schema 允许也不等于实现应返回。
        assertThat(item.has("providerId")).isFalse();
        assertThat(item.has("providerCode")).isFalse();
        assertThat(item.has("baseUrl")).isFalse();
        assertThat(item.has("environmentLabel")).isFalse();
        assertThat(item.has("inputPricePer1M")).isFalse();
        assertThat(item.has("outputPricePer1M")).isFalse();
        assertThat(item.has("pricingStatus")).isFalse();
    }

    /** 平台端点目录返回标签、允许状态、key 数量与模型数量。 */
    @Test
    @DisplayName("平台 /admin/model-providers 返回标签与 key 数量")
    void adminProviderCatalogShowsDeploymentMetadata() {
        LoginSession admin = loginPlatformAdmin();

        ApiTestClient.ApiResponse response =
                client.get("/api/v1/admin/model-providers", admin.sessionCookie(), Map.of());

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode provider = findItemByProperty(client.json(response).path("items"), "code", PROVIDER_CODE);
        assertThat(provider).isNotNull();
        assertThat(provider.path("id").asText()).isEqualTo(providerId.toString());
        assertThat(provider.path("baseUrl").asText()).isEqualTo(PROVIDER_BASE_URL);
        assertThat(provider.path("environmentLabel").asText()).isEqualTo("PRODUCTION");
        assertThat(provider.path("allowed").asBoolean()).isTrue();
        assertThat(provider.path("activeCredentialCount").asLong()).isEqualTo(1);
        assertThat(provider.path("modelCount").asLong()).isEqualTo(1);
        assertThat(provider.path("protocol").asText()).isEqualTo("OPENAI_COMPATIBLE");
    }

    /** 平台模型目录返回价格、币种与定价状态。 */
    @Test
    @DisplayName("平台 /admin/models 返回价格与 pricingStatus")
    void adminModelCatalogShowsPricing() {
        LoginSession admin = loginPlatformAdmin();

        ApiTestClient.ApiResponse response = client.get("/api/v1/admin/models", admin.sessionCookie(), Map.of());

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode model = findItem(client.json(response).path("items"), modelKey);
        assertThat(model).isNotNull();
        assertThat(model.path("providerId").asText()).isEqualTo(providerId.toString());
        assertThat(model.path("providerCode").asText()).isEqualTo(PROVIDER_CODE);
        assertThat(model.path("environmentLabel").asText()).isEqualTo("PRODUCTION");
        assertThat(model.path("inputPricePer1M").asText()).isEqualTo("3.000000");
        assertThat(model.path("outputPricePer1M").asText()).isEqualTo("6.000000");
        assertThat(model.path("priceCurrency").asText()).isEqualTo("CNY");
        assertThat(model.path("pricingStatus").asText()).isEqualTo("PRICED");
    }

    /** 模型启停写审计；停用后员工目录立即不再返回。 */
    @Test
    @DisplayName("PATCH /admin/models 启停并写 model.updated")
    void patchModelTogglesStatusAndAudits() {
        LoginSession admin = loginPlatformAdmin();
        LoginSession member = createMember();

        ApiTestClient.ApiResponse disabled = client.patch(
                "/api/v1/admin/models/" + modelId,
                client.jsonBody("status", "DISABLED"),
                admin.sessionCookie(),
                admin.csrfToken());
        assertThat(disabled.status()).as(disabled.body()).isEqualTo(200);
        assertThat(client.json(disabled).path("status").asText()).isEqualTo("DISABLED");

        assertThat(auditRows("model.updated"))
                .anySatisfy(row -> assertThat(row.details())
                        .contains(modelId.toString())
                        .contains(providerId.toString())
                        .contains("DISABLED"));

        ApiTestClient.ApiResponse afterDisable = client.get("/api/v1/models", member.sessionCookie(), Map.of());
        assertThat(findItem(client.json(afterDisable).path("items"), modelKey)).isNull();

        ApiTestClient.ApiResponse enabled = client.patch(
                "/api/v1/admin/models/" + modelId,
                client.jsonBody("status", "ACTIVE"),
                admin.sessionCookie(),
                admin.csrfToken());
        assertThat(enabled.status()).as(enabled.body()).isEqualTo(200);
        ApiTestClient.ApiResponse afterEnable = client.get("/api/v1/models", member.sessionCookie(), Map.of());
        assertThat(findItem(client.json(afterEnable).path("items"), modelKey)).isNotNull();
    }

    /** 非法状态返回字段级 400，而不是通用反序列化错误。 */
    @Test
    @DisplayName("PATCH /admin/models 非法状态返回 RICCON-MODEL-400")
    void patchModelRejectsInvalidStatus() {
        LoginSession admin = loginPlatformAdmin();

        ApiTestClient.ApiResponse response = client.patch(
                "/api/v1/admin/models/" + modelId,
                client.jsonBody("status", "PAUSED"),
                admin.sessionCookie(),
                admin.csrfToken());

        assertThat(response.status()).isEqualTo(400);
        JsonNode problem = client.json(response);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-MODEL-400");
        assertThat(problem.path("field").asText()).isEqualTo("status");
        assertThat(problem.path("reason").asText()).isEqualTo("INVALID_STATUS");
    }

    /** provider 只允许改名，部署字段一律 400；URL 非法返回具体原因。 */
    @Test
    @DisplayName("PUT /admin/model-providers 只改 name，只读字段 400")
    void putProviderOnlyRenames() {
        LoginSession admin = loginPlatformAdmin();
        String path = "/api/v1/admin/model-providers/" + providerId;

        ApiTestClient.ApiResponse renamed =
                client.put(path, client.jsonBody("name", "Renamed Provider"), admin.sessionCookie(), admin.csrfToken());
        assertThat(renamed.status()).as(renamed.body()).isEqualTo(200);
        assertThat(client.json(renamed).path("name").asText()).isEqualTo("Renamed Provider");
        assertThat(client.json(renamed).path("allowed").asBoolean()).isTrue();

        ApiTestClient.ApiResponse httpUrl = client.put(
                path, client.jsonBody("baseUrl", "http://10.20.30.40/v1"), admin.sessionCookie(), admin.csrfToken());
        assertThat(httpUrl.status()).isEqualTo(400);
        assertThat(client.json(httpUrl).path("code").asText()).isEqualTo("RICCON-MODEL-400");
        assertThat(client.json(httpUrl).path("field").asText()).isEqualTo("baseUrl");
        assertThat(client.json(httpUrl).path("reason").asText()).isEqualTo("ENDPOINT_SCHEME_NOT_ALLOWED");

        ApiTestClient.ApiResponse immutableUrl = client.put(
                path, client.jsonBody("baseUrl", "https://10.20.30.41/v1"), admin.sessionCookie(), admin.csrfToken());
        assertThat(immutableUrl.status()).isEqualTo(400);
        assertThat(client.json(immutableUrl).path("field").asText()).isEqualTo("baseUrl");
        assertThat(client.json(immutableUrl).path("reason").asText())
                .isEqualTo("ENDPOINT_IMMUTABLE_DEPLOYMENT_MANAGED");

        ApiTestClient.ApiResponse immutableLabel = client.put(
                path, client.jsonBody("environmentLabel", "DEMO_ONLY"), admin.sessionCookie(), admin.csrfToken());
        assertThat(immutableLabel.status()).isEqualTo(400);
        assertThat(client.json(immutableLabel).path("field").asText()).isEqualTo("environmentLabel");

        ApiTestClient.ApiResponse immutableProtocol = client.put(
                path, client.jsonBody("protocol", "OPENAI_COMPATIBLE"), admin.sessionCookie(), admin.csrfToken());
        assertThat(immutableProtocol.status()).isEqualTo(400);
        assertThat(client.json(immutableProtocol).path("field").asText()).isEqualTo("protocol");
    }

    /** 没有端点创建 API：POST 集合路径返回 405 并列出 Allow。 */
    @Test
    @DisplayName("POST /admin/model-providers 返回 405")
    void providerCreateRouteIsNotImplemented() {
        LoginSession admin = loginPlatformAdmin();

        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/admin/model-providers",
                client.jsonBody("name", "New Provider"),
                admin.sessionCookie(),
                admin.csrfToken(),
                Map.of());

        assertThat(response.status()).isEqualTo(405);
        assertThat(response.header("allow")).contains("GET");
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-METHOD-405");
    }

    /** 租户管理员无权访问平台模型目录。 */
    @Test
    @DisplayName("租户管理员访问平台模型目录返回 403")
    void tenantAdminCannotReadPlatformCatalog() {
        LoginSession platformAdmin = loginPlatformAdmin();
        String tenantCode = uniqueTenantCode("m04cat");
        createTenant(platformAdmin, tenantCode);
        LoginSession tenantAdmin = createAndLoginTenantAdmin(platformAdmin, tenantCode);

        ApiTestClient.ApiResponse providers =
                client.get("/api/v1/admin/model-providers", tenantAdmin.sessionCookie(), Map.of());
        ApiTestClient.ApiResponse models = client.get("/api/v1/admin/models", tenantAdmin.sessionCookie(), Map.of());

        assertThat(providers.status()).isEqualTo(403);
        assertThat(models.status()).isEqualTo(403);
    }

    /** 创建并登录员工。 */
    private LoginSession createMember() {
        LoginSession admin = loginPlatformAdmin();
        String tenantCode = uniqueTenantCode("m04cat");
        createTenant(admin, tenantCode);
        return createAndLoginMember(admin, tenantCode);
    }

    /** 清理本测试的目录与平台 key，避免跨测试/跨类漂移。 */
    private void cleanup() {
        Optional<UUID> existingProvider = jdbcClient
                .sql("SELECT id FROM model_provider WHERE code = :code")
                .param("code", PROVIDER_CODE)
                .query((rs, rowNum) -> BinaryUuids.fromBytes(rs.getBytes("id")))
                .optional();
        existingProvider.ifPresent(id -> {
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
        jdbcClient
                .sql("DELETE FROM tenant_data_key WHERE tenant_id = 'platform' AND key_id LIKE 'm04-key-%'")
                .update();
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

    /** 按属性值查找目录项。 */
    private static JsonNode findItemByProperty(JsonNode items, String property, String value) {
        for (JsonNode item : items) {
            if (value.equals(item.path(property).asText())) {
                return item;
            }
        }
        return null;
    }
}
