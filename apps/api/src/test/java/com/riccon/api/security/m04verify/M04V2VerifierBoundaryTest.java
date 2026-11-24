package com.riccon.api.security.m04verify;

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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
 * M04-V2 独立验收：AC-02 整条写权限/跨租户/审计 fail-closed 与 AC-18 开关关闭/历史保留。
 *
 * <p>与 Builder 用例独立：只依赖 HTTP 契约、数据库结构与审计持久化，不复用 Builder 的断言或夹具。
 * 本类不判 AC-17（性能由合同 `commands.perf` 的 k6 证据判定），也不替代 V1 的 15 条 AC 证据；
 * 覆盖点：
 *
 * <ul>
 *   <li>AC-02 / SEC-M04-003b / TEN-032 / TEN-033：租户管理员与员工写租户/用户配额一律 403，
 *       响应不含配额内容；平台聚合无用户明细；跨租户用户 404；平台 `groupBy=USER` 400；
 *   <li>AC-02 / SEC-M04-003a / TEN-031 独立抽检：租户管理员与员工对路由/模型/端点/key/开关的
 *       读与写一律 403，匿名 401，响应不回显链内容；
 *   <li>AC-02 / IT-MODEL-102：平台保存路由写操作者/动作/变更摘要/结果；审计写入失败时保存 500
 *       且链版本与顺序不变；
 *   <li>AC-18：关闭路由/降级/配额执行后下一次读取生效并写三条审计，schema 不回滚（当前 V14）
 *       数据库），历史调用行与成本快照不被删除或改写。
 * </ul>
 */
@TestPropertySource(
        properties = {
            "riccon.model.deployment-mode=DEMO",
            "riccon.model.endpoints=[{\"code\":\"m04-v2-prod\",\"baseUrl\":\"https://10.30.40.10/v1\",\"environmentLabel\":\"PRODUCTION\"}]",
            "riccon.model.allow-legacy-endpoints=false",
            "riccon.model.allow-http=false"
        })
@Tag("security")
class M04V2VerifierBoundaryTest extends ApiIntegrationTestSupport {

    /** 允许列表内的端点代码。 */
    private static final String PROVIDER_CODE = "m04-v2-prod";

    /** 允许列表内的端点地址。 */
    private static final String PROVIDER_URL = "https://10.30.40.10/v1";

    /** JSON 解析器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 审计仓储 spy；夹具与登录走真实实现，只在指定阶段让路由审计失败。 */
    @MockitoSpyBean
    private AuditRepository auditRepository;

    /** 是否让 {@code model.routing.updated} 审计失败。 */
    private final AtomicBoolean failRoutingAudit = new AtomicBoolean(false);

    /** 目录 provider。 */
    private UUID providerId;

    /** 模型 A（有价格）。 */
    private UUID modelA;

    /** 模型 B（无价格）。 */
    private UUID modelB;

