package com.riccon.api.admin.model.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.repository.AuditRepository;
import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.domain.audit.AuditActions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 模型路由的权限边界、审计数组与审计失败回滚（IT-MODEL-102）。
 *
 * <p>路由是全局平台资源，没有跨租户对象路径：租户管理员与员工调用读/写接口统一 403，且响应不包含链内容；
 * 不存在的子路径返回 404，证明没有额外契约端点。平台管理员保存时写 {@code model.routing.updated}，
 * details 中 {@code modelIds} 是保序 UUID 数组；审计写入失败必须回滚整链保存（ADR-0021 §4.3/§4.4/§7.4）。
 */
@TestPropertySource(
        properties = {
            "riccon.model.deployment-mode=DEMO",
            "riccon.model.endpoints=[{\"code\":\"m04-routing-audit\",\"baseUrl\":\"https://10.20.30.60/v1\",\"environmentLabel\":\"PRODUCTION\"}]",
            "riccon.model.allow-legacy-endpoints=false",
            "riccon.model.allow-http=false"
        })
@Tag("security")
class ModelRoutingAuditSecurityTest extends ApiIntegrationTestSupport {

    /** 允许列表内的供应商代码。 */
    private static final String PROVIDER_CODE = "m04-routing-audit";

    /** 允许列表内的供应商地址。 */
    private static final String PROVIDER_BASE_URL = "https://10.20.30.60/v1";

    /** JSON 解析器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 审计仓储 spy；启动、登录与租户夹具走真实实现，只在测试指定阶段让路由审计失败。 */
    @MockitoSpyBean
    private AuditRepository auditRepository;

    /** 是否让 {@code model.routing.updated} 审计失败。 */
    private final AtomicBoolean failRoutingAudit = new AtomicBoolean(false);

    /** 模型 A。 */
    private UUID modelA;

    /** 模型 B。 */
    private UUID modelB;

    /** 每个测试重建目录、清空路由条目并安装条件审计失败桩。 */
    @BeforeEach
    void seedCatalogAndStubAudit() {
        cleanup();
        UUID providerId = UuidV7.generate();
        insertProvider(providerId);
        modelA = insertModel(providerId, "m04-routing-audit-a", "审计模型 A");
        modelB = insertModel(providerId, "m04-routing-audit-b", "审计模型 B");
        // 只拦截路由审计；其他动作（登录、租户/账号、模型启停）仍走真实仓储，避免夹具被阻断。
        doAnswer(invocation -> {
                    AuditEvent event = invocation.getArgument(0);
                    if (failRoutingAudit.get() && AuditActions.MODEL_ROUTING_UPDATED.equals(event.action())) {
                        throw new DataAccessResourceFailureException("audit storage unavailable");
                    }
                    return invocation.callRealMethod();
                })
                .when(auditRepository)
                .append(any(AuditEvent.class));
    }

    /** 清理目录与路由条目。 */
    @AfterEach
    void cleanupAfterTest() {
        cleanup();
    }

