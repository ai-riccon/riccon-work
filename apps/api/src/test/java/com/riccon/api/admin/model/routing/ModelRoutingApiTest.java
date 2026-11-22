package com.riccon.api.admin.model.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * 模型路由配置 API 的集成行为（IT-MODEL-101）。
 *
 * <p>覆盖 ADR-0021 §4.2/§4.3/§11.1：GET 返回 CHAT/REPORT 两条链；整链保存后下一次 GET（等价下一次请求读
 * 读模型）立即返回新链；停用模型保留在链中且顺序不变；版本冲突 409；空链、未知模型、不可路由端点、
 * 重复与超长 400。路由是全局平台资源，本测试不涉及租户路径。
 */
@TestPropertySource(
        properties = {
            "riccon.model.deployment-mode=DEMO",
            "riccon.model.endpoints=[{\"code\":\"m04-routing\",\"baseUrl\":\"https://10.20.30.50/v1\",\"environmentLabel\":\"PRODUCTION\"}]",
            "riccon.model.allow-legacy-endpoints=false",
            "riccon.model.allow-http=false"
        })
class ModelRoutingApiTest extends ApiIntegrationTestSupport {

    /** 允许列表内的供应商代码。 */
    private static final String PROVIDER_CODE = "m04-routing";

    /** 允许列表内的供应商地址。 */
    private static final String PROVIDER_BASE_URL = "https://10.20.30.50/v1";

    /** 不在允许列表内的供应商代码。 */
    private static final String BLOCKED_PROVIDER_CODE = "m04-routing-blocked";

    /** 不在允许列表内的供应商地址。 */
    private static final String BLOCKED_PROVIDER_BASE_URL = "https://10.20.30.51/v1";

    /** 模型 A。 */
    private UUID modelA;

    /** 模型 B（用于停用/启用）。 */
    private UUID modelB;

    /** 模型 C（REPORT）。 */
    private UUID modelC;

    /** 不可路由端点上的模型。 */
    private UUID blockedModel;

    /** 允许列表供应商。 */
    private UUID providerId;

    /** 不可路由供应商。 */
    private UUID blockedProviderId;

    /** 每个测试重建目录并清空路由条目，避免跨测试漂移。 */
    @BeforeEach
    void seedCatalogAndClearRouting() {
        cleanup();
        providerId = UuidV7.generate();
        blockedProviderId = UuidV7.generate();
        insertProvider(providerId, PROVIDER_CODE, PROVIDER_BASE_URL, "ACTIVE");
        insertProvider(blockedProviderId, BLOCKED_PROVIDER_CODE, BLOCKED_PROVIDER_BASE_URL, "ACTIVE");
        modelA = insertModel(providerId, "m04-routing-a", "路由模型 A", "ACTIVE");
        modelB = insertModel(providerId, "m04-routing-b", "路由模型 B", "ACTIVE");
        modelC = insertModel(providerId, "m04-routing-c", "路由模型 C", "ACTIVE");
        blockedModel = insertModel(blockedProviderId, "m04-routing-blocked", "不可路由模型", "ACTIVE");
    }

    /** 清理本测试目录与路由条目，保证不留下外键引用。 */
    @AfterEach
    void cleanupAfterTest() {
        cleanup();
    }

