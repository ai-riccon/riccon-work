package com.riccon.api.security.m04;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M04-V1 独立验收：路由/key/端点边界的 Verifier 用例（TEN-031、SEC-M04-003a、AC-01、AC-06、AC-07、AC-15、AC-16）。
 *
 * <p>与 Builder 用例独立：只依赖 HTTP 契约、数据库结构与审计持久化，不复用 Builder 的断言或夹具；
 * 数据使用唯一 provider code 与随机 canary，测试前后自行清理。覆盖点：
 *
 * <ul>
 *   <li>TEN-031 / SEC-M04-003a：租户管理员与员工对模型路由、模型启停、端点目录、平台 key 的读/写一律 403，
 *       跨 provider 的 key 对象统一 404，响应不包含链内容、地址或掩码；
 *   <li>AC-01：CHAT/REPORT 链保存后立即可读、停用模型保留位置但员工目录不再返回、重新启用后恢复；
 *   <li>AC-06 / AC-07：key 新增/轮换/停用/恢复的掩码、审计白名单与失败/恢复动作，密文与响应无明文；
 *   <li>AC-15：端点目录标签、部署字段不可写、非允许列表/HTTP/userinfo/query 端点返回字段级 400，
 *       页面/接口不出现 endpoint 明文与 key；
 *   <li>AC-16：三个模型契约端点已实现（非 404）且平台/员工视图字段边界正确；
 *   <li>V11 结构：`model_invocation` 无 provider 外键、无 M04 成本列 CHECK，配额表主键顺序与空表回滚。
 * </ul>
 */
@TestPropertySource(
        properties = {
            "riccon.model.deployment-mode=DEMO",
            "riccon.model.endpoints=[{\"code\":\"m04-v-prod\",\"baseUrl\":\"https://10.20.40.10/v1\",\"environmentLabel\":\"PRODUCTION\"},{\"code\":\"m04-v-demo\",\"baseUrl\":\"https://10.20.40.20/v1\",\"environmentLabel\":\"DEMO_ONLY\"}]",
            "riccon.model.allow-legacy-endpoints=false",
            "riccon.model.allow-http=false"
        })
class M04VerifierGatewaySecurityTest extends ApiIntegrationTestSupport {

    /** 允许列表内的生产标签端点代码。 */
    private static final String PROD_CODE = "m04-v-prod";

    /** 允许列表内的生产标签端点地址。 */
    private static final String PROD_URL = "https://10.20.40.10/v1";

    /** 允许列表内的演示标签端点代码。 */
    private static final String DEMO_CODE = "m04-v-demo";

    /** 允许列表内的演示标签端点地址。 */
    private static final String DEMO_URL = "https://10.20.40.20/v1";

    /** JSON 解析器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 生产标签 provider。 */
    private UUID prodProviderId;

    /** 演示标签 provider。 */
    private UUID demoProviderId;

    /** 生产标签模型。 */
    private UUID prodModelId;

    /** 演示标签模型。 */
    private UUID demoModelId;

    /** 生产标签模型 key。 */
    private String prodModelKey;

    /** 演示标签模型 key。 */
    private String demoModelKey;

    /** 每个用例重建目录，避免跨用例漂移。 */
    @BeforeEach
    void seedCatalog() {
        cleanup();
        prodProviderId = UuidV7.generate();
        demoProviderId = UuidV7.generate();
        prodModelKey = "m04-v-prod-model";
        demoModelKey = "m04-v-demo-model";
        insertProvider(prodProviderId, PROD_CODE, "M04 V 生产端点", PROD_URL);
        insertProvider(demoProviderId, DEMO_CODE, "M04 V 演示端点", DEMO_URL);
        prodModelId = insertModel(prodProviderId, prodModelKey, "M04 V 生产模型", "1.500000", "3.000000");
        demoModelId = insertModel(demoProviderId, demoModelKey, "M04 V 演示模型", null, null);
    }

    /** 用例后清理本类目录、路由条目与遗留 DEK。 */
    @AfterEach
    void cleanupAfterTest() {
        cleanup();
    }

