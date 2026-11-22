package com.riccon.api.admin.model.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.repository.AuditRepository;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.domain.audit.AuditActions;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 网关开关 API 的集成行为（IT-MODEL-103）。
 *
 * <p>覆盖 ADR-0021 §3.4/§7.1/§7.4：GET 返回 V11 种子默认值；PUT 整组保存、版本推进与下一次 GET 立即生效；
 * 旧版本 409；字段级 400；租户管理员/员工读写均 403；每次实际变化写
 * {@code model.gateway.switch.changed}（switch/value/previousValue/source）；审计失败回滚整组保存。
 * B10 的 quota false→true 演练直接复用本接口，因此用例显式验证该路径。
 */
class ModelGatewaySwitchApiTest extends ApiIntegrationTestSupport {

    /** JSON 解析器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 审计仓储 spy；启动、登录与租户夹具走真实实现，只在测试指定阶段让开关审计失败。 */
    @MockitoSpyBean
    private AuditRepository auditRepository;

    /** 是否让 {@code model.gateway.switch.changed} 审计失败。 */
    private final AtomicBoolean failSwitchAudit = new AtomicBoolean(false);

    /** 每个测试重置为 V11 种子值，避免跨测试漂移；并安装条件审计失败桩。 */
    @BeforeEach
    void resetSwitchesAndStubAudit() {
        resetSwitches();
        doAnswer(invocation -> {
                    AuditEvent event = invocation.getArgument(0);
                    if (failSwitchAudit.get() && AuditActions.MODEL_GATEWAY_SWITCH_CHANGED.equals(event.action())) {
                        throw new DataAccessResourceFailureException("audit storage unavailable");
                    }
                    return invocation.callRealMethod();
                })
                .when(auditRepository)
                .append(any(AuditEvent.class));
    }

    /** 测试结束后恢复种子，避免影响其他测试上下文。 */
    @AfterEach
    void restoreSeedSwitches() {
        resetSwitches();
    }

    /** GET 必须返回与 V11 种子一致的生效值和整组版本。 */
    @Test
    @DisplayName("IT-MODEL-103a GET 返回 V11 种子默认值与来源")
    void getSwitches_returnsSeedDefaults() {
        LoginSession admin = loginPlatformAdmin();

        JsonNode switches = getSwitches(admin);

        assertThat(switches.path("routingMode").asText()).isEqualTo("ROUTED");
        assertThat(switches.path("failoverEnabled").asBoolean()).isTrue();
        assertThat(switches.path("quotaEnforcementEnabled").asBoolean()).isFalse();
        assertThat(switches.path("version").asInt()).isEqualTo(1);
        assertThat(switches.path("source").asText()).isEqualTo("DATABASE");
        assertThat(switches.path("envOverride").isNull())
                .as("无环境覆盖时 envOverride 为 null")
                .isTrue();
        assertThat(switches.path("updatedBy").isNull()).as("迁移种子没有操作者").isTrue();
        assertThat(switches.path("updatedAt").asText())
                .as("时间必须带 +08:00 偏移")
                .startsWith("2026-11-20T08:00")
                .endsWith("+08:00");
    }

