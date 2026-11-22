package com.riccon.api.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.repository.AuditRepository;
import com.riccon.api.support.ApiTestClient;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

/**
 * 拒绝审计失败必须返回 500 而不是 429（“无审计不拒绝”的 fail-closed）。
 *
 * <p>只让 {@code quota.exceeded} 写入失败，其他审计调用保持无语义；断言超限请求返回 500、没有创建 run，
 * 且已用量没有因失败路径重复增加。
 */
class QuotaAuditFailClosedTest extends QuotaApiTestSupport {

    /** 抛错的审计仓储。 */
    @MockitoBean
    private AuditRepository auditRepository;

    /** 每个用例前恢复执行模式。 */
    @BeforeEach
    void enableEnforcement() {
        setEnforcement(true);
    }

    /**
     * 拒绝审计失败返回 500，不创建 run。
     */
    @Test
    @DisplayName("IT-QUOTA-101 拒绝审计失败返回 500")
    void rejectionAuditFailure_returns500() {
        doThrow(new DataAccessResourceFailureException("audit storage unavailable"))
                .when(auditRepository)
                .append(argThat((AuditEvent event) -> "quota.exceeded".equals(event.action())));

        Fixture fixture = fixture();
        setTenantQuota(fixture.platform(), fixture.tenant(), 1, 10);
        setUserQuota(fixture.platform(), fixture.tenant(), userId(fixture.member()), 1, 10);
        UUID toolId = createMcpTool(fixture.tenant());
        createTask(fixture.member(), fixture.memberWorkspace(), "审计失败主题一", toolId, "quota-audit-1");

        ApiTestClient.ApiResponse response =
                postResearchTask(fixture.member(), fixture.memberWorkspace(), "审计失败主题二", toolId, "quota-audit-2");

        assertThat(response.status()).as(response.body()).isEqualTo(500);
        JsonNode problem = client.json(response);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-COMMON-500");
        assertThat(problem.path("traceId").asText()).isNotBlank();
        assertThat(taskCount(fixture.tenant())).isEqualTo(1);
        assertThat(runCount(fixture.tenant())).isEqualTo(1);
        assertThat(usedCount(fixture.tenant(), "TENANT", fixture.tenant(), "DAILY"))
                .isEqualTo(1);
    }
}