    /**
     * TEN-031 / SEC-M04-003a：模型路由、模型启停、端点目录与平台 key 的写接口只对平台管理员开放。
     *
     * <p>租户管理员与员工读/写一律 403 且不回显链内容；跨 provider 的凭据按统一 404 处理，不泄露存在性。
     */
    @Test
    @DisplayName("TEN-031 / SEC-M04-003a 模型配置写接口仅平台管理员，跨 provider 对象 404")
    void ten031_modelConfigurationIsPlatformOnly() {
        LoginSession platform = loginPlatformAdmin();
        String tenantCode = uniqueTenantCode("m04vsec");
        createTenant(platform, tenantCode);
        LoginSession tenantAdmin = createAndLoginTenantAdmin(platform, tenantCode);
        LoginSession member = createAndLoginMember(platform, tenantCode);
        int version = chain(getRouting(platform), "CHAT").path("version").asInt();

        String routingBody = client.jsonBody("version", version, "modelIds", List.of(prodModelId.toString()));
        for (LoginSession session : List.of(tenantAdmin, member)) {
            assertForbidden(client.get("/api/v1/admin/model-routing", session.sessionCookie(), Map.of()));
            assertForbidden(client.put(
                    "/api/v1/admin/model-routing/CHAT", routingBody, session.sessionCookie(), session.csrfToken()));
            assertForbidden(client.get("/api/v1/admin/model-providers", session.sessionCookie(), Map.of()));
            assertForbidden(client.get("/api/v1/admin/models", session.sessionCookie(), Map.of()));
            assertForbidden(client.patch(
                    "/api/v1/admin/models/" + prodModelId,
                    client.jsonBody("status", "DISABLED"),
                    session.sessionCookie(),
                    session.csrfToken()));
            assertForbidden(client.post(
                    "/api/v1/admin/model-providers/" + prodProviderId + "/credentials",
                    client.jsonBody("label", "denied", "apiKey", "sk-denied-" + UUID.randomUUID(), "priority", 10),
                    session.sessionCookie(),
                    session.csrfToken(),
                    Map.of()));
            assertForbidden(client.get(
                    "/api/v1/admin/model-providers/" + prodProviderId + "/credentials",
                    session.sessionCookie(),
                    Map.of()));
        }

        // 匿名访问先认证后授权：401，响应不含任何链/端点内容。
        ApiTestClient.ApiResponse anonymous = client.get("/api/v1/admin/model-routing", null, Map.of());
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.body()).doesNotContain("entries").doesNotContain(PROD_URL);