    /**
     * 保存/停用/启用/冲突/非法输入与生效读模型的完整旅程。
     *
     * <p>一个 ID 对应一个用例（ADR-0021 §11.1）；断言按阶段分组，失败时消息指到具体阶段。
     */
    @Test
    @DisplayName("IT-MODEL-101 路由保存、乐观锁、停用保留与下一次请求读新链")
    void itModel101() {
        LoginSession admin = loginPlatformAdmin();
        UUID adminId = UUID.fromString(admin.body().path("user").path("id").asText());

        JsonNode initial = getRouting(admin);
        assertThat(initial.path("items")).as("GET 必须返回两条链").hasSize(2);
        assertThat(purposesOf(initial)).containsExactly("CHAT", "REPORT");
        int chatVersion = chain(initial, "CHAT").path("version").asInt();
        int reportVersion = chain(initial, "REPORT").path("version").asInt();

        ApiTestClient.ApiResponse chatSaved = putChain(admin, "CHAT", chatVersion, List.of(modelA, modelB));
        assertThat(chatSaved.status()).as(chatSaved.body()).isEqualTo(200);
        JsonNode chatBody = client.json(chatSaved);
        assertThat(chatBody.path("purpose").asText()).isEqualTo("CHAT");
        assertThat(chatBody.path("version").asInt()).isEqualTo(chatVersion + 1);
        assertThat(chatBody.path("updatedBy").asText()).isEqualTo(adminId.toString());
        assertThat(chatBody.path("updatedAt").asText()).endsWith("+08:00");
        assertThat(chatBody.path("entries")).hasSize(2);
        assertEntry(chatBody.path("entries").get(0), 1, modelA, "ACTIVE");
        assertEntry(chatBody.path("entries").get(1), 2, modelB, "ACTIVE");

        ApiTestClient.ApiResponse reportSaved = putChain(admin, "REPORT", reportVersion, List.of(modelC));
        assertThat(reportSaved.status()).as(reportSaved.body()).isEqualTo(200);
        assertThat(client.json(reportSaved)
                        .path("entries")
                        .get(0)
                        .path("modelId")
                        .asText())
                .isEqualTo(modelC.toString());

        // 保存成功后重新 GET：读模型不缓存，等价于下一次请求立即读取新链。
        JsonNode afterSave = getRouting(admin);
        JsonNode savedChat = chain(afterSave, "CHAT");
        assertThat(savedChat.path("version").asInt()).isEqualTo(chatVersion + 1);
        assertThat(modelIdsOf(savedChat)).containsExactly(modelA.toString(), modelB.toString());
        assertThat(modelIdsOf(chain(afterSave, "REPORT"))).containsExactly(modelC.toString());

        // 停用模型 B：链与版本不变，B 保留在 position=2 且状态为 DISABLED。
        ApiTestClient.ApiResponse disabled = client.patch(
                "/api/v1/admin/models/" + modelB,
                client.jsonBody("status", "DISABLED"),
                admin.sessionCookie(),
                admin.csrfToken());
        assertThat(disabled.status()).as(disabled.body()).isEqualTo(200);
        JsonNode afterDisable = getRouting(admin);
        JsonNode disabledChat = chain(afterDisable, "CHAT");
        assertThat(disabledChat.path("version").asInt()).as("停用模型不改写链版本").isEqualTo(chatVersion + 1);
        assertThat(modelIdsOf(disabledChat)).containsExactly(modelA.toString(), modelB.toString());
        assertThat(disabledChat.path("entries").get(1).path("status").asText()).isEqualTo("DISABLED");

        // 停用状态下仍允许整链保存（保留位置），版本推进但状态仍为 DISABLED。
        ApiTestClient.ApiResponse savedWhileDisabled =
                putChain(admin, "CHAT", chatVersion + 1, List.of(modelA, modelB));
        assertThat(savedWhileDisabled.status()).as(savedWhileDisabled.body()).isEqualTo(200);
        JsonNode disabledSaved = client.json(savedWhileDisabled);
        assertThat(disabledSaved.path("version").asInt()).isEqualTo(chatVersion + 2);
        assertThat(disabledSaved.path("entries").get(1).path("status").asText()).isEqualTo("DISABLED");
        int currentChatVersion = chatVersion + 2;

        // 重新启用：下一次 GET 立即看到 ACTIVE，链顺序不变。
        ApiTestClient.ApiResponse enabled = client.patch(
                "/api/v1/admin/models/" + modelB,
                client.jsonBody("status", "ACTIVE"),
                admin.sessionCookie(),
                admin.csrfToken());
        assertThat(enabled.status()).as(enabled.body()).isEqualTo(200);
        JsonNode afterEnable = getRouting(admin);
        assertThat(chain(afterEnable, "CHAT").path("version").asInt()).isEqualTo(currentChatVersion);
        assertThat(chain(afterEnable, "CHAT")
                        .path("entries")
                        .get(1)
                        .path("status")
                        .asText())
                .isEqualTo("ACTIVE");
        assertThat(modelIdsOf(chain(afterEnable, "CHAT"))).containsExactly(modelA.toString(), modelB.toString());

        // 旧版本保存：409 RICCON-MODEL-409，链保持不变。
        ApiTestClient.ApiResponse conflict = putChain(admin, "CHAT", chatVersion, List.of(modelA));
        assertThat(conflict.status()).as(conflict.body()).isEqualTo(409);
        JsonNode conflictProblem = client.json(conflict);
        assertThat(conflictProblem.path("code").asText()).isEqualTo("RICCON-MODEL-409");
        assertThat(conflictProblem.path("field").asText()).isEqualTo("version");
        assertThat(conflictProblem.path("reason").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(conflictProblem.path("detail").asText()).contains("current version is " + currentChatVersion);
        assertThat(modelIdsOf(chain(getRouting(admin), "CHAT"))).containsExactly(modelA.toString(), modelB.toString());

        // 空链：400 CHAIN_EMPTY，不是“清空链”的成功路径。
        assertInvalidField(putChain(admin, "CHAT", currentChatVersion + 1, List.of()), "modelIds", "CHAIN_EMPTY");

        // 未知模型：字段级 400，而不是 404。
        assertInvalidField(
                putChain(admin, "CHAT", currentChatVersion + 1, List.of(UuidV7.generate())),
                "modelIds",
                "UNKNOWN_MODEL");

        // 端点不在部署允许列表：复用 B2 policy，400 且不回显地址。
        ApiTestClient.ApiResponse blocked = putChain(admin, "CHAT", currentChatVersion + 1, List.of(blockedModel));
        assertInvalidField(blocked, "modelIds", "ENDPOINT_NOT_ALLOWED");
        assertThat(blocked.body()).doesNotContain(BLOCKED_PROVIDER_BASE_URL);

        // 重复模型与超长链：400，不写入任何条目。
        assertInvalidField(
                putChain(admin, "CHAT", currentChatVersion + 1, List.of(modelA, modelA)),
                "modelIds",
                "DUPLICATE_MODEL");
        List<UUID> tooMany =
                IntStream.range(0, 21).mapToObj(index -> UuidV7.generate()).toList();
        assertInvalidField(putChain(admin, "CHAT", currentChatVersion + 1, tooMany), "modelIds", "CHAIN_TOO_LONG");

        // 缺少 version：400 INVALID_VERSION，而不是反序列化错误。
        assertInvalidField(
                client.put(
                        "/api/v1/admin/model-routing/CHAT",
                        client.jsonBody("modelIds", List.of(modelA.toString())),
                        admin.sessionCookie(),
                        admin.csrfToken()),
                "version",
                "INVALID_VERSION");

        // 非法 purpose：400 INVALID_PURPOSE，不创建新链。
        ApiTestClient.ApiResponse invalidPurpose = client.put(
                "/api/v1/admin/model-routing/SUMMARY",
                client.jsonBody("version", currentChatVersion + 1, "modelIds", List.of(modelA.toString())),
                admin.sessionCookie(),
                admin.csrfToken());
        assertInvalidField(invalidPurpose, "purpose", "INVALID_PURPOSE");

        // 所有失败路径都没有改写链。
        JsonNode finalChains = getRouting(admin);
        assertThat(modelIdsOf(chain(finalChains, "CHAT"))).containsExactly(modelA.toString(), modelB.toString());
        assertThat(chain(finalChains, "CHAT").path("version").asInt()).isEqualTo(currentChatVersion);
    }

    /** 读取两条路由链。 */
    private JsonNode getRouting(LoginSession admin) {
        ApiTestClient.ApiResponse response = client.get("/api/v1/admin/model-routing", admin.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /** 整链保存请求。 */
    private ApiTestClient.ApiResponse putChain(LoginSession admin, String purpose, int version, List<UUID> modelIds) {
        return client.put(
                "/api/v1/admin/model-routing/" + purpose,
                client.jsonBody(
                        "version",
                        version,
                        "modelIds",
                        modelIds.stream().map(UUID::toString).toList()),
                admin.sessionCookie(),
                admin.csrfToken());
    }

    /** 断言字段级 400。 */
    private void assertInvalidField(ApiTestClient.ApiResponse response, String field, String reason) {
        assertThat(response.status()).as(response.body()).isEqualTo(400);
        JsonNode problem = client.json(response);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-MODEL-400");
        assertThat(problem.path("field").asText()).isEqualTo(field);
        assertThat(problem.path("reason").asText()).isEqualTo(reason);
    }

    /** 断言单条链目。 */
    private static void assertEntry(JsonNode entry, int position, UUID modelId, String status) {
        assertThat(entry.path("position").asInt()).isEqualTo(position);
        assertThat(entry.path("modelId").asText()).isEqualTo(modelId.toString());
        assertThat(entry.path("status").asText()).isEqualTo(status);
        assertThat(entry.path("environmentLabel").asText()).isEqualTo("PRODUCTION");
        assertThat(entry.path("displayName").asText()).isNotBlank();
    }

    /** 按 purpose 找链。 */
    private static JsonNode chain(JsonNode list, String purpose) {
        for (JsonNode item : list.path("items")) {
            if (purpose.equals(item.path("purpose").asText())) {
                return item;
            }
        }
        throw new AssertionError("missing routing chain: " + purpose);
    }

    /** 链的 purpose 顺序。 */
    private static List<String> purposesOf(JsonNode list) {
        List<String> purposes = new ArrayList<>();
        for (JsonNode item : list.path("items")) {
            purposes.add(item.path("purpose").asText());
        }
        return purposes;
    }

    /** 链的模型 ID 顺序。 */
    private static List<String> modelIdsOf(JsonNode chain) {
        List<String> ids = new ArrayList<>();
        for (JsonNode entry : chain.path("entries")) {
            ids.add(entry.path("modelId").asText());
        }
        return ids;
    }

    /** 插入供应商。 */
    private void insertProvider(UUID id, String code, String baseUrl, String status) {
        jdbcClient
                .sql("""
                        INSERT INTO model_provider (id, code, name, protocol, base_url, status)
                        VALUES (:id, :code, :name, 'OPENAI_COMPATIBLE', :baseUrl, :status)
                        """)
                .param("id", BinaryUuids.toBytes(id))
                .param("code", code)
                .param("name", "M04 " + code)
                .param("baseUrl", baseUrl)
                .param("status", status)
                .update();
    }

    /** 插入模型并返回 ID。 */
    private UUID insertModel(UUID provider, String modelKey, String displayName, String status) {
        UUID modelId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO model (
                            id, provider_id, model_key, display_name, context_window, max_output_tokens,
                            capabilities, status
                        ) VALUES (
                            :id, :providerId, :modelKey, :displayName, 32768, 2048,
                            JSON_OBJECT('chat', TRUE, 'stream', TRUE), :status
                        )
                        """)
                .param("id", BinaryUuids.toBytes(modelId))
                .param("providerId", BinaryUuids.toBytes(provider))
                .param("modelKey", modelKey)
                .param("displayName", displayName)
                .param("status", status)
                .update();
        return modelId;
    }

    /** 清空路由条目并删除本测试目录；路由链头由迁移保证，不删除。 */
    private void cleanup() {
        jdbcClient.sql("DELETE FROM model_routing_entry").update();
        for (String code : List.of(PROVIDER_CODE, BLOCKED_PROVIDER_CODE)) {
            Optional<UUID> existing = jdbcClient
                    .sql("SELECT id FROM model_provider WHERE code = :code")
                    .param("code", code)
                    .query((rs, rowNum) -> BinaryUuids.fromBytes(rs.getBytes("id")))
                    .optional();
            existing.ifPresent(id -> {
                byte[] bytes = BinaryUuids.toBytes(id);
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
            });
        }
    }
}