    /** PUT 整组保存、版本推进、B10 quota false→true、审计与重复保存语义。 */
    @Test
    @DisplayName("IT-MODEL-103b PUT 整组保存、版本推进、审计与 quota false→true")
    void putSwitches_updatesGroupAndWritesAudit() {
        LoginSession admin = loginPlatformAdmin();
        UUID adminId = UUID.fromString(admin.body().path("user").path("id").asText());
        int auditCountBefore =
                auditRows(AuditActions.MODEL_GATEWAY_SWITCH_CHANGED).size();

        ApiTestClient.ApiResponse saved = putSwitches(admin, 1, "BASELINE", false, true);
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        JsonNode body = client.json(saved);
        assertThat(body.path("routingMode").asText()).isEqualTo("BASELINE");
        assertThat(body.path("failoverEnabled").asBoolean()).isFalse();
        assertThat(body.path("quotaEnforcementEnabled").asBoolean())
                .as("B10 quota false→true 演练必须能通过 PUT 打开")
                .isTrue();
        assertThat(body.path("version").asInt()).isEqualTo(2);
        assertThat(body.path("source").asText()).isEqualTo("DATABASE");
        assertThat(body.path("updatedBy").asText()).isEqualTo(adminId.toString());
        assertThat(body.path("updatedAt").asText()).endsWith("+08:00");

        // 下一次 GET 不缓存，立即返回新值和推进后的版本。
        JsonNode afterSave = getSwitches(admin);
        assertThat(afterSave.path("routingMode").asText()).isEqualTo("BASELINE");
        assertThat(afterSave.path("failoverEnabled").asBoolean()).isFalse();
        assertThat(afterSave.path("quotaEnforcementEnabled").asBoolean()).isTrue();
        assertThat(afterSave.path("version").asInt()).isEqualTo(2);

        // 每个实际变化的开关各一条审计；details 只写白名单字段。
        List<AuditRow> audits = auditRows(AuditActions.MODEL_GATEWAY_SWITCH_CHANGED);
        assertThat(audits).hasSize(auditCountBefore + 3);
        Map<String, JsonNode> detailsBySwitch = new LinkedHashMap<>();
        for (AuditRow audit : audits.subList(auditCountBefore, audits.size())) {
            assertThat(audit.tenantId()).isEqualTo("platform");
            assertThat(audit.actorType()).isEqualTo("USER");
            assertThat(audit.actorId()).isEqualTo(adminId.toString());
            assertThat(audit.targetType()).isEqualTo("MODEL");
            assertThat(audit.outcome()).isEqualTo("SUCCESS");
            JsonNode details = MAPPER.readTree(audit.details());
            assertThat(details.path("source").asText()).isEqualTo("DATABASE");
            detailsBySwitch.put(details.path("switch").asText(), details);
        }
        assertThat(detailsBySwitch.keySet())
                .containsExactlyInAnyOrder("routingMode", "failoverEnabled", "quotaEnforcementEnabled");
        assertThat(detailsBySwitch.get("routingMode").path("value").asText()).isEqualTo("BASELINE");
        assertThat(detailsBySwitch.get("routingMode").path("previousValue").asText())
                .isEqualTo("ROUTED");
        assertThat(detailsBySwitch.get("failoverEnabled").path("value").asText())
                .isEqualTo("false");
        assertThat(detailsBySwitch.get("failoverEnabled").path("previousValue").asText())
                .isEqualTo("true");
        assertThat(detailsBySwitch.get("quotaEnforcementEnabled").path("value").asText())
                .isEqualTo("true");
        assertThat(detailsBySwitch
                        .get("quotaEnforcementEnabled")
                        .path("previousValue")
                        .asText())
                .isEqualTo("false");

        // 重复保存相同值仍推进版本（real-stack checks 依赖该语义），但不制造无意义审计。
        ApiTestClient.ApiResponse identical = putSwitches(admin, 2, "BASELINE", false, true);
        assertThat(identical.status()).as(identical.body()).isEqualTo(200);
        assertThat(client.json(identical).path("version").asInt()).isEqualTo(3);
        assertThat(auditRows(AuditActions.MODEL_GATEWAY_SWITCH_CHANGED)).hasSize(auditCountBefore + 3);
    }