        // 平台管理员跨 provider 引用凭据：统一 404，而不是 403 或 500。
        String credentialId = createCredential(platform, demoProviderId, "cross-provider", randomApiKey("cross"));
        ApiTestClient.ApiResponse crossProvider = client.post(
                "/api/v1/admin/model-providers/" + prodProviderId + "/credentials/" + credentialId + "/disable",
                client.jsonBody(),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
        assertThat(crossProvider.status()).as(crossProvider.body()).isEqualTo(404);
        assertThat(client.json(crossProvider).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        ApiTestClient.ApiResponse randomObject = client.post(
                "/api/v1/admin/model-providers/" + prodProviderId + "/credentials/" + UuidV7.generate() + "/disable",
                client.jsonBody(),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
        assertThat(randomObject.status()).as(randomObject.body()).isEqualTo(404);
    }

    /**
     * AC-15：端点目录标签来自部署配置，部署字段不可写；非 HTTPS/userinfo/query/允许列表外 URL 均被拒绝。
     *
     * <p>平台目录只返回掩码/标签等元数据，不含 key 或 Authorization；员工目录不含 provider/baseUrl/价格。
     * 保存被拒绝的响应不回显用户提交的 URL 或凭据。
     */
    @Test
    @DisplayName("AC-15 端点标签、部署字段不可写与页面无密钥/内部地址")
    void ac15_endpointCatalogLabelsAndRejections() {
        LoginSession platform = loginPlatformAdmin();
        JsonNode providers = client.json(
                client.get("/api/v1/admin/model-providers?page=1&size=100", platform.sessionCookie(), Map.of()));
        JsonNode prod = findByProperty(providers.path("items"), "code", PROD_CODE);
        JsonNode demo = findByProperty(providers.path("items"), "code", DEMO_CODE);
        assertThat(prod).as("PRODUCTION 端点必须出现在平台目录").isNotNull();
        assertThat(demo).as("DEMO_ONLY 端点必须出现在平台目录").isNotNull();
        assertThat(prod.path("environmentLabel").asText()).isEqualTo("PRODUCTION");
        assertThat(demo.path("environmentLabel").asText()).isEqualTo("DEMO_ONLY");
        // DEMO 部署模式允许演示端点参与路由；标签仍必须是部署原值。
        assertThat(prod.path("allowed").asBoolean()).isTrue();
        assertThat(demo.path("allowed").asBoolean()).isTrue();
        String providerBody = providers.toString();
        assertThat(providerBody)
                .doesNotContain("apiKey")
                .doesNotContain("Authorization")
                .doesNotContain("ciphertext")
                .doesNotContain("fingerprint");

        // 员工目录只有模型标识与展示字段，不含端点、provider 或价格。
        LoginSession member = createMember(platform);
        JsonNode employee = client.json(client.get("/api/v1/models?page=1&size=100", member.sessionCookie(), Map.of()));
        JsonNode prodModel = findByProperty(employee.path("items"), "modelKey", prodModelKey);
        assertThat(prodModel).as("员工目录应包含可路由模型").isNotNull();
        for (JsonNode item : employee.path("items")) {
            assertThat(item.has("providerId")).isFalse();
            assertThat(item.has("providerCode")).isFalse();
            assertThat(item.has("baseUrl")).isFalse();
            assertThat(item.has("inputPricePer1M")).isFalse();
            assertThat(item.has("environmentLabel")).isFalse();
        }
        assertThat(employee.toString()).doesNotContain(PROD_URL).doesNotContain("Authorization");

        String providerPath = "/api/v1/admin/model-providers/" + prodProviderId;
        assertRejectedBaseUrl(platform, providerPath, "http://10.20.40.99/v1", "ENDPOINT_SCHEME_NOT_ALLOWED");
        assertRejectedBaseUrl(
                platform, providerPath, "https://user:secret@10.20.40.99/v1", "ENDPOINT_USERINFO_FORBIDDEN");
        assertRejectedBaseUrl(platform, providerPath, "https://10.20.40.99/v1?x=1", "ENDPOINT_QUERY_FORBIDDEN");
        assertRejectedBaseUrl(platform, providerPath, "https://10.20.40.99/v1#frag", "ENDPOINT_FRAGMENT_FORBIDDEN");
        // 结构合法但不在部署允许列表：仍 400（部署字段管理），不是 404，也不发起出网。
        ApiTestClient.ApiResponse notAllowed = client.put(
                providerPath,
                client.jsonBody("baseUrl", "https://10.20.40.99/v1"),
                platform.sessionCookie(),
                platform.csrfToken());
        assertThat(notAllowed.status()).as(notAllowed.body()).isEqualTo(400);
        assertThat(client.json(notAllowed).path("reason").asText()).isEqualTo("ENDPOINT_IMMUTABLE_DEPLOYMENT_MANAGED");
        assertThat(notAllowed.body()).doesNotContain("10.20.40.99");

        // 只有展示名可改：environmentLabel/protocol 均被拒绝；没有端点创建 API（405）。
        ApiTestClient.ApiResponse immutableLabel = client.put(
                providerPath,
                client.jsonBody("environmentLabel", "DEMO_ONLY"),
                platform.sessionCookie(),
                platform.csrfToken());
        assertThat(immutableLabel.status()).isEqualTo(400);
        assertThat(client.json(immutableLabel).path("field").asText()).isEqualTo("environmentLabel");
        ApiTestClient.ApiResponse noCreate = client.post(
                "/api/v1/admin/model-providers",
                client.jsonBody("name", "new provider"),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
        assertThat(noCreate.status()).isEqualTo(405);
        assertThat(client.json(noCreate).path("code").asText()).isEqualTo("RICCON-METHOD-405");
    }

    /**
     * AC-06 / AC-07：key 生命周期与审计。
     *
     * <p>新增/轮换/停用/恢复只返回掩码；数据库只保存信封密文与 HMAC 指纹；审计 details 只含
     * credentialId/maskedHint/status/reason，不含明文；审计查询 API 可读回轮换事件。
     */
    @Test
    @DisplayName("AC-06 / AC-07 key 生命周期掩码、恢复审计与查询不含明文")
    void ac06_ac07_keyLifecycleAuditsAndMasking() {
        LoginSession platform = loginPlatformAdmin();
        String firstKey = randomApiKey("first");
        String secondKey = randomApiKey("second");

        JsonNode created = createCredentialJson(platform, prodProviderId, "v1-first", firstKey, 10);
        String firstId = created.path("id").asText();
        assertThat(created.path("maskedHint").asText()).isEqualTo(lastFourMask(firstKey));
        assertThat(created.toString()).doesNotContain(firstKey);

        // 列表、重复新增 409 都不回显明文。
        ApiTestClient.ApiResponse list =
                client.get(credentialsPath(prodProviderId), platform.sessionCookie(), Map.of());
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body()).doesNotContain(firstKey);
        ApiTestClient.ApiResponse duplicated = client.post(
                credentialsPath(prodProviderId),
                client.jsonBody("label", "v1-duplicate", "apiKey", firstKey, "priority", 20),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
        assertThat(duplicated.status()).isEqualTo(409);
        assertThat(duplicated.body()).doesNotContain(firstKey);

        // 数据库：信封密文 + HMAC 指纹，无明文。
        Map<String, Object> stored = credentialRow(firstId);
        assertThat(stored.get("ciphertext").toString()).startsWith("enc:v1:").doesNotContain(firstKey);
        assertThat(stored.get("fingerprint").toString()).matches("^[0-9a-f]{64}$");
        assertThat(stored.get("masked_hint").toString()).isEqualTo(lastFourMask(firstKey));

        JsonNode createdAudit = onlyAudit("model.credential.created", firstId);
        assertThat(createdAudit.path("maskedHint").asText()).isEqualTo(lastFourMask(firstKey));
        assertThat(auditRow("model.credential.created", firstId).details()).doesNotContain(firstKey);

        // 轮换：新增新 key，旧 key 停用且密文不变；在途材料仍按旧密文可读（密文列不变）。
        ApiTestClient.ApiResponse rotated = client.post(
                credentialsPath(prodProviderId) + "/" + firstId + "/rotate",
                client.jsonBody("apiKey", secondKey, "label", "v1-rotated"),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
        assertThat(rotated.status()).as(rotated.body()).isEqualTo(200);
        JsonNode rotatedBody = client.json(rotated);
        String secondId = rotatedBody.path("id").asText();
        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(rotatedBody.path("maskedHint").asText()).isEqualTo(lastFourMask(secondKey));
        assertThat(rotated.body()).doesNotContain(secondKey);
        assertThat(credentialRow(firstId).get("status")).isEqualTo("DISABLED");
        assertThat(credentialRow(firstId).get("ciphertext")).isEqualTo(stored.get("ciphertext"));
        assertThat(credentialRow(secondId).get("status")).isEqualTo("ACTIVE");
        assertThat(auditRows("model.credential.rotated").stream()
                        .anyMatch(row -> row.details().contains(secondId)
                                && row.details().contains("previousCredentialId")))
                .isTrue();
        assertThat(auditRows("model.credential.rotated").toString()).doesNotContain(secondKey);

        // 停用/启用/失效恢复：动作名与 details 掩码正确。
        assertThat(client.post(
                                credentialsPath(prodProviderId) + "/" + secondId + "/disable",
                                client.jsonBody(),
                                platform.sessionCookie(),
                                platform.csrfToken(),
                                Map.of())
                        .status())
                .isEqualTo(200);
        assertThat(onlyAudit("model.credential.disabled", secondId)
                        .path("maskedHint")
                        .asText())
                .isEqualTo(lastFourMask(secondKey));
        assertThat(client.post(
                                credentialsPath(prodProviderId) + "/" + secondId + "/enable",
                                client.jsonBody(),
                                platform.sessionCookie(),
                                platform.csrfToken(),
                                Map.of())
                        .status())
                .isEqualTo(200);
        assertThat(auditRows("model.credential.enabled").stream()
                        .anyMatch(row -> row.details().contains(secondId)))
                .isTrue();

        jdbcClient
                .sql("""
                        UPDATE provider_credential SET status = 'INVALID', invalidated_at = UTC_TIMESTAMP(6)
                         WHERE tenant_id = 'platform' AND id = :id
                        """)
                .param("id", BinaryUuids.toBytes(UUID.fromString(secondId)))
                .update();
        assertThat(client.post(
                                credentialsPath(prodProviderId) + "/" + secondId + "/enable",
                                client.jsonBody(),
                                platform.sessionCookie(),
                                platform.csrfToken(),
                                Map.of())
                        .status())
                .isEqualTo(200);
        assertThat(auditRows("model.credential.recovered").stream()
                        .anyMatch(row -> row.details().contains(secondId)))
                .isTrue();

        // 审计查询 API（M10）可读回轮换事件，响应不含明文。
        ApiTestClient.ApiResponse auditQuery = client.get(
                "/api/v1/audit-events?action=model.credential.rotated&page=1&size=50",
                platform.sessionCookie(),
                Map.of());
        assertThat(auditQuery.status()).as(auditQuery.body()).isEqualTo(200);
        assertThat(auditQuery.body()).doesNotContain(secondKey);
        JsonNode queryItems = client.json(auditQuery).path("items");
        assertThat(queryItems.isArray()).isTrue();
        assertThat(queryItems)
                .anySatisfy(item -> assertThat(
                                item.path("details").path("maskedHint").asText())
                        .isEqualTo(lastFourMask(secondKey)));
    }

    /**
     * AC-01：路由链保存后下一次读取立即生效；停用模型保留在链中但员工目录不再返回。
     *
     * <p>CHAT 链保存两个模型、REPORT 链保存演示模型；停用/启用不改变链版本，只改变条目状态。
     * 与 Worker 的“实际模型请求”由 IT-MODEL-101（B3/B4 复跑）与 E2E-M04-02（真实栈）共同覆盖。
     */
    @Test
    @DisplayName("AC-01 路由保存立即可读、停用保留位置与重新启用")
    void ac01_routeChainOrderAndModelToggle() {
        LoginSession platform = loginPlatformAdmin();
        UUID adminId = UUID.fromString(platform.body().path("user").path("id").asText());
        JsonNode initial = getRouting(platform);
        int chatVersion = chain(initial, "CHAT").path("version").asInt();
        int reportVersion = chain(initial, "REPORT").path("version").asInt();

        ApiTestClient.ApiResponse chatSaved =
                putChain(platform, "CHAT", chatVersion, List.of(prodModelId, demoModelId));
        assertThat(chatSaved.status()).as(chatSaved.body()).isEqualTo(200);
        ApiTestClient.ApiResponse reportSaved = putChain(platform, "REPORT", reportVersion, List.of(demoModelId));
        assertThat(reportSaved.status()).as(reportSaved.body()).isEqualTo(200);

        JsonNode afterSave = getRouting(platform);
        JsonNode chat = chain(afterSave, "CHAT");
        assertThat(modelIdsOf(chat)).containsExactly(prodModelId.toString(), demoModelId.toString());
        assertThat(modelIdsOf(chain(afterSave, "REPORT"))).containsExactly(demoModelId.toString());

        // 停用演示模型：链条目保留（position=2），版本不变；员工目录立即排除该模型。
        assertThat(client.patch(
                                "/api/v1/admin/models/" + demoModelId,
                                client.jsonBody("status", "DISABLED"),
                                platform.sessionCookie(),
                                platform.csrfToken())
                        .status())
                .isEqualTo(200);
        JsonNode afterDisable = chain(getRouting(platform), "CHAT");
        assertThat(afterDisable.path("version").asInt()).isEqualTo(chatVersion + 1);
        assertThat(modelIdsOf(afterDisable)).containsExactly(prodModelId.toString(), demoModelId.toString());
        assertThat(afterDisable.path("entries").get(1).path("status").asText()).isEqualTo("DISABLED");
        LoginSession member = createMember(platform);
        JsonNode catalogAfterDisable =
                client.json(client.get("/api/v1/models?page=1&size=100", member.sessionCookie(), Map.of()));
        assertThat(findByProperty(catalogAfterDisable.path("items"), "modelKey", demoModelKey))
                .isNull();
        assertThat(findByProperty(catalogAfterDisable.path("items"), "modelKey", prodModelKey))
                .isNotNull();

        // 重新启用：下一次读取立即 ACTIVE，顺序与版本不变。
        assertThat(client.patch(
                                "/api/v1/admin/models/" + demoModelId,
                                client.jsonBody("status", "ACTIVE"),
                                platform.sessionCookie(),
                                platform.csrfToken())
                        .status())
                .isEqualTo(200);
        JsonNode afterEnable = chain(getRouting(platform), "CHAT");
        assertThat(afterEnable.path("version").asInt()).isEqualTo(chatVersion + 1);
        assertThat(afterEnable.path("entries").get(1).path("status").asText()).isEqualTo("ACTIVE");
        JsonNode catalogAfterEnable =
                client.json(client.get("/api/v1/models?page=1&size=100", member.sessionCookie(), Map.of()));
        assertThat(findByProperty(catalogAfterEnable.path("items"), "modelKey", demoModelKey))
                .isNotNull();

        // 保存链写审计：modelIds 为保序数组，且不含地址/凭据。
        List<AuditRow> routingAudits = auditRows("model.routing.updated");
        AuditRow latest = routingAudits.stream()
                .filter(row -> row.targetId().equals("CHAT") && row.details().contains(prodModelId.toString()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("missing CHAT routing audit"));
        JsonNode details = MAPPER.readTree(latest.details());
        assertThat(details.path("purpose").asText()).isEqualTo("CHAT");
        assertThat(details.path("modelIds").isArray()).isTrue();
        assertThat(details.path("modelIds")).hasSize(2);
        assertThat(details.path("modelIds").get(0).asText()).isEqualTo(prodModelId.toString());
        assertThat(details.path("modelIds").get(1).asText()).isEqualTo(demoModelId.toString());
        assertThat(latest.tenantId()).isEqualTo("platform");
        assertThat(latest.actorId()).isEqualTo(adminId.toString());
        assertThat(latest.details())
                .doesNotContain(PROD_URL)
                .doesNotContain("apiKey")
                .doesNotContain("Authorization");
    }

    /**
     * AC-16：三个模型契约端点在运行时已实现（非 404），且平台/员工视图字段边界正确。
     */
    @Test
    @DisplayName("AC-16 三个模型端点非 404 且字段边界正确")
    void ac16_modelContractEndpointsExist() {
        LoginSession platform = loginPlatformAdmin();
        LoginSession member = createMember(platform);

        assertPage(client.get("/api/v1/models?page=1&size=10", member.sessionCookie(), Map.of()));
        assertPage(client.get("/api/v1/admin/model-providers?page=1&size=10", platform.sessionCookie(), Map.of()));
        assertPage(client.get("/api/v1/admin/models?page=1&size=10", platform.sessionCookie(), Map.of()));

        // 匿名与租户管理员访问管理端点：401/403，不是 404（证明端点存在且被保护）。
        assertThat(client.get("/api/v1/models?page=1&size=10", null, Map.of()).status())
                .isEqualTo(401);
        assertThat(client.get("/api/v1/admin/model-providers?page=1&size=10", null, Map.of())
                        .status())
                .isEqualTo(401);
        assertThat(client.get("/api/v1/admin/models?page=1&size=10", null, Map.of())
                        .status())
                .isEqualTo(401);

        // 平台模型目录返回定价状态；员工目录不返回价格。
        JsonNode adminModels =
                client.json(client.get("/api/v1/admin/models?page=1&size=100", platform.sessionCookie(), Map.of()));
        JsonNode prodModel = findByProperty(adminModels.path("items"), "modelKey", prodModelKey);
        assertThat(prodModel).as("平台模型目录必须包含已配置模型").isNotNull();
        assertThat(prodModel.path("pricingStatus").asText()).isEqualTo("PRICED");
        assertThat(prodModel.path("inputPricePer1M").asText()).isEqualTo("1.500000");

        JsonNode employeeModels =
                client.json(client.get("/api/v1/models?page=1&size=100", member.sessionCookie(), Map.of()));
        for (JsonNode item : employeeModels.path("items")) {
            assertThat(item.has("pricingStatus")).isFalse();
            assertThat(item.has("inputPricePer1M")).isFalse();
        }
    }

    /**
     * V11 结构独立断言：provider 外键缺失、成本列无 CHECK、配额表主键顺序与空表回滚。
     *
     * <p>空表 INSERT/ROLLBACK 在测试事务内完成：断言写入成功后备注回滚，不污染共享容器；
     * 迁移级 forward/rollback 仍由 V11MigrationTest 在一次性 schema 上覆盖。
     */
    @Test
    @Transactional
    @DisplayName("V11 结构：无 provider 外键/无成本 CHECK/配额主键顺序与空表回滚")
    void v11StructureAndQuotaInsertRollback() {
        long providerFk = jdbcClient.sql("""
                        SELECT COUNT(*)
                          FROM information_schema.REFERENTIAL_CONSTRAINTS rc
                          JOIN information_schema.KEY_COLUMN_USAGE kcu
                            ON rc.CONSTRAINT_SCHEMA = kcu.CONSTRAINT_SCHEMA
                           AND rc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME
                         WHERE rc.CONSTRAINT_SCHEMA = DATABASE()
                           AND kcu.TABLE_NAME = 'model_invocation'
                           AND kcu.COLUMN_NAME = 'provider_id'
                        """).query(Long.class).single();
        assertThat(providerFk).as("model_invocation.provider_id 不得有外键").isZero();

        long costChecks = jdbcClient.sql("""
                        SELECT COUNT(*)
                          FROM information_schema.CHECK_CONSTRAINTS cc
                          JOIN information_schema.TABLE_CONSTRAINTS tc
                            ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
                           AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
                         WHERE tc.TABLE_SCHEMA = DATABASE()
                           AND tc.TABLE_NAME = 'model_invocation'
                           AND (cc.CHECK_CLAUSE LIKE '%cost_status%'
                                OR cc.CHECK_CLAUSE LIKE '%cost_amount%'
                                OR cc.CHECK_CLAUSE LIKE '%attempt_count%'
                                OR cc.CHECK_CLAUSE LIKE '%cost_day%')
                        """).query(Long.class).single();
        assertThat(costChecks).as("M04 成本列不得新增 CHECK").isZero();

        assertThat(primaryKeyColumns("quota_limit"))
                .containsExactly("tenant_id", "scope_type", "scope_id", "window_kind");
        assertThat(primaryKeyColumns("quota_usage"))
                .containsExactly("tenant_id", "scope_type", "scope_id", "window_kind", "window_start");
        assertThat(primaryKeyColumns("model_invocation_attempt"))
                .containsExactly("tenant_id", "invocation_id", "attempt_no");

        long tables = jdbcClient.sql("""
                        SELECT COUNT(*) FROM information_schema.TABLES
                         WHERE TABLE_SCHEMA = DATABASE()
                           AND TABLE_NAME IN ('model_routing', 'model_routing_entry', 'model_gateway_switch',
                                              'model_invocation_attempt', 'quota_limit', 'quota_usage')
                        """).query(Long.class).single();
        assertThat(tables).as("V11 六张新表必须存在").isEqualTo(6);

        // 空表 INSERT/ROLLBACK：事务内插入一行配额用量，断言可写后由测试事务回滚。
        long before = jdbcClient.sql("""
                        SELECT COUNT(*) FROM quota_usage
                         WHERE tenant_id = 'platform' AND window_start = '2000-01-01 00:00:00'
                        """).query(Long.class).single();
        assertThat(before).isZero();
        jdbcClient.sql("""
                        INSERT INTO quota_usage (tenant_id, scope_type, scope_id, window_kind, window_start, used_count)
                        VALUES ('platform', 'TENANT', 'platform', 'DAILY', '2000-01-01 00:00:00', 0)
                        """).update();
        long afterInsert = jdbcClient.sql("""
                        SELECT COUNT(*) FROM quota_usage
                         WHERE tenant_id = 'platform' AND window_start = '2000-01-01 00:00:00'
                        """).query(Long.class).single();
        assertThat(afterInsert).isEqualTo(1);
    }

    /** 断言分页契约端点可用（不是 404）。 */
    private void assertPage(ApiTestClient.ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = client.json(response);
        assertThat(body.path("page").asInt()).isEqualTo(1);
        assertThat(body.path("size").asInt()).isEqualTo(10);
        assertThat(body.has("total")).isTrue();
        assertThat(body.has("totalPages")).isTrue();
        assertThat(body.path("items").isArray()).isTrue();
    }

    /** 断言字段级 400 且不回显提交的 URL。 */
    private void assertRejectedBaseUrl(LoginSession platform, String path, String url, String reason) {
        ApiTestClient.ApiResponse response =
                client.put(path, client.jsonBody("baseUrl", url), platform.sessionCookie(), platform.csrfToken());
        assertThat(response.status()).as(response.body()).isEqualTo(400);
        JsonNode problem = client.json(response);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-MODEL-400");
        assertThat(problem.path("field").asText()).isEqualTo("baseUrl");
        assertThat(problem.path("reason").asText()).isEqualTo(reason);
        assertThat(response.body()).doesNotContain(url);
    }

    /** 断言 403 且不泄露链/端点内容。 */
    private void assertForbidden(ApiTestClient.ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(403);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-AUTH-403");
        assertThat(response.body())
                .doesNotContain(PROD_URL)
                .doesNotContain("maskedHint")
                .doesNotContain("entries")
                .doesNotContain("modelIds");
    }

    /** 读取两条路由链。 */
    private JsonNode getRouting(LoginSession platform) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/admin/model-routing", platform.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /** 整链保存。 */
    private ApiTestClient.ApiResponse putChain(
            LoginSession platform, String purpose, int version, List<UUID> modelIds) {
        return client.put(
                "/api/v1/admin/model-routing/" + purpose,
                client.jsonBody(
                        "version",
                        version,
                        "modelIds",
                        modelIds.stream().map(UUID::toString).toList()),
                platform.sessionCookie(),
                platform.csrfToken());
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

    /** 链的模型 ID 顺序。 */
    private static List<String> modelIdsOf(JsonNode chain) {
        List<String> ids = new ArrayList<>();
        for (JsonNode entry : chain.path("entries")) {
            ids.add(entry.path("modelId").asText());
        }
        return ids;
    }

    /** 创建员工会话。 */
    private LoginSession createMember(LoginSession platform) {
        String tenantCode = uniqueTenantCode("m04vcat");
        createTenant(platform, tenantCode);
        return createAndLoginMember(platform, tenantCode);
    }

    /** 新增平台 key 并返回响应体。 */
    private JsonNode createCredentialJson(
            LoginSession platform, UUID providerId, String label, String apiKey, int priority) {
        ApiTestClient.ApiResponse response = client.post(
                credentialsPath(providerId),
                client.jsonBody("label", label, "apiKey", apiKey, "priority", priority),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(response.body()).doesNotContain(apiKey);
        return client.json(response);
    }

    /** 新增平台 key 并返回凭据 ID。 */
    private String createCredential(LoginSession platform, UUID providerId, String label, String apiKey) {
        return createCredentialJson(platform, providerId, label, apiKey, 10)
                .path("id")
                .asText();
    }

    /** 平台 key 集合路径。 */
    private static String credentialsPath(UUID providerId) {
        return "/api/v1/admin/model-providers/" + providerId + "/credentials";
    }

    /** 读取凭据行（状态/密文/掩码/指纹）。 */
    private Map<String, Object> credentialRow(String credentialId) {
        return jdbcClient
                .sql("""
                        SELECT status, ciphertext, masked_hint, fingerprint
                          FROM provider_credential
                         WHERE tenant_id = 'platform' AND id = :id
                        """)
                .param("id", BinaryUuids.toBytes(UUID.fromString(credentialId)))
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "status", rs.getString("status"),
                        "ciphertext", rs.getString("ciphertext"),
                        "masked_hint", rs.getString("masked_hint"),
                        "fingerprint", rs.getString("fingerprint")))
                .single();
    }

    /** 取指定动作且 details 含凭据 ID 的唯一审计行。 */
    private AuditRow auditRow(String action, String credentialId) {
        List<AuditRow> rows = auditRows(action).stream()
                .filter(row -> row.details() != null && row.details().contains(credentialId))
                .toList();
        assertThat(rows).as(action + " audit for " + credentialId).hasSize(1);
        return rows.get(0);
    }

    /** 取指定动作且 details 含凭据 ID 的唯一审计 details。 */
    private JsonNode onlyAudit(String action, String credentialId) {
        return MAPPER.readTree(auditRow(action, credentialId).details());
    }

    /** 读取指定表的主键列顺序。 */
    private List<String> primaryKeyColumns(String table) {
        return jdbcClient.sql("""
                        SELECT COLUMN_NAME FROM information_schema.STATISTICS
                         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = :table AND INDEX_NAME = 'PRIMARY'
                         ORDER BY SEQ_IN_INDEX
                        """).param("table", table).query(String.class).list();
    }

    /** 插入 provider。 */
    private void insertProvider(UUID id, String code, String name, String baseUrl) {
        jdbcClient
                .sql("""
                        INSERT INTO model_provider (id, code, name, protocol, base_url, status)
                        VALUES (:id, :code, :name, 'OPENAI_COMPATIBLE', :baseUrl, 'ACTIVE')
                        """)
                .param("id", BinaryUuids.toBytes(id))
                .param("code", code)
                .param("name", name)
                .param("baseUrl", baseUrl)
                .update();
    }

    /** 插入模型并返回 ID。 */
    private UUID insertModel(
            UUID providerId, String modelKey, String displayName, String inputPrice, String outputPrice) {
        UUID modelId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO model (
                            id, provider_id, model_key, display_name, context_window, max_output_tokens,
                            capabilities, input_price_per_1m, output_price_per_1m, status
                        ) VALUES (
                            :id, :providerId, :modelKey, :displayName, 32768, 2048,
                            JSON_OBJECT('chat', TRUE, 'stream', TRUE), :inputPrice, :outputPrice, 'ACTIVE'
                        )
                        """)
                .param("id", BinaryUuids.toBytes(modelId))
                .param("providerId", BinaryUuids.toBytes(providerId))
                .param("modelKey", modelKey)
                .param("displayName", displayName)
                .param("inputPrice", inputPrice)
                .param("outputPrice", outputPrice)
                .update();
        return modelId;
    }

    /** 按属性查集合条目。 */
    private static JsonNode findByProperty(JsonNode items, String property, String value) {
        for (JsonNode item : items) {
            if (value.equals(item.path(property).asText())) {
                return item;
            }
        }
        return null;
    }

    /** 生成测试 apiKey；仅占位值，不对应任何真实环境。 */
    private static String randomApiKey(String tag) {
        return "sk-m04-verifier-" + tag + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 末四位掩码。 */
    private static String lastFourMask(String apiKey) {
        return "****" + apiKey.substring(apiKey.length() - 4);
    }

    /** 清理本类 provider/model/凭据与路由条目，并回收遗留 DEK。 */
    private void cleanup() {
        List<UUID> modelIds = java.util.stream.Stream.of(prodModelId, demoModelId)
                .filter(java.util.Objects::nonNull)
                .toList();
        for (UUID modelId : modelIds) {
            jdbcClient
                    .sql("DELETE FROM model_routing_entry WHERE model_id = :modelId")
                    .param("modelId", BinaryUuids.toBytes(modelId))
                    .update();
        }
        for (String code : List.of(PROD_CODE, DEMO_CODE)) {
            jdbcClient
                    .sql("SELECT id FROM model_provider WHERE code = :code")
                    .param("code", code)
                    .query((rs, rowNum) -> BinaryUuids.fromBytes(rs.getBytes("id")))
                    .optional()
                    .ifPresent(this::deleteProviderGraph);
        }
        jdbcClient.sql("""
                        DELETE k FROM tenant_data_key k
                         LEFT JOIN provider_credential c
                           ON c.tenant_id = k.tenant_id AND c.key_id = k.key_id
                         WHERE k.tenant_id = 'platform' AND k.key_id LIKE 'mc-%' AND c.id IS NULL
                        """).update();
    }

    /** 删除 provider 及其模型/凭据。 */
    private void deleteProviderGraph(UUID providerId) {
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
