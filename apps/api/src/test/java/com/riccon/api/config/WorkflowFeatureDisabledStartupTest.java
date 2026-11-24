package com.riccon.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * 关闭态编排开关的启动观测与平台租户 404 守卫（ADR-0024 §4.8/§6.1、OPS-M06-019/024）。
 *
 * <p>独立上下文以 {@code RICCON_WORKFLOW_ENABLED=false} 启动：启动后数据库必须有
 * {@code workflow.feature.changed} 审计（platform/SYSTEM，details 含 instanceId/observedAt），且健康检查
 * 仍可用——证明关闭态审计不阻断启动。同一上下文再通过真实安全链验证平台管理员访问
 * {@code /api/v1/workflows} 得到 404 {@code RICCON-TENANT-404}（不是 403），即守卫已接线且在权限
 * 拦截器之前生效。
 */
@TestPropertySource(
        properties = {
            "riccon.workflow.enabled=false",
            "riccon.workflow.disabled-reason=只读演练",
            "riccon.workflow.instance-id=it-disabled"
        })
class WorkflowFeatureDisabledStartupTest extends ApiIntegrationTestSupport {

    /** 关闭态启动写审计，且 API 健康。 */
    @Test
    @DisplayName("关闭态启动写 workflow.feature.changed 观测审计且不影响启动")
    void disabledStartupWritesFeatureChangedAudit() {
        ApiTestClient.ApiResponse health = client.get("/actuator/health");
        assertThat(health.status()).as(health.body()).isEqualTo(200);

        Map<String, String> row = jdbcClient
                .sql("""
                        SELECT tenant_id, actor_type, actor_id, target_type, outcome,
                               JSON_UNQUOTE(JSON_EXTRACT(details, '$.enabled')) AS enabled,
                               JSON_UNQUOTE(JSON_EXTRACT(details, '$.source')) AS source,
                               JSON_UNQUOTE(JSON_EXTRACT(details, '$.reason')) AS reason,
                               JSON_UNQUOTE(JSON_EXTRACT(details, '$.instanceId')) AS instance_id,
                               JSON_UNQUOTE(JSON_EXTRACT(details, '$.observedAt')) AS observed_at
                          FROM audit_event
                         WHERE action = 'workflow.feature.changed'
                           AND tenant_id = 'platform'
                         ORDER BY occurred_at DESC
                         LIMIT 1
                        """)
                .query((rs, rowNum) -> Map.of(
                        "tenantId", rs.getString("tenant_id"),
                        "actorType", rs.getString("actor_type"),
                        "actorId", rs.getString("actor_id"),
                        "targetType", rs.getString("target_type"),
                        "outcome", rs.getString("outcome"),
                        "enabled", rs.getString("enabled"),
                        "source", rs.getString("source"),
                        "reason", rs.getString("reason"),
                        "instanceId", rs.getString("instance_id"),
                        "observedAt", rs.getString("observed_at")))
                .single();

        assertThat(row)
                .containsEntry("tenantId", "platform")
                .containsEntry("actorType", "SYSTEM")
                .containsEntry("actorId", "system")
                .containsEntry("targetType", "WORKFLOW")
                .containsEntry("outcome", "SUCCESS")
                .containsEntry("enabled", "false")
                .containsEntry("source", WorkflowFeatureStartupAuditor.SOURCE_DEPLOYMENT_CONFIG)
                .containsEntry("reason", "只读演练")
                .containsEntry("instanceId", "it-disabled");
        assertThat(row.get("observedAt")).endsWith("+08:00");
    }

    /** 平台管理员访问工作流端点得到 404（不是 403），证明守卫在权限拦截器之前。 */
    @Test
    @DisplayName("平台管理员访问 /api/v1/workflows 返回 404 RICCON-TENANT-404")
    void platformAdminGets404OnWorkflowEndpoint() {
        LoginSession platform = loginPlatformAdmin();
        ApiTestClient.ApiResponse response = client.get("/api/v1/workflows", platform.sessionCookie(), Map.of());
        assertThat(response.status()).isEqualTo(404);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-TENANT-404");
    }
}