    /** 每个用例重建目录、恢复开关种子并安装条件审计失败桩。 */
    @BeforeEach
    void setUp() {
        cleanup();
        providerId = UuidV7.generate();
        insertProvider(providerId);
        modelA = insertModel(providerId, "m04-v2-model-a", "V2 模型 A", "1.500000", "3.000000");
        modelB = insertModel(providerId, "m04-v2-model-b", "V2 模型 B", null, null);
        writeSeedSwitches();
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

    /** 用例后清理目录与遗留数据。 */
    @AfterEach
    void tearDown() {
        cleanup();
        writeSeedSwitches();
    }

    /**
     * AC-02：配额写接口只对平台管理员开放；租户管理员只读本租户，员工无入口；跨租户 404；
     * 平台聚合不含用户明细，`groupBy=USER` 400。
     */
    @Test
    @DisplayName("AC-02 / SEC-M04-003b 配额读写边界与跨租户 404")
    void ac02_quotaWriteBoundary() {
        LoginSession platform = loginPlatformAdmin();
        String tenantA = uniqueTenantCode("m04v2qa");
        String tenantB = uniqueTenantCode("m04v2qb");
        createTenant(platform, tenantA);
        createTenant(platform, tenantB);
        LoginSession tenantAdmin = createAndLoginTenantAdmin(platform, tenantA);
        LoginSession member = createAndLoginMember(platform, tenantA);
        LoginSession foreignAdmin = createAndLoginTenantAdmin(platform, tenantB);
        String foreignMemberId = foreignAdmin.body().path("user").path("id").asText();
        String ownAdminId = tenantAdmin.body().path("user").path("id").asText();

        // 租户管理员只读本租户配额；员工无入口。
        ApiTestClient.ApiResponse ownQuota = client.get("/api/v1/quotas", tenantAdmin.sessionCookie(), Map.of());
        assertThat(ownQuota.status()).as(ownQuota.body()).isEqualTo(200);
        JsonNode quota = client.json(ownQuota);
        assertThat(quota.path("tenantCode").asText()).isEqualTo(tenantA);
        assertThat(quota.path("windows")).hasSize(2);
        assertThat(client.get("/api/v1/quotas", member.sessionCookie(), Map.of())
                        .status())
                .isEqualTo(403);
        assertThat(client.get("/api/v1/quotas/users?page=1&size=100", member.sessionCookie(), Map.of())
                        .status())
                .isEqualTo(403);

        // 租户管理员与员工写租户/用户配额：403，且响应不含配额内容。
        String tenantQuotaPath = "/api/v1/admin/tenants/" + tenantA + "/quotas";
        String userQuotaPath = "/api/v1/admin/tenants/" + tenantA + "/users/" + ownAdminId + "/quotas";
        for (LoginSession session : List.of(tenantAdmin, member)) {
            assertForbiddenNoQuotaBody(putQuota(session, tenantQuotaPath, 1, 10));
            assertForbiddenNoQuotaBody(putQuota(session, userQuotaPath, 1, 10));
        }
        assertThat(client.get("/api/v1/admin/quotas?page=1&size=100", tenantAdmin.sessionCookie(), Map.of())
                        .status())
                .isEqualTo(403);

        // 平台跨租户写用户配额：统一 404，不泄露对象是否存在。
        ApiTestClient.ApiResponse crossTenant =
                putQuota(platform, "/api/v1/admin/tenants/" + tenantA + "/users/" + foreignMemberId + "/quotas", 1, 10);
        assertThat(crossTenant.status()).as(crossTenant.body()).isEqualTo(404);
        assertThat(client.json(crossTenant).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        // 平台聚合只返回租户维度，不含用户字段；平台按 USER 聚合 400。
        JsonNode platformQuotas =
                client.json(client.get("/api/v1/admin/quotas?page=1&size=100", platform.sessionCookie(), Map.of()));
        assertThat(platformQuotas.path("items").isArray()).isTrue();
        for (JsonNode item : platformQuotas.path("items")) {
            assertThat(item.has("userId")).isFalse();
            assertThat(item.has("username")).isFalse();
            assertThat(item.has("displayName")).isFalse();
        }
        ApiTestClient.ApiResponse userGroup = client.get(
                "/api/v1/admin/costs?from=2026-11-01&to=2026-11-30&groupBy=USER", platform.sessionCookie(), Map.of());
        assertThat(userGroup.status()).as(userGroup.body()).isEqualTo(400);

        // 合法写：平台能整体替换并写 quota.limit.changed 审计（两条窗口）。
        int auditsBefore = auditRows(AuditActions.QUOTA_LIMIT_CHANGED).size();
        ApiTestClient.ApiResponse accepted = putQuota(platform, tenantQuotaPath, 7, 70);
        assertThat(accepted.status()).as(accepted.body()).isEqualTo(200);
        assertThat(auditRows(AuditActions.QUOTA_LIMIT_CHANGED)).hasSize(auditsBefore + 2);
    }

    /**
     * AC-02：路由/模型/端点/key/开关写接口对租户管理员与员工一律 403，匿名 401；响应不回显配置内容。
     */
    @Test
    @DisplayName("AC-02 / SEC-M04-003a 独立抽检：模型与开关读/写越权")
    void ac02_modelConfigWriteBoundary() {
        LoginSession platform = loginPlatformAdmin();
        String tenantCode = uniqueTenantCode("m04v2mw");
        createTenant(platform, tenantCode);
        LoginSession tenantAdmin = createAndLoginTenantAdmin(platform, tenantCode);
        LoginSession member = createAndLoginMember(platform, tenantCode);
        int version = chain(getRouting(platform), "CHAT").path("version").asInt();
        String routingBody = client.jsonBody("version", version, "modelIds", List.of(modelA.toString()));

        for (LoginSession session : List.of(tenantAdmin, member)) {
            assertForbiddenNoConfig(client.get("/api/v1/admin/model-routing", session.sessionCookie(), Map.of()));
            assertForbiddenNoConfig(client.put(
                    "/api/v1/admin/model-routing/CHAT", routingBody, session.sessionCookie(), session.csrfToken()));
            assertForbiddenNoConfig(
                    client.get("/api/v1/admin/model-providers?page=1&size=10", session.sessionCookie(), Map.of()));
            assertForbiddenNoConfig(
                    client.get("/api/v1/admin/models?page=1&size=10", session.sessionCookie(), Map.of()));
            assertForbiddenNoConfig(client.patch(
                    "/api/v1/admin/models/" + modelA,
                    client.jsonBody("status", "DISABLED"),
                    session.sessionCookie(),
                    session.csrfToken()));
            assertForbiddenNoConfig(client.post(
                    "/api/v1/admin/model-providers/" + providerId + "/credentials",
                    client.jsonBody("label", "denied", "apiKey", "sk-denied-v2", "priority", 10),
                    session.sessionCookie(),
                    session.csrfToken(),
                    Map.of()));
            assertForbiddenNoConfig(
                    client.get("/api/v1/admin/model-gateway/switches", session.sessionCookie(), Map.of()));
            assertForbiddenNoConfig(putSwitches(session, 1, "BASELINE", false, true));
        }

        // 匿名先认证后授权：401，且不泄露任何配置。
        ApiTestClient.ApiResponse anonymous = client.get("/api/v1/admin/model-routing", null, Map.of());
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.body()).doesNotContain("entries").doesNotContain(PROVIDER_URL);
        assertThat(client.put(
                                "/api/v1/admin/model-gateway/switches",
                                client.jsonBody(
                                        "version",
                                        1,
                                        "routingMode",
                                        "BASELINE",
                                        "failoverEnabled",
                                        false,
                                        "quotaEnforcementEnabled",
                                        true),
                                null,
                                null)
                        .status())
                .isIn(401, 403);

        // 合法平台保存仍可用（证明只读边界来自权限，不是端点 404）。
        assertThat(putChain(platform, "CHAT", version, List.of(modelA)).status())
                .isEqualTo(200);
    }

    /**
     * AC-02 / IT-MODEL-102：路由审计包含操作者、动作、变更摘要、时间与结果；审计写入失败时保存失败。
     */
    @Test
    @DisplayName("AC-02 路由审计数组与审计 fail-closed")
    void ac02_routingAuditFailClosed() {
        LoginSession platform = loginPlatformAdmin();
        UUID adminId = UUID.fromString(platform.body().path("user").path("id").asText());
        int version = chain(getRouting(platform), "CHAT").path("version").asInt();
        int auditsBefore = auditRows(AuditActions.MODEL_ROUTING_UPDATED).size();

        ApiTestClient.ApiResponse saved = putChain(platform, "CHAT", version, List.of(modelA, modelB));
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        int newVersion = client.json(saved).path("version").asInt();
        assertThat(newVersion).isEqualTo(version + 1);

        List<AuditRow> audits = auditRows(AuditActions.MODEL_ROUTING_UPDATED);
        assertThat(audits).hasSize(auditsBefore + 1);
        AuditRow audit = audits.get(audits.size() - 1);
        assertThat(audit.tenantId()).isEqualTo("platform");
        assertThat(audit.actorType()).isEqualTo("USER");
        assertThat(audit.actorId()).isEqualTo(adminId.toString());
        assertThat(audit.targetType()).isEqualTo("MODEL_ROUTING");
        assertThat(audit.targetId()).isEqualTo("CHAT");
        assertThat(audit.outcome()).isEqualTo("SUCCESS");
        assertThat(audit.traceId()).isNotBlank();
        JsonNode details = MAPPER.readTree(audit.details());
        assertThat(details.path("purpose").asText()).isEqualTo("CHAT");
        assertThat(details.path("version").asInt()).isEqualTo(newVersion);
        assertThat(details.path("modelIds")).hasSize(2);
        assertThat(details.path("modelIds").get(0).asText()).isEqualTo(modelA.toString());
        assertThat(details.path("modelIds").get(1).asText()).isEqualTo(modelB.toString());
        assertThat(audit.details()).doesNotContain(PROVIDER_URL).doesNotContain("apiKey");

        // 审计失败：整链保存回滚，版本/顺序不变，不返回“已保存”。
        failRoutingAudit.set(true);
        try {
            ApiTestClient.ApiResponse failed = putChain(platform, "CHAT", newVersion, List.of(modelB, modelA));
            assertThat(failed.status()).as(failed.body()).isEqualTo(500);
            assertThat(client.json(failed).path("code").asText()).isEqualTo("RICCON-COMMON-500");
        } finally {
            failRoutingAudit.set(false);
        }
        JsonNode afterFailure = chain(getRouting(platform), "CHAT");
        assertThat(afterFailure.path("version").asInt()).isEqualTo(newVersion);
        assertThat(modelIdsOf(afterFailure)).containsExactly(modelA.toString(), modelB.toString());
        assertThat(auditRows(AuditActions.MODEL_ROUTING_UPDATED)).hasSize(auditsBefore + 1);
    }

    /**
     * AC-18：关闭路由/降级/配额后下一次读取生效并逐项写审计；当前 schema 不回滚，历史调用与成本
     * 快照保留。
     */
    @Test
    @DisplayName("AC-18 开关关闭生效、审计与历史成本快照保留")
    void ac18_switchClosureKeepsHistory() {
        LoginSession platform = loginPlatformAdmin();
        String tenantCode = uniqueTenantCode("m04v2h");
        createTenant(platform, tenantCode);
        UUID invocationId = UuidV7.generate();
        insertHistoricalInvocation(tenantCode, invocationId);

        Map<String, Object> before = historicalSnapshot(tenantCode, invocationId);
        assertThat((String) before.get("cost_status")).isEqualTo("PRICED");
        assertThat(before.get("cost_amount").toString()).isEqualTo("0.004500");

        JsonNode seed = getSwitches(platform);
        assertThat(seed.path("routingMode").asText()).isEqualTo("ROUTED");
        assertThat(seed.path("version").asInt()).isEqualTo(1);
        int switchAuditsBefore =
                auditRows(AuditActions.MODEL_GATEWAY_SWITCH_CHANGED).size();

        // 先让三个开关都处在非默认值，再一次性关闭路由/降级/配额执行；
        // 审计只在值实际变化时写入（ADR-0021 §10.5），因此分两步共 4 条。
        ApiTestClient.ApiResponse enabled = putSwitches(platform, 1, "BASELINE", false, true);
        assertThat(enabled.status()).as(enabled.body()).isEqualTo(200);
        ApiTestClient.ApiResponse closed = putSwitches(platform, 2, "BASELINE", false, false);
        assertThat(closed.status()).as(closed.body()).isEqualTo(200);
        JsonNode body = client.json(closed);
        assertThat(body.path("routingMode").asText()).isEqualTo("BASELINE");
        assertThat(body.path("failoverEnabled").asBoolean()).isFalse();
        assertThat(body.path("quotaEnforcementEnabled").asBoolean()).isFalse();
        assertThat(body.path("version").asInt()).isEqualTo(3);

        // 下一次读取不缓存，立即返回关闭后的策略。
        JsonNode afterClose = getSwitches(platform);
        assertThat(afterClose.path("version").asInt()).isEqualTo(3);
        assertThat(afterClose.path("routingMode").asText()).isEqualTo("BASELINE");
        assertThat(afterClose.path("failoverEnabled").asBoolean()).isFalse();
        assertThat(afterClose.path("quotaEnforcementEnabled").asBoolean()).isFalse();

        // 实际变化的四个开关值各一条审计，来源 DATABASE，操作者为平台管理员；不含端点或密钥。
        List<AuditRow> switchAudits = auditRows(AuditActions.MODEL_GATEWAY_SWITCH_CHANGED);
        assertThat(switchAudits).hasSize(switchAuditsBefore + 4);
        assertThat(switchAudits.subList(switchAuditsBefore, switchAudits.size()))
                .allSatisfy(row -> {
                    assertThat(row.tenantId()).isEqualTo("platform");
                    assertThat(row.actorType()).isEqualTo("USER");
                    assertThat(row.outcome()).isEqualTo("SUCCESS");
                    JsonNode detail = MAPPER.readTree(row.details());
                    assertThat(detail.path("source").asText()).isEqualTo("DATABASE");
                    assertThat(detail.has("switch")).isTrue();
                    assertThat(detail.has("value")).isTrue();
                    assertThat(row.details()).doesNotContain(PROVIDER_URL).doesNotContain("apiKey");
                });

        // 关闭开关不需要回滚数据库：schema 仍为当前 15（V15 只新增 workflow 结构与权限/模板种子），历史调用行与成本快照可读且未被改写。
        assertThat(schemaVersion()).isEqualTo(15);
        Map<String, Object> after = historicalSnapshot(tenantCode, invocationId);
        assertThat(after).isEqualTo(before);
        // 旧应用（M04 前）按列名读取历史调用仍工作。
        assertThat(jdbcClient
                        .sql("""
                                SELECT status, prompt_tokens, completion_tokens
                                  FROM model_invocation
                                 WHERE tenant_id = :tenantId AND id = :id
                                """)
                        .param("tenantId", tenantCode)
                        .param("id", BinaryUuids.toBytes(invocationId))
                        .query((rs, rowNum) -> rs.getString("status"))
                        .single())
                .isEqualTo("SUCCEEDED");

        // 非法开关值被稳定拒绝，不写库。
        ApiTestClient.ApiResponse invalid = client.put(
                "/api/v1/admin/model-gateway/switches",
                client.jsonBody(
                        "version",
                        3,
                        "routingMode",
                        "ROUND_ROBIN",
                        "failoverEnabled",
                        true,
                        "quotaEnforcementEnabled",
                        true),
                platform.sessionCookie(),
                platform.csrfToken());
        assertThat(invalid.status()).as(invalid.body()).isEqualTo(400);
        assertThat(client.json(invalid).path("field").asText()).isEqualTo("routingMode");
        assertThat(getSwitches(platform).path("version").asInt()).isEqualTo(3);
    }

    /** 断言 403 且响应不含配额内容（只按 JSON 字段名判断，不误伤 instance 路径中的 `quotas`）。 */
    private void assertForbiddenNoQuotaBody(ApiTestClient.ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(403);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-AUTH-403");
        assertThat(response.body())
                .doesNotContain("\"windows\"")
                .doesNotContain("\"limit\"")
                .doesNotContain("\"used\"")
                .doesNotContain("daily")
                .doesNotContain("monthly");
    }

    /** 断言 403 且响应不含模型链/开关内容。 */
    private void assertForbiddenNoConfig(ApiTestClient.ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(403);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-AUTH-403");
        assertThat(response.body())
                .doesNotContain("entries")
                .doesNotContain("modelIds")
                .doesNotContain("routingMode")
                .doesNotContain("maskedHint")
                .doesNotContain(PROVIDER_URL);
    }

    /** 读取两条路由链。 */
    private JsonNode getRouting(LoginSession session) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/admin/model-routing", session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /** 读取开关。 */
    private JsonNode getSwitches(LoginSession session) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/admin/model-gateway/switches", session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /** 整链保存。 */
    private ApiTestClient.ApiResponse putChain(LoginSession session, String purpose, int version, List<UUID> modelIds) {
        return client.put(
                "/api/v1/admin/model-routing/" + purpose,
                client.jsonBody(
                        "version",
                        version,
                        "modelIds",
                        modelIds.stream().map(UUID::toString).toList()),
                session.sessionCookie(),
                session.csrfToken());
    }

    /** 整组保存开关。 */
    private ApiTestClient.ApiResponse putSwitches(
            LoginSession session, int version, String routingMode, boolean failover, boolean quota) {
        return client.put(
                "/api/v1/admin/model-gateway/switches",
                client.jsonBody(
                        "version",
                        version,
                        "routingMode",
                        routingMode,
                        "failoverEnabled",
                        failover,
                        "quotaEnforcementEnabled",
                        quota),
                session.sessionCookie(),
                session.csrfToken());
    }

    /** 配额整组保存。 */
    private ApiTestClient.ApiResponse putQuota(LoginSession session, String path, Integer daily, Integer monthly) {
        return client.put(
                path,
                "{\"limits\":{\"daily\":" + daily + ",\"monthly\":" + monthly + "}}",
                session.sessionCookie(),
                session.csrfToken());
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
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode entry : chain.path("entries")) {
            ids.add(entry.path("modelId").asText());
        }
        return ids;
    }

