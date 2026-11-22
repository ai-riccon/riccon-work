package com.riccon.api.security.m04;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.domain.audit.AuditActions;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M04-V1 delta 独立验收：网关开关 GET/PUT 契约与权限（F-002 / AC-02 开关段）。
 *
 * <p>与 Builder 的 `ModelGatewaySwitchApiTest` 独立：直接从 HTTP 契约与数据库审计断言，
 * 覆盖 GET 种子值、PUT 版本推进与下一次读取、旧版本 409、租户管理员/员工 403、审计白名单；
 * 环境变量覆盖语义由本类的独立复跑（Builder `ModelGatewaySwitchEnvOverrideTest`）与本类外的
 * 定向证据补充。
 */
@Tag("security")
class M04VerifierSwitchDeltaTest extends ApiIntegrationTestSupport {

    /** JSON 解析器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 每个用例重置 V11 种子，避免共享上下文漂移。 */
    @BeforeEach
    void resetSwitches() {
        writeSeedSwitches();
    }

    /** 用例后恢复种子。 */
    @AfterEach
    void restoreSeedSwitches() {
        writeSeedSwitches();
    }

    /**
     * F-002：平台管理员可读/写开关；版本乐观锁、审计与下一次请求生效成立。
     */
    @Test
    @DisplayName("F-002 开关 GET/PUT、版本 409、审计与租户/员工 403")
    void switchEndpointContractAndPermission() {
        LoginSession platform = loginPlatformAdmin();
        JsonNode seed = getSwitches(platform);
        assertThat(seed.path("routingMode").asText()).isEqualTo("ROUTED");
        assertThat(seed.path("failoverEnabled").asBoolean()).isTrue();
        assertThat(seed.path("quotaEnforcementEnabled").asBoolean()).isFalse();
        assertThat(seed.path("version").asInt()).isEqualTo(1);
        assertThat(seed.path("source").asText()).isEqualTo("DATABASE");
        assertThat(seed.path("envOverride").isNull()).isTrue();

        int auditBefore = auditRows(AuditActions.MODEL_GATEWAY_SWITCH_CHANGED).size();
        ApiTestClient.ApiResponse saved = putSwitches(platform, 1, "BASELINE", false, true);
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        JsonNode body = client.json(saved);
        assertThat(body.path("routingMode").asText()).isEqualTo("BASELINE");
        assertThat(body.path("failoverEnabled").asBoolean()).isFalse();
        assertThat(body.path("quotaEnforcementEnabled").asBoolean()).isTrue();
        assertThat(body.path("version").asInt()).isEqualTo(2);

        // 下一次 GET 不缓存，立即返回保存值（B10 quota false→true 的 API 路径）。
        JsonNode afterSave = getSwitches(platform);
        assertThat(afterSave.path("routingMode").asText()).isEqualTo("BASELINE");
        assertThat(afterSave.path("version").asInt()).isEqualTo(2);

        List<AuditRow> audits = auditRows(AuditActions.MODEL_GATEWAY_SWITCH_CHANGED);
        assertThat(audits).hasSize(auditBefore + 3);
        assertThat(audits.subList(auditBefore, audits.size())).allSatisfy(row -> {
            assertThat(row.tenantId()).isEqualTo("platform");
            assertThat(row.actorType()).isEqualTo("USER");
            assertThat(row.targetType()).isEqualTo("MODEL");
            assertThat(row.outcome()).isEqualTo("SUCCESS");
            JsonNode details = MAPPER.readTree(row.details());
            assertThat(details.path("source").asText()).isEqualTo("DATABASE");
            assertThat(details.has("switch")).isTrue();
            assertThat(details.has("value")).isTrue();
            assertThat(details.has("previousValue")).isTrue();
            assertThat(row.details()).doesNotContain("apiKey").doesNotContain("baseUrl");
        });

        // 旧版本：409 VERSION_CONFLICT，不覆盖已推进的值。
        ApiTestClient.ApiResponse conflict = putSwitches(platform, 1, "ROUTED", true, false);
        assertThat(conflict.status()).as(conflict.body()).isEqualTo(409);
        JsonNode problem = client.json(conflict);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-MODEL-409");
        assertThat(problem.path("field").asText()).isEqualTo("version");
        assertThat(problem.path("reason").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(getSwitches(platform).path("version").asInt()).isEqualTo(2);

        // 租户管理员/员工：读/写 403；匿名 401；响应不含开关内容。
        String tenantCode = uniqueTenantCode("m04vswitch");
        createTenant(platform, tenantCode);
        LoginSession tenantAdmin = createAndLoginTenantAdmin(platform, tenantCode);
        LoginSession member = createAndLoginMember(platform, tenantCode);
        for (LoginSession session : List.of(tenantAdmin, member)) {
            ApiTestClient.ApiResponse read =
                    client.get("/api/v1/admin/model-gateway/switches", session.sessionCookie(), Map.of());
            assertThat(read.status()).isEqualTo(403);
            assertThat(client.json(read).path("code").asText()).isEqualTo("RICCON-AUTH-403");
            assertThat(read.body()).doesNotContain("routingMode").doesNotContain("BASELINE");
            ApiTestClient.ApiResponse write = client.put(
                    "/api/v1/admin/model-gateway/switches",
                    client.jsonBody(
                            "version",
                            2,
                            "routingMode",
                            "ROUTED",
                            "failoverEnabled",
                            true,
                            "quotaEnforcementEnabled",
                            false),
                    session.sessionCookie(),
                    session.csrfToken());
            assertThat(write.status()).isEqualTo(403);
            assertThat(write.body()).doesNotContain("routingMode");
        }
        assertThat(client.get("/api/v1/admin/model-gateway/switches", null, Map.of())
                        .status())
                .isEqualTo(401);
    }

    /** 读取开关。 */
    private JsonNode getSwitches(LoginSession session) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/admin/model-gateway/switches", session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /** 整组保存。 */
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

    /** 恢复 V11 种子值。 */
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
}
