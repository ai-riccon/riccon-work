package com.riccon.api.quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 配额准入集成测试（IT-QUOTA-101/105 与执行开关）。
 *
 * <p>走真实 MySQL、真实 HTTP 与真实事务：断言 429 专用响应、拒绝审计、计数不重复、在途 run 不受影响，
 * 以及 {@code quotaEnforcementEnabled=false} 只计量不阻断。
 */
class QuotaAdmissionApiTest extends QuotaApiTestSupport {

    /** 指标注册表。 */
    @Autowired
    private MeterRegistry meterRegistry;

    /** 每个用例前恢复执行模式，避免开关测试影响其他用例。 */
    @BeforeEach
    void enableEnforcement() {
        setEnforcement(true);
    }

    /**
     * IT-QUOTA-101：日限额拒绝、429 专用响应与拒绝审计。
     */
    @Test
    @DisplayName("IT-QUOTA-101 日限额拒绝并写 quota.exceeded")
    void dailyLimit_rejectsAndAudits() {
        Fixture fixture = fixture();
        setTenantQuota(fixture.platform(), fixture.tenant(), 1, 10);
        setUserQuota(fixture.platform(), fixture.tenant(), userId(fixture.member()), 1, 10);
        UUID toolId = createMcpTool(fixture.tenant());
        createTask(fixture.member(), fixture.memberWorkspace(), "配额日限额主题一", toolId, "quota-daily-1");

        ApiTestClient.ApiResponse response =
                postResearchTask(fixture.member(), fixture.memberWorkspace(), "配额日限额主题二", toolId, "quota-daily-2");

        assertQuotaProblem(response, "TENANT", "DAILY", 1, 1);
        assertThat(client.json(response).path("detail").asText()).contains("已达日限额");
        assertThat(usedCount(fixture.tenant(), "TENANT", fixture.tenant(), "DAILY"))
                .isEqualTo(1);
        assertThat(usedCount(fixture.tenant(), "TENANT", fixture.tenant(), "MONTHLY"))
                .isEqualTo(1);
        assertThat(usedCount(fixture.tenant(), "USER", userId(fixture.member()), "DAILY"))
                .isEqualTo(1);
        assertThat(taskCount(fixture.tenant())).isEqualTo(1);
        assertThat(runCount(fixture.tenant())).isEqualTo(1);

        List<AuditRow> rows = auditRows("quota.exceeded").stream()
                .filter(row -> fixture.tenant().equals(row.tenantId()))
                .toList();
        assertThat(rows).hasSize(1);
        AuditRow audit = rows.getFirst();
        assertThat(audit.actorType()).isEqualTo("USER");
        assertThat(audit.actorId()).isEqualTo(userId(fixture.member()));
        assertThat(audit.targetType()).isEqualTo("QUOTA");
        assertThat(audit.targetId()).isEqualTo(fixture.tenant());
        assertThat(audit.outcome()).isEqualTo("DENIED");
        JsonNode details = details(audit);
        assertThat(details.path("scopeType").asText()).isEqualTo("TENANT");
        assertThat(details.path("scopeId").asText()).isEqualTo(fixture.tenant());
        assertThat(details.path("window").asText()).isEqualTo("DAILY");
        assertThat(details.path("limit").asInt()).isEqualTo(1);
        assertThat(details.path("used").asInt()).isEqualTo(1);
        assertThat(details.path("resetsAt").asText()).endsWith("+08:00");
    }