    /** 读取 flyway 当前最大成功版本。 */
    private int schemaVersion() {
        return jdbcClient.sql("""
                        SELECT MAX(CAST(version AS UNSIGNED))
                          FROM flyway_schema_history
                         WHERE success = 1
                        """).query(Integer.class).single();
    }

    /** 插入一条带价格快照的历史调用；用于 AC-18 历史保留断言。 */
    private void insertHistoricalInvocation(String tenantCode, UUID invocationId) {
        jdbcClient
                .sql("""
                        INSERT INTO model_invocation (
                            tenant_id, id, model_id, model_key_snapshot, purpose, status,
                            prompt_tokens, completion_tokens, trace_id, started_at, finished_at,
                            provider_id, cost_day, input_price_per_1m, output_price_per_1m,
                            price_currency, pricing_version, cost_amount, cost_status, attempt_count
                        ) VALUES (
                            :tenantId, :id, :modelId, 'm04-v2-model-a', 'CHAT', 'SUCCEEDED',
                            1000, 500, 'trace-m04-v2-history', '2026-11-01 02:00:00', '2026-11-01 02:00:02',
                            :providerId, '2026-11-01', 1.500000, 3.000000,
                            'CNY', 'v1', 0.004500, 'PRICED', 1
                        )
                        """)
                .param("tenantId", tenantCode)
                .param("id", BinaryUuids.toBytes(invocationId))
                .param("modelId", BinaryUuids.toBytes(modelA))
                .param("providerId", BinaryUuids.toBytes(providerId))
                .update();
    }

