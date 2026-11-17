package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.repository.AuditRepository;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.ResearchApiTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

/**
 * 审计写入失败必须使报告任务创建失败（ADR-0007 fail-closed）。
 *
 * <p>只让 {@code research.task.created} 失败，其他审计调用保持无语义，避免影响登录与租户创建；
 * 断言返回 500 且任务、运行与 outbox 都没有残留，证明事务整体回滚。
 */
class ResearchAuditFailClosedTest extends ResearchApiTestSupport {

    /** 抛异常的审计仓储。 */
    @MockitoBean
    private AuditRepository auditRepository;

    /** 创建任务时审计失败返回 500，业务数据全部回滚。 */
    @Test
    @DisplayName("审计失败使创建任务失败")
    void create_failsWhenAuditWriteFails() {
        doThrow(new DataAccessResourceFailureException("audit storage unavailable"))
                .when(auditRepository)
                .append(argThat((AuditEvent event) -> "research.task.created".equals(event.action())));

        Fixture fixture = fixture();
        UUID toolId = createMcpTool(fixture.tenant());
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workspaces/" + fixture.memberWorkspace() + "/research-tasks",
                client.jsonBody("topic", "审计失败场景主题", "mcpToolId", toolId.toString()),
                fixture.member().sessionCookie(),
                fixture.member().csrfToken(),
                Map.of("Idempotency-Key", "audit-fail-0001"));
        assertThat(response.status()).isEqualTo(500);
        JsonNode problem = client.json(response);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-COMMON-500");
        assertThat(problem.path("traceId").asText()).isNotBlank();

        assertThat(count("research_task", fixture.tenant())).isZero();
        assertThat(count("agent_run", fixture.tenant())).isZero();
        assertThat(count("agent_run_outbox", fixture.tenant())).isZero();
    }

    /** 审批决定时审计失败返回 500，审批、运行与 outbox 都不迁移。 */
    @Test
    @DisplayName("审计失败使审批决定失败")
    void decide_failsWhenAuditWriteFails() {
        doThrow(new DataAccessResourceFailureException("audit storage unavailable"))
                .when(auditRepository)
                .append(argThat((AuditEvent event) -> "approval.decided".equals(event.action())));

        Fixture fixture = fixture();
        UUID toolId = createMcpTool(fixture.tenant());
        JsonNode task = createTask(fixture.member(), fixture.memberWorkspace(), "审批审计失败主题", toolId, "audit-fail-0002");
        String taskId = task.path("id").asText();
        String runId = runId(task);
        markRunWaitingApproval(fixture.tenant(), runId);
        UUID approvalId = seedApproval(
                fixture.tenant(),
                taskId,
                runId,
                toolId,
                userId(fixture.member()),
                "search_market",
                "PENDING",
                "{\"region\":\"CN\"}");

        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/approvals/" + approvalId + "/decision",
                client.jsonBody("decision", "APPROVE"),
                fixture.admin().sessionCookie(),
                fixture.admin().csrfToken(),
                Map.of("Idempotency-Key", "audit-fail-decide-1"));
        assertThat(response.status()).isEqualTo(500);

        assertThat(approvalStatus(fixture.tenant(), approvalId)).isEqualTo("PENDING");
        assertThat(runStatus(fixture.tenant(), runId)).isEqualTo("WAITING_APPROVAL");
        assertThat(taskStatus(fixture.tenant(), taskId)).isEqualTo("PENDING");
        assertThat(outboxCount(fixture.tenant(), runId, "riccon.agent.run.resume.v1"))
                .isZero();
    }

    /**
     * 统计租户内指定表行数。
     *
     * @param table 表名（测试固定常量，不接受外部输入）
     * @param tenant 租户
     * @return 行数
     */
    private long count(String table, String tenant) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = :tenantId")
                .param("tenantId", tenant)
                .query(Long.class)
                .single();
    }
}
