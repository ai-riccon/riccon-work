package com.riccon.api.workflow.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import com.riccon.api.workflow.run.WorkflowRunTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * 编排关闭（kill switch）下的运行限制与取消集成测试（IT-WF-110，AC-23/ADR-0024 §4.8）。
 *
 * <p>独立上下文以 {@code RICCON_WORKFLOW_ENABLED=false} 启动：运行限制仍可读并展示关闭原因，创建运行
 * 返回 503 {@code RICCON-WF-503}，取消保持可用（否则关闭开关后无法清空在途运行）。
 *
 * <p>运行历史/详情读取端点在 T08 交付，本任务不重复实现；关闭态“历史只读”由 T08 的端点测试补充。
 */
@TestPropertySource(properties = {"riccon.workflow.enabled=false", "riccon.workflow.disabled-reason=只读演练"})
class WorkflowRuntimeLimitsDisabledApiTest extends WorkflowRunTestSupport {

    /** 关闭态运行限制仍可读，并返回部署原因。 */
    @Test
    @DisplayName("IT-WF-110 关闭开关时运行限制可读且带关闭原因")
    void limits_whenDisabled_remainsReadable() {
        Fixture fixture = fixture("wflimitsdis");

        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workflows/runtime-limits", fixture.member().sessionCookie(), Map.of());

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        var limits = client.json(response);
        assertThat(limits.path("featureEnabled").asBoolean()).isFalse();
        assertThat(limits.path("disabledReason").asText()).isEqualTo("只读演练");
        // 关闭态下限制值仍为部署配置，不因开关变化而改写。
        assertThat(limits.path("maxNodes").asInt()).isEqualTo(50);
    }

    /** 关闭开关时创建运行 503，且不写运行/审计。 */
    @Test
    @DisplayName("IT-WF-110 关闭开关时创建运行返回 503")
    void createRun_whenDisabled_returns503() {
        Fixture fixture = fixture("wflimitsdis2");
        String workflowId = publishWorkflow(fixture, "关闭态运行限制");

        ApiTestClient.ApiResponse response =
                createRun(fixture.owner(), workflowId, null, DEFAULT_INPUTS, "run-disabled-" + UUID.randomUUID());

        assertThat(response.status()).as(response.body()).isEqualTo(503);
        assertThat(client.json(response).path("code").asText()).isEqualTo(WF_503);
        assertThat(runCount(fixture.tenant())).isZero();
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.requested")).isEmpty();
    }

    /** 关闭开关时取消仍可用，保证回滚能清空在途运行。 */
    @Test
    @DisplayName("IT-WF-110 关闭开关时取消仍可用")
    void cancelRun_whenDisabled_stillWorks() {
        Fixture fixture = fixture("wflimitsdis3");
        String workflowId = publishWorkflow(fixture, "关闭态取消");
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID runId = insertActiveRun(
                fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "WAITING_APPROVAL", "approval");

        ApiTestClient.ApiResponse response = cancelRun(fixture.owner(), runId.toString());

        assertThat(response.status()).as(response.body()).isEqualTo(202);
        assertThat(runRow(fixture.tenant(), runId).status()).isEqualTo("CANCELLED");
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.cancelled")).hasSize(1);
    }

    /** 关闭开关时重试 503，运行保持 FAILED。 */
    @Test
    @DisplayName("IT-WF-110 关闭开关时重试返回 503")
    void retryRun_whenDisabled_returns503() {
        Fixture fixture = fixture("wflimitsdis4");
        String workflowId = publishWorkflow(fixture, "关闭态重试");
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID runId = insertFailedRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "draft");

        ApiTestClient.ApiResponse response =
                retryRun(fixture.owner(), runId.toString(), "draft", "retry-disabled-" + UUID.randomUUID());

        assertThat(response.status()).as(response.body()).isEqualTo(503);
        assertThat(client.json(response).path("code").asText()).isEqualTo(WF_503);
        assertThat(runRow(fixture.tenant(), runId).status()).isEqualTo("FAILED");
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.retried")).isEmpty();
    }

    /**
     * 创建并发布流程；发布不受 kill switch 限制（ADR-0024 §4.8 只关闭新受理动作）。
     *
     * @param fixture 测试夹具
     * @param name 流程名
     * @return 流程 ID
     */
    private String publishWorkflow(Fixture fixture, String name) {
        String workflowId = createDraft(fixture.owner(), name).path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        publishOk(fixture.tenantAdmin(), workflowId, "TENANT", null, "publish-" + UUID.randomUUID());
        return workflowId;
    }
}