    /** 读取历史调用的成本快照列。 */
    private Map<String, Object> historicalSnapshot(String tenantCode, UUID invocationId) {
        return jdbcClient
                .sql("""
                        SELECT status, cost_status, cost_amount, cost_day, input_price_per_1m,
                               output_price_per_1m, price_currency
                          FROM model_invocation
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantCode)
                .param("id", BinaryUuids.toBytes(invocationId))
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("status", rs.getString("status"));
                    row.put("cost_status", rs.getString("cost_status"));
                    row.put("cost_amount", rs.getBigDecimal("cost_amount"));
                    row.put("cost_day", rs.getDate("cost_day").toLocalDate());
                    row.put("input_price_per_1m", rs.getBigDecimal("input_price_per_1m"));
                    row.put("output_price_per_1m", rs.getBigDecimal("output_price_per_1m"));
                    row.put("price_currency", rs.getString("price_currency"));
                    return row;
                })
                .single();
    }

    /** 恢复 V11 开关种子。 */
    private void writeSeedSwitches() {
        jdbcClient
                .sql("""
                        UPDATE model_gateway_switch
                           SET switch_value = CASE switch_key
                                   WHEN 'routingMode' THEN 'ROUTED'
                                   WHEN 'failoverEnabled' THEN 'true'
                                   ELSE 'false'
                               END,
                               version = 1,
                               updated_by = NULL,
                               updated_at = :updatedAt
                        """)
                .param("updatedAt", LocalDateTime.of(2026, 11, 20, 0, 0))
                .update();
    }

    /** 插入允许列表内的 provider。 */
    private void insertProvider(UUID id) {
        jdbcClient
                .sql("""
                        INSERT INTO model_provider (id, code, name, protocol, base_url, status)
                        VALUES (:id, :code, 'M04 V2 端点', 'OPENAI_COMPATIBLE', :baseUrl, 'ACTIVE')
                        """)
                .param("id", BinaryUuids.toBytes(id))
                .param("code", PROVIDER_CODE)
                .param("baseUrl", PROVIDER_URL)
                .update();
    }

    /** 插入模型并返回 ID。 */
    private UUID insertModel(
            UUID provider, String modelKey, String displayName, String inputPrice, String outputPrice) {
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
                .param("providerId", BinaryUuids.toBytes(provider))
                .param("modelKey", modelKey)
                .param("displayName", displayName)
                .param("inputPrice", inputPrice)
                .param("outputPrice", outputPrice)
                .update();
        return modelId;
    }

    /** 清理目录、路由条目与历史调用。 */
    private void cleanup() {
        List<UUID> modelIds = java.util.stream.Stream.of(modelA, modelB)
                .filter(java.util.Objects::nonNull)
                .toList();
        for (UUID modelId : modelIds) {
            jdbcClient
                    .sql("DELETE FROM model_invocation_attempt WHERE model_id = :modelId")
                    .param("modelId", BinaryUuids.toBytes(modelId))
                    .update();
            jdbcClient
                    .sql("DELETE FROM model_invocation WHERE model_id = :modelId")
                    .param("modelId", BinaryUuids.toBytes(modelId))
                    .update();
            jdbcClient
                    .sql("DELETE FROM model_routing_entry WHERE model_id = :modelId")
                    .param("modelId", BinaryUuids.toBytes(modelId))
                    .update();
        }
        jdbcClient
                .sql("SELECT id FROM model_provider WHERE code = :code")
                .param("code", PROVIDER_CODE)
                .query((rs, rowNum) -> BinaryUuids.fromBytes(rs.getBytes("id")))
                .optional()
                .ifPresent(provider -> {
                    jdbcClient
                            .sql("DELETE FROM provider_credential WHERE provider_id = :providerId")
                            .param("providerId", BinaryUuids.toBytes(provider))
                            .update();
                    jdbcClient
                            .sql("DELETE FROM model WHERE provider_id = :providerId")
                            .param("providerId", BinaryUuids.toBytes(provider))
                            .update();
                    jdbcClient
                            .sql("DELETE FROM model_provider WHERE id = :providerId")
                            .param("providerId", BinaryUuids.toBytes(provider))
                            .update();
                });
    }
}
