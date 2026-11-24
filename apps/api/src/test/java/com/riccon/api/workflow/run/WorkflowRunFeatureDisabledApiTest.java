package com.riccon.api.workflow.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * 编排关闭（kill switch）运行端点集成测试（IT-WF-110，AC-23/ADR-0024 §4.8）。
 *
 * <p>独立上下文以 {@code RICCON_WORKFLOW_ENABLED=false} 启动：新运行与重试返回 503
 * {@code RICCON-WF-503} 且带部署关闭原因；取消仍可用，否则关闭开关后无法清空在途运行。
 */
@TestPropertySource(
        properties = {
            "riccon.workflow.enabled=false",
            "riccon.workflow.disabled-reason=只读演练"
        })
class WorkflowRunFeatureDisabledApiTest extends WorkflowRunTestSupport {

    /** 创建并发布流程（发布不受 kill switch 限制）。 */
    private String publishWorkflow(Fixture fixture, String name) {
        String workflowId = createDraft(fixture.owner(), name).path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        publishOk(fixture.tenantAdmin(), workflowId, "TENANT", null, "publish-" + UUID.randomUUID());
        return workflowId;
    }

    @Test
    @DisplayName("IT-WF-110 关闭开关时创建运行返回 503 且带关闭原因")
    void createRun_whenDisabled_returns503() {
        Fixture fixture = fixture("wfd");
        String workflowId = publishWorkflow(fixture, "关闭态运行");

        ApiTestClient.ApiResponse response =
                createRun(fixture.owner(), workflowId, null, DEFAULT_INPUTS, "run-disabled-001");
        assertThat(response.status()).as(response.body()).isEqualTo(503);
        var problem = client.json(response);
        assertThat(problem.path("code").asText()).isEqualTo(WF_503);
        assertThat(problem.path("detail").asText()).isEqualTo("只读演练");
        assertThat(runCount(fixture.tenant())).isZero();
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.requested")).isEmpty();
    }

    @Test
    @DisplayName("IT-WF-110 关闭开关时重试返回 503，运行保持 FAILED")
    void retryRun_whenDisabled_returns503() {
        Fixture fixture = fixture("wfd");
        String workflowId = publishWorkflow(fixture, "关闭态重试");
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID runId =
                insertFailedRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "draft");

        ApiTestClient.ApiResponse response =
                retryRun(fixture.owner(), runId.toString(), "draft", "retry-disabled-001");
        assertThat(response.status()).as(response.body()).isEqualTo(503);
        assertThat(client.json(response).path("code").asText()).isEqualTo(WF_503);
        assertThat(runRow(fixture.tenant(), runId).status()).isEqualTo("FAILED");
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.retried")).isEmpty();
    }

    @Test
    @DisplayName("IT-WF-110 关闭开关时取消仍可用")
    void cancelRun_whenDisabled_stillWorks() {
        Fixture fixture = fixture("wfd");
        String workflowId = publishWorkflow(fixture, "关闭态取消");
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID runId = insertActiveRun(
                fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "PENDING", "start");

        ApiTestClient.ApiResponse response = cancelRun(fixture.owner(), runId.toString());
        assertThat(response.status()).as(response.body()).isEqualTo(202);
        assertThat(runRow(fixture.tenant(), runId).status()).isEqualTo("CANCELLED");
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.cancelled")).hasSize(1);
    }
}
