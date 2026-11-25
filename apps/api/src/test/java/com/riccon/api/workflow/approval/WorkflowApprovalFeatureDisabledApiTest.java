package com.riccon.api.workflow.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.domain.audit.AuditActions;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * 编排关闭时的审批行为（ADR-0024 第 10.1 节、AC-23 关闭演练的审批面）。
 *
 * <p>关闭开关只拒绝审批决定（503 {@code RICCON-WF-503}）；待办与详情保持只读，便于运维排障与取消
 * 在途运行。关闭原因来自部署配置，原样出现在 ProblemDetail 说明中。
 */
@TestPropertySource(properties = {"riccon.workflow.enabled=false", "riccon.workflow.disabled-reason=只读演练"})
class WorkflowApprovalFeatureDisabledApiTest extends WorkflowApprovalTestSupport {

    @Test
    @DisplayName("编排关闭时审批决定 503，待办与详情只读")
    void decision_whenDisabled_returns503_butReadsStayAvailable() {
        Fixture fixture = fixture("wap");
        String workflowId = publishApprovalWorkflow(fixture, "关闭审批");
        UUID approvalId = seedAwaitingApproval(
                fixture,
                workflowId,
                fixture.ownerId(),
                "ROLE",
                DEFAULT_APPROVER_ROLE,
                null,
                Instant.now().minusSeconds(20));

        JsonNode inbox = client.json(listApprovals(fixture.otherEditor(), null));
        assertThat(inbox.path("total").asLong()).isEqualTo(1);
        assertThat(getApproval(fixture.otherEditor(), approvalId).status()).isEqualTo(200);

        ApiTestClient.ApiResponse decision =
                decideApproval(fixture.otherEditor(), approvalId, "APPROVE", "关闭", "decision-disabled-001");
        assertProblem(decision, 503, WF_503);
        assertThat(client.json(decision).path("detail").asText()).contains("只读演练");
        assertThat(approvalRow(fixture.tenant(), approvalId).status()).isEqualTo("PENDING");
        assertThat(tenantAuditRows(fixture.tenant(), AuditActions.WORKFLOW_APPROVAL_DECIDED))
                .isEmpty();
    }
}