    /**
     * IT-QUOTA-101：月限额拒绝。
     */
    @Test
    @DisplayName("IT-QUOTA-101 月限额拒绝")
    void monthlyLimit_rejects() {
        Fixture fixture = fixture();
        setTenantQuota(fixture.platform(), fixture.tenant(), 10, 1);
        setUserQuota(fixture.platform(), fixture.tenant(), userId(fixture.member()), 10, 10);
        UUID toolId = createMcpTool(fixture.tenant());
        createTask(fixture.member(), fixture.memberWorkspace(), "配额月限额主题一", toolId, "quota-month-1");

        ApiTestClient.ApiResponse response =
                postResearchTask(fixture.member(), fixture.memberWorkspace(), "配额月限额主题二", toolId, "quota-month-2");

        assertQuotaProblem(response, "TENANT", "MONTHLY", 1, 1);
        assertThat(taskCount(fixture.tenant())).isEqualTo(1);
    }

    /**
     * IT-QUOTA-101：用户级日限额先于租户级用尽时拒绝在 USER/DAILY。
     */
    @Test
    @DisplayName("IT-QUOTA-101 用户日限额拒绝")
    void userDailyLimit_rejects() {
        Fixture fixture = fixture();
        setTenantQuota(fixture.platform(), fixture.tenant(), 10, 10);
        setUserQuota(fixture.platform(), fixture.tenant(), userId(fixture.member()), 1, 10);
        UUID toolId = createMcpTool(fixture.tenant());
        createTask(fixture.member(), fixture.memberWorkspace(), "配额用户限额主题一", toolId, "quota-user-1");

        ApiTestClient.ApiResponse response =
                postResearchTask(fixture.member(), fixture.memberWorkspace(), "配额用户限额主题二", toolId, "quota-user-2");

        assertQuotaProblem(response, "USER", "DAILY", 1, 1);
        assertThat(usedCount(fixture.tenant(), "TENANT", fixture.tenant(), "DAILY"))
                .isEqualTo(1);
    }