    /** 旧版本必须 409，且不覆盖已被并发推进的值。 */
    @Test
    @DisplayName("IT-MODEL-103c 旧版本 409 且不写入")
    void putSwitches_versionConflict_returns409WithoutWrites() {
        LoginSession admin = loginPlatformAdmin();
        ApiTestClient.ApiResponse saved = putSwitches(admin, 1, "BASELINE", false, true);
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        int auditCountAfterSave =
                auditRows(AuditActions.MODEL_GATEWAY_SWITCH_CHANGED).size();

        ApiTestClient.ApiResponse conflict = putSwitches(admin, 1, "ROUTED", true, false);

        assertThat(conflict.status()).as(conflict.body()).isEqualTo(409);
        JsonNode problem = client.json(conflict);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-MODEL-409");
        assertThat(problem.path("field").asText()).isEqualTo("version");
        assertThat(problem.path("reason").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(problem.path("detail").asText()).contains("current version is 2");

        JsonNode current = getSwitches(admin);
        assertThat(current.path("routingMode").asText()).isEqualTo("BASELINE");
        assertThat(current.path("failoverEnabled").asBoolean()).isFalse();
        assertThat(current.path("quotaEnforcementEnabled").asBoolean()).isTrue();
        assertThat(current.path("version").asInt()).isEqualTo(2);
        assertThat(auditRows(AuditActions.MODEL_GATEWAY_SWITCH_CHANGED)).hasSize(auditCountAfterSave);
    }

    /** 租户管理员与员工读写均 403，响应不含开关内容；平台管理员仍可读。 */
    @Test
    @DisplayName("IT-MODEL-103d 租户/员工越权 403")
    void switches_tenantAndMemberForbidden() {
        LoginSession platformAdmin = loginPlatformAdmin();
        String tenantCode = uniqueTenantCode("m04switch");
        createTenant(platformAdmin, tenantCode);
        LoginSession tenantAdmin = createAndLoginTenantAdmin(platformAdmin, tenantCode);
        LoginSession member = createAndLoginMember(platformAdmin, tenantCode);

        assertForbidden(client.get("/api/v1/admin/model-gateway/switches", tenantAdmin.sessionCookie(), Map.of()));
        assertForbidden(client.get("/api/v1/admin/model-gateway/switches", member.sessionCookie(), Map.of()));
        assertForbidden(putSwitches(tenantAdmin, 1, "BASELINE", false, true));
        assertForbidden(putSwitches(member, 1, "BASELINE", false, true));

        JsonNode switches = getSwitches(platformAdmin);
        assertThat(switches.path("version").asInt()).as("越权调用不得推进版本").isEqualTo(1);
        assertThat(switches.path("routingMode").asText()).isEqualTo("ROUTED");
    }

    /** 字段缺失/非法返回稳定字段级 400；不存在的子路径 404，证明没有额外契约端点。 */
    @Test
    @DisplayName("IT-MODEL-103e 字段级 400 与无额外端点")
    void putSwitches_invalidBody_returnsFieldErrors() {
        LoginSession admin = loginPlatformAdmin();

        assertInvalidField(
                client.put(
                        "/api/v1/admin/model-gateway/switches",
                        client.jsonBody(
                                "routingMode", "BASELINE",
                                "failoverEnabled", false,
                                "quotaEnforcementEnabled", true),
                        admin.sessionCookie(),
                        admin.csrfToken()),
                "version",
                "INVALID_VERSION");
        assertInvalidField(putSwitches(admin, 0, "BASELINE", false, true), "version", "INVALID_VERSION");
        assertInvalidField(putSwitches(admin, 1, "ROUND_ROBIN", false, true), "routingMode", "INVALID_ROUTING_MODE");
        assertInvalidField(
                client.put(
                        "/api/v1/admin/model-gateway/switches",
                        client.jsonBody("version", 1, "routingMode", "BASELINE", "quotaEnforcementEnabled", true),
                        admin.sessionCookie(),
                        admin.csrfToken()),
                "failoverEnabled",
                "INVALID_FAILOVER_ENABLED");
        assertInvalidField(
                client.put(
                        "/api/v1/admin/model-gateway/switches",
                        client.jsonBody("version", 1, "routingMode", "BASELINE", "failoverEnabled", false),
                        admin.sessionCookie(),
                        admin.csrfToken()),
                "quotaEnforcementEnabled",
                "INVALID_QUOTA_ENFORCEMENT_ENABLED");

        ApiTestClient.ApiResponse unknownPath =
                client.get("/api/v1/admin/model-gateway/switches/versions", admin.sessionCookie(), Map.of());
        assertThat(unknownPath.status()).isEqualTo(404);
        assertThat(client.json(unknownPath).path("code").asText()).isEqualTo("RICCON-REQUEST-404");

        JsonNode current = getSwitches(admin);
        assertThat(current.path("version").asInt()).as("所有失败路径都没有改写开关").isEqualTo(1);
        assertThat(current.path("routingMode").asText()).isEqualTo("ROUTED");
    }

    /** 审计写入失败时整组保存回滚，数据库版本与值不变。 */
    @Test
    @DisplayName("IT-MODEL-103f 审计失败回滚整组保存")
    void putSwitches_auditFailure_rollsBack() {
        LoginSession admin = loginPlatformAdmin();
        int auditCountBefore =
                auditRows(AuditActions.MODEL_GATEWAY_SWITCH_CHANGED).size();
        failSwitchAudit.set(true);

        ApiTestClient.ApiResponse failed = putSwitches(admin, 1, "BASELINE", false, true);

        assertThat(failed.status()).as(failed.body()).isEqualTo(500);
        assertThat(client.json(failed).path("code").asText()).isEqualTo("RICCON-COMMON-500");
        assertThat(switchValues())
                .containsEntry("routingMode", "ROUTED")
                .containsEntry("failoverEnabled", "true")
                .containsEntry("quotaEnforcementEnabled", "false");
        assertThat(maxSwitchVersion()).as("回滚后版本不变").isEqualTo(1);
        assertThat(auditRows(AuditActions.MODEL_GATEWAY_SWITCH_CHANGED)).hasSize(auditCountBefore);

        // 恢复审计后同一请求可以成功，证明失败不是开关状态被破坏造成的。
        failSwitchAudit.set(false);
        assertThat(putSwitches(admin, 1, "BASELINE", false, true).status()).isEqualTo(200);
    }

    /** 读取开关。 */
    private JsonNode getSwitches(LoginSession session) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/admin/model-gateway/switches", session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /** 整组保存请求。 */
    private ApiTestClient.ApiResponse putSwitches(
            LoginSession session,
            int version,
            String routingMode,
            boolean failoverEnabled,
            boolean quotaEnforcementEnabled) {
        return client.put(
                "/api/v1/admin/model-gateway/switches",
                client.jsonBody(
                        "version",
                        version,
                        "routingMode",
                        routingMode,
                        "failoverEnabled",
                        failoverEnabled,
                        "quotaEnforcementEnabled",
                        quotaEnforcementEnabled),
                session.sessionCookie(),
                session.csrfToken());
    }

    /** 断言越权 403 且不泄露开关内容。 */
    private void assertForbidden(ApiTestClient.ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(403);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-AUTH-403");
        assertThat(response.body()).doesNotContain("routingMode").doesNotContain("failoverEnabled");
    }

    /** 断言字段级 400。 */
    private void assertInvalidField(ApiTestClient.ApiResponse response, String field, String reason) {
        assertThat(response.status()).as(response.body()).isEqualTo(400);
        JsonNode problem = client.json(response);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-MODEL-400");
        assertThat(problem.path("field").asText()).isEqualTo(field);
        assertThat(problem.path("reason").asText()).isEqualTo(reason);
    }

    /** 直接读取全局开关值，绕过环境覆盖与 API 映射。 */
    private Map<String, String> switchValues() {
        return jdbcClient
                .sql("SELECT switch_key, switch_value FROM model_gateway_switch")
                .query((rs, rowNum) -> Map.entry(rs.getString("switch_key"), rs.getString("switch_value")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** 读取三行中的最大版本。 */
    private int maxSwitchVersion() {
        return Optional.ofNullable(jdbcClient
                        .sql("SELECT MAX(version) FROM model_gateway_switch")
                        .query(Integer.class)
                        .single())
                .orElse(0);
    }

    /** 重置为 V11 种子值、版本 1、无操作者。 */
    private void resetSwitches() {
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
}
