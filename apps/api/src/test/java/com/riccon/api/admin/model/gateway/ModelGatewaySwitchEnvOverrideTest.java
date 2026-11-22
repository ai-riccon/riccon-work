package com.riccon.api.admin.model.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.domain.audit.AuditActions;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * 网关开关的环境变量覆盖语义（IT-MODEL-104）。
 *
 * <p>ADR-0021 §3.4/§10.5 要求优先级为“环境变量 &gt; 数据库 &gt; 默认”：本类只覆盖路由与配额两个开关，
 * 验证 GET 返回逐键生效值、{@code source=ENVIRONMENT}、{@code envOverride} 只列出实际覆盖项，
 * 且任意覆盖存在时 PUT 固定 409 {@code ENV_OVERRIDE_ACTIVE}，数据库开关与版本保持不变。
 */
@TestPropertySource(
        properties = {
            "riccon.model.gateway.routing-mode=BASELINE",
            "riccon.model.gateway.quota-enforcement-enabled=true"
        })
class ModelGatewaySwitchEnvOverrideTest extends ApiIntegrationTestSupport {

    /** 每个测试重置数据库开关，确保断言的是覆盖而不是上一个用例的残留。 */
    @BeforeEach
    void resetSwitches() {
        writeSeedSwitches();
    }

    /** 测试结束后恢复种子，避免影响其他测试上下文。 */
    @AfterEach
    void restoreSeedSwitches() {
        writeSeedSwitches();
    }

    /** GET 应逐键返回部署覆盖值，未覆盖键回落数据库，并标记 ENVIRONMENT。 */
    @Test
    @DisplayName("IT-MODEL-104a GET 返回环境覆盖与逐键回落")
    void getSwitches_environmentOverrideWins() {
        LoginSession admin = loginPlatformAdmin();

        JsonNode switches = getSwitches(admin);

        assertThat(switches.path("routingMode").asText())
                .as("环境变量优先于数据库 ROUTED")
                .isEqualTo("BASELINE");
        assertThat(switches.path("failoverEnabled").asBoolean())
                .as("未覆盖的降级开关回落数据库 true")
                .isTrue();
        assertThat(switches.path("quotaEnforcementEnabled").asBoolean())
                .as("环境变量优先于数据库 false")
                .isTrue();
        assertThat(switches.path("source").asText()).isEqualTo("ENVIRONMENT");
        assertThat(switches.path("version").asInt()).isEqualTo(1);
        assertThat(switches.path("envOverride").isObject()).isTrue();
        assertThat(switches.path("envOverride").path("routingMode").asText()).isEqualTo("BASELINE");
        assertThat(switches.path("envOverride").path("quotaEnforcementEnabled").asBoolean())
                .isTrue();
        assertThat(switches.path("envOverride").has("failoverEnabled"))
                .as("envOverride 只列出实际覆盖的开关")
                .isFalse();
    }

    /** 任意开关被环境覆盖时 PUT 409，数据库值、版本与审计都不变。 */
    @Test
    @DisplayName("IT-MODEL-104b 环境覆盖时 PUT 409 且不写库")
    void putSwitches_environmentOverride_returns409() {
        LoginSession admin = loginPlatformAdmin();
        int auditCountBefore =
                auditRows(AuditActions.MODEL_GATEWAY_SWITCH_CHANGED).size();

        ApiTestClient.ApiResponse response = client.put(
                "/api/v1/admin/model-gateway/switches",
                client.jsonBody(
                        "version",
                        1,
                        "routingMode",
                        "ROUTED",
                        "failoverEnabled",
                        true,
                        "quotaEnforcementEnabled",
                        false),
                admin.sessionCookie(),
                admin.csrfToken());

        assertThat(response.status()).as(response.body()).isEqualTo(409);
        JsonNode problem = client.json(response);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-MODEL-409");
        assertThat(problem.path("field").asText()).isEqualTo("envOverride");
        assertThat(problem.path("reason").asText()).isEqualTo("ENV_OVERRIDE_ACTIVE");
        assertThat(switchValues())
                .containsEntry("routingMode", "ROUTED")
                .containsEntry("failoverEnabled", "true")
                .containsEntry("quotaEnforcementEnabled", "false");
        assertThat(maxSwitchVersion()).isEqualTo(1);
        assertThat(auditRows(AuditActions.MODEL_GATEWAY_SWITCH_CHANGED)).hasSize(auditCountBefore);
    }

    /** 读取开关。 */
    private JsonNode getSwitches(LoginSession session) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/admin/model-gateway/switches", session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /** 直接读取全局开关值。 */
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

    /** 恢复 V11 种子值、版本 1、无操作者。 */
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