    /**
     * IT-QUOTA-105：调整限额不影响已创建的在途 run，且幂等重放不重复扣减。
     */
    @Test
    @DisplayName("IT-QUOTA-105 幂等重放不重复扣减且在途 run 不受调低影响")
    void replayAndLoweredLimit_keepOneChargeAndRun() {
        Fixture fixture = fixture();
        setTenantQuota(fixture.platform(), fixture.tenant(), 2, 10);
        setUserQuota(fixture.platform(), fixture.tenant(), userId(fixture.member()), 2, 10);
        UUID toolId = createMcpTool(fixture.tenant());
        JsonNode first = createTask(fixture.member(), fixture.memberWorkspace(), "配额幂等主题", toolId, "quota-idem-1");
        String runId = runId(first);

        JsonNode replay = createTask(fixture.member(), fixture.memberWorkspace(), "配额幂等主题", toolId, "quota-idem-1");
        assertThat(replay.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(usedCount(fixture.tenant(), "TENANT", fixture.tenant(), "DAILY"))
                .isEqualTo(1);
        assertThat(runCount(fixture.tenant())).isEqualTo(1);

        // 调低到已用量：新请求拒绝，但既有 run 仍在途（PENDING）且没有被取消/迁移。
        setTenantQuota(fixture.platform(), fixture.tenant(), 1, 10);
        ApiTestClient.ApiResponse rejected =
                postResearchTask(fixture.member(), fixture.memberWorkspace(), "配额幂等主题二", toolId, "quota-idem-2");
        assertThat(rejected.status()).isEqualTo(429);
        assertThat(runStatus(fixture.tenant(), runId)).isEqualTo("PENDING");
        assertThat(taskStatus(fixture.tenant(), first.path("id").asText())).isEqualTo("PENDING");
        assertThat(runCount(fixture.tenant())).isEqualTo(1);
    }

    /**
     * 显式 0 等价于不限：不阻断并继续保持计数。
     */
    @Test
    @DisplayName("显式 0 限额视为不限")
    void explicitZero_isUnlimited() {
        Fixture fixture = fixture();
        setTenantQuota(fixture.platform(), fixture.tenant(), 0, 0);
        setUserQuota(fixture.platform(), fixture.tenant(), userId(fixture.member()), 0, 0);
        UUID toolId = createMcpTool(fixture.tenant());

        createTask(fixture.member(), fixture.memberWorkspace(), "显式不限主题一", toolId, "quota-zero-1");
        createTask(fixture.member(), fixture.memberWorkspace(), "显式不限主题二", toolId, "quota-zero-2");

        assertThat(usedCount(fixture.tenant(), "TENANT", fixture.tenant(), "DAILY"))
                .isEqualTo(2);
        assertThat(taskCount(fixture.tenant())).isEqualTo(2);
    }

    /**
     * 执行开关关闭时只计量：不拒绝、不写拒绝审计，并暴露超限观察指标。
     */
    @Test
    @DisplayName("quotaEnforcement=false 只计量不阻断")
    void enforcementDisabled_metersWithoutBlocking() {
        Fixture fixture = fixture();
        setTenantQuota(fixture.platform(), fixture.tenant(), 1, 1);
        setUserQuota(fixture.platform(), fixture.tenant(), userId(fixture.member()), 1, 1);
        setEnforcement(false);
        UUID toolId = createMcpTool(fixture.tenant());
        double rejectionsBefore = counter("riccon.quota.rejections", "USER", "DAILY");
        double observedBefore = counter("riccon.quota.over.limit.observed", "USER", "DAILY");

        createTask(fixture.member(), fixture.memberWorkspace(), "只计量主题一", toolId, "quota-off-1");
        createTask(fixture.member(), fixture.memberWorkspace(), "只计量主题二", toolId, "quota-off-2");

        assertThat(usedCount(fixture.tenant(), "TENANT", fixture.tenant(), "DAILY"))
                .isEqualTo(2);
        assertThat(usedCount(fixture.tenant(), "USER", userId(fixture.member()), "DAILY"))
                .isEqualTo(2);
        assertThat(auditRows("quota.exceeded").stream()
                        .filter(row -> fixture.tenant().equals(row.tenantId()))
                        .toList())
                .isEmpty();
        assertThat(counter("riccon.quota.rejections", "USER", "DAILY")).isEqualTo(rejectionsBefore);
        assertThat(counter("riccon.quota.over.limit.observed", "USER", "DAILY")).isGreaterThan(observedBefore);
        assertThat(meterRegistry
                        .get("riccon.quota.usage.ratio")
                        .tags("scope", "USER", "window", "DAILY")
                        .gauge()
                        .value())
                .isGreaterThanOrEqualTo(2.0d);
    }

    /**
     * 断言 429 专用 ProblemDetail。
     *
     * @param response 响应
     * @param scopeType 期望维度
     * @param window 期望窗口
     * @param limit 期望限额
     * @param used 期望已用量
     */
    private void assertQuotaProblem(
            ApiTestClient.ApiResponse response, String scopeType, String window, int limit, int used) {
        assertThat(response.status()).as(response.body()).isEqualTo(429);
        assertThat(response.header("Content-Type")).contains("application/problem+json");
        JsonNode body = client.json(response);
        assertThat(body.path("status").asInt()).isEqualTo(429);
        assertThat(body.path("code").asText()).isEqualTo("RICCON-QUOTA-429");
        assertThat(body.path("traceId").asText()).isNotBlank();
        assertThat(body.path("scopeType").asText()).isEqualTo(scopeType);
        assertThat(body.path("window").asText()).isEqualTo(window);
        assertThat(body.path("limit").asInt()).isEqualTo(limit);
        assertThat(body.path("used").asInt()).isEqualTo(used);
        assertThat(body.path("resetsAt").asText()).endsWith("+08:00");
    }

    /**
     * 读取指标计数。
     *
     * @param name 指标名
     * @param scope 维度标签
     * @param window 窗口标签
     * @return 当前计数；不存在时为 0
     */
    private double counter(String name, String scope, String window) {
        var counter =
                meterRegistry.find(name).tags("scope", scope, "window", window).counter();
        return counter == null ? 0.0d : counter.count();
    }
}
