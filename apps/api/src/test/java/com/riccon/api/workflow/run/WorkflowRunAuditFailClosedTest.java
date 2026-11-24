package com.riccon.api.workflow.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.repository.AuditRepository;
import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.domain.audit.AuditActions;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

/**
 * 运行创建/取消/重试审计 fail-closed 集成测试（IT-WF-112 的任务级补充，ADR-0007/ADR-0024 §8.2）。
 *
 * <p>只让目标动作的审计写入失败，其他审计保持无语义，避免影响登录与租户创建；断言 HTTP 500 且业务
 * 状态、运行行与节点尝试全部回滚，不产生“有运行但无审计”的无痕变更。
 */
class WorkflowRunAuditFailClosedTest extends WorkflowRunTestSupport {

    /** 抛异常的审计仓储。 */
    @MockitoBean
    private AuditRepository auditRepository;

    /** 创建并发布流程。 */
    private String publishWorkflow(Fixture fixture, String name) {
        String workflowId = createDraft(fixture.owner(), name).path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        publishOk(fixture.tenantAdmin(), workflowId, "TENANT", null, "publish-" + UUID.randomUUID());
        return workflowId;
    }

    @Test
    @DisplayName("IT-WF-112 创建审计失败回滚运行、配额与审计")
    void createRun_failsWhenAuditWriteFails() {
        doThrow(new DataAccessResourceFailureException("audit storage unavailable"))
                .when(auditRepository)
                .append(argThat((AuditEvent event) -> AuditActions.WORKFLOW_RUN_REQUESTED.equals(event.action())));

        Fixture fixture = fixture("wfa");
        String workflowId = publishWorkflow(fixture, "创建审计失败");
        ApiTestClient.ApiResponse response =
                createRun(fixture.owner(), workflowId, null, DEFAULT_INPUTS, "run-audit-001");
        assertThat(response.status()).as(response.body()).isEqualTo(500);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-COMMON-500");
        assertThat(runCount(fixture.tenant())).isZero();
        assertThat(quotaUsed(fixture.tenant(), "TENANT", fixture.tenant(), "DAILY"))
                .isZero();
    }

    @Test
    @DisplayName("IT-WF-112 取消审计失败回滚运行状态与审批")
    void cancelRun_failsWhenAuditWriteFails() {
        Fixture fixture = fixture("wfa");
        String workflowId = publishWorkflow(fixture, "取消审计失败");
        JsonNode created = createRunOk(fixture.owner(), workflowId, null, "run-audit-002");
        UUID runId = UUID.fromString(created.path("id").asText());

        doThrow(new DataAccessResourceFailureException("audit storage unavailable"))
                .when(auditRepository)
                .append(argThat((AuditEvent event) -> AuditActions.WORKFLOW_RUN_CANCELLED.equals(event.action())));
        ApiTestClient.ApiResponse response = cancelRun(fixture.owner(), runId.toString());
        assertThat(response.status()).as(response.body()).isEqualTo(500);
        assertThat(runRow(fixture.tenant(), runId).status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("IT-WF-112 重试审计失败回滚运行与节点尝试")
    void retryRun_failsWhenAuditWriteFails() {
        Fixture fixture = fixture("wfa");
        String workflowId = publishWorkflow(fixture, "重试审计失败");
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID runId = insertFailedRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "draft");

        doThrow(new DataAccessResourceFailureException("audit storage unavailable"))
                .when(auditRepository)
                .append(argThat((AuditEvent event) -> AuditActions.WORKFLOW_RUN_RETRIED.equals(event.action())));
        ApiTestClient.ApiResponse response = retryRun(fixture.owner(), runId.toString(), "draft", "retry-audit-001");
        assertThat(response.status()).as(response.body()).isEqualTo(500);
        assertThat(runRow(fixture.tenant(), runId).status()).isEqualTo("FAILED");
        assertThat(nodeAttemptCount(fixture.tenant(), runId, "draft")).isEqualTo(1);
    }
}