    /**
     * 权限 403、审计数组与审计失败回滚的完整用例。
     */
    @Test
    @DisplayName("IT-MODEL-102 越权 403、路由审计数组与审计失败回滚")
    void itModel102() {
        LoginSession platformAdmin = loginPlatformAdmin();
        UUID platformAdminId =
                UUID.fromString(platformAdmin.body().path("user").path("id").asText());
        String tenantCode = uniqueTenantCode("m04route");
        createTenant(platformAdmin, tenantCode);
        LoginSession tenantAdmin = createAndLoginTenantAdmin(platformAdmin, tenantCode);
        LoginSession member = createAndLoginMember(platformAdmin, tenantCode);
        int currentVersion =
                chain(getRouting(platformAdmin), "CHAT").path("version").asInt();

        // 租户管理员与员工：读/写均 403，响应不含链内容。
        assertForbidden(client.get("/api/v1/admin/model-routing", tenantAdmin.sessionCookie(), Map.of()));
        assertForbidden(putChain(tenantAdmin, currentVersion, List.of(modelA)));
        assertForbidden(client.get("/api/v1/admin/model-routing", member.sessionCookie(), Map.of()));
        assertForbidden(putChain(member, currentVersion, List.of(modelA)));

        // 不存在的子路径统一 404，证明没有为 B3 新增额外契约端点。
        ApiTestClient.ApiResponse unknownPath =
                client.get("/api/v1/admin/model-routing/CHAT/versions", platformAdmin.sessionCookie(), Map.of());
        assertThat(unknownPath.status()).isEqualTo(404);
        assertThat(client.json(unknownPath).path("code").asText()).isEqualTo("RICCON-REQUEST-404");

        int auditCountBefore = auditRows(AuditActions.MODEL_ROUTING_UPDATED).size();
        ApiTestClient.ApiResponse saved = putChain(platformAdmin, currentVersion, List.of(modelA, modelB));
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        int newVersion = client.json(saved).path("version").asInt();
        assertThat(newVersion).isEqualTo(currentVersion + 1);

        List<AuditRow> audits = auditRows(AuditActions.MODEL_ROUTING_UPDATED);
        assertThat(audits).hasSize(auditCountBefore + 1);
        AuditRow audit = audits.get(audits.size() - 1);
        assertThat(audit.tenantId()).isEqualTo("platform");
        assertThat(audit.actorType()).isEqualTo("USER");
        assertThat(audit.actorId()).isEqualTo(platformAdminId.toString());
        assertThat(audit.targetType()).isEqualTo("MODEL_ROUTING");
        assertThat(audit.targetId()).isEqualTo("CHAT");
        assertThat(audit.outcome()).isEqualTo("SUCCESS");
        JsonNode details = MAPPER.readTree(audit.details());
        assertThat(details.path("purpose").asText()).isEqualTo("CHAT");
        assertThat(details.path("version").asInt()).isEqualTo(newVersion);
        assertThat(details.path("previousVersion").asInt()).isEqualTo(currentVersion);
        assertThat(details.path("result").asText()).isEqualTo("SUCCESS");
        assertThat(details.path("modelIds").isArray()).as("modelIds 必须是数组").isTrue();
        assertThat(details.path("modelIds")).hasSize(2);
        assertThat(details.path("modelIds").get(0).asText()).isEqualTo(modelA.toString());
        assertThat(details.path("modelIds").get(1).asText()).isEqualTo(modelB.toString());
        // details 不得出现端点地址、密钥或 provider 内部字段。
        assertThat(audit.details())
                .doesNotContain(PROVIDER_BASE_URL)
                .doesNotContain("baseUrl")
                .doesNotContain("apiKey")
                .doesNotContain("Authorization");

        // 审计失败：整链保存回滚，版本与条目不改变，也不返回“已保存”。
        failRoutingAudit.set(true);
        ApiTestClient.ApiResponse failed = putChain(platformAdmin, newVersion, List.of(modelB, modelA));
        assertThat(failed.status()).as(failed.body()).isEqualTo(500);
        assertThat(client.json(failed).path("code").asText()).isEqualTo("RICCON-COMMON-500");

        JsonNode afterFailure = chain(getRouting(platformAdmin), "CHAT");
        assertThat(afterFailure.path("version").asInt()).as("回滚后版本不变").isEqualTo(newVersion);
        assertThat(modelIdsOf(afterFailure)).containsExactly(modelA.toString(), modelB.toString());
        assertThat(auditRows(AuditActions.MODEL_ROUTING_UPDATED)).hasSize(auditCountBefore + 1);
    }

    /** 断言越权 403 且不泄露链内容。 */
    private void assertForbidden(ApiTestClient.ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(403);
        JsonNode problem = client.json(response);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-AUTH-403");
        assertThat(problem.has("entries")).isFalse();
        assertThat(response.body()).doesNotContain(modelA.toString());
    }

    /** 读取两条路由链。 */
    private JsonNode getRouting(LoginSession session) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/admin/model-routing", session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /** 整链保存请求。 */
    private ApiTestClient.ApiResponse putChain(LoginSession session, int version, List<UUID> modelIds) {
        return client.put(
                "/api/v1/admin/model-routing/CHAT",
                client.jsonBody(
                        "version",
                        version,
                        "modelIds",
                        modelIds.stream().map(UUID::toString).toList()),
                session.sessionCookie(),
                session.csrfToken());
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

    /** 链的模型 ID 顺序。 */
    private static List<String> modelIdsOf(JsonNode chain) {
        return java.util.stream.StreamSupport.stream(chain.path("entries").spliterator(), false)
                .map(entry -> entry.path("modelId").asText())
                .toList();
    }

    /** 插入供应商。 */
    private void insertProvider(UUID id) {
        jdbcClient
                .sql("""
                        INSERT INTO model_provider (id, code, name, protocol, base_url, status)
                        VALUES (:id, :code, 'M04 Routing Audit Provider', 'OPENAI_COMPATIBLE', :baseUrl, 'ACTIVE')
                        """)
                .param("id", BinaryUuids.toBytes(id))
                .param("code", PROVIDER_CODE)
                .param("baseUrl", PROVIDER_BASE_URL)
                .update();
    }

    /** 插入模型并返回 ID。 */
    private UUID insertModel(UUID provider, String modelKey, String displayName) {
        UUID modelId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO model (
                            id, provider_id, model_key, display_name, context_window, max_output_tokens,
                            capabilities, status
                        ) VALUES (
                            :id, :providerId, :modelKey, :displayName, 32768, 2048,
                            JSON_OBJECT('chat', TRUE, 'stream', TRUE), 'ACTIVE'
                        )
                        """)
                .param("id", BinaryUuids.toBytes(modelId))
                .param("providerId", BinaryUuids.toBytes(provider))
                .param("modelKey", modelKey)
                .param("displayName", displayName)
                .update();
        return modelId;
    }

    /** 清空路由条目并删除本测试目录。 */
    private void cleanup() {
        jdbcClient.sql("DELETE FROM model_routing_entry").update();
        Optional<UUID> existing = jdbcClient
                .sql("SELECT id FROM model_provider WHERE code = :code")
                .param("code", PROVIDER_CODE)
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
