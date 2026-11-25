package com.riccon.api.workflow.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.workflow.approval.domain.WorkflowApprovalActor;
import com.riccon.api.workflow.approval.domain.WorkflowApprovalDecisionCommand;
import com.riccon.api.workflow.approval.service.WorkflowApprovalForbiddenException;
import com.riccon.api.workflow.approval.service.WorkflowApprovalService;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.workflow.WorkflowApprovalDecision;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 审批人账号停用的服务层补证（SEC-WF-001）。
 *
 * <p>HTTP 路径上，账号被停用后会话在下一请求即 401（T03/AuthFlowTest），因此 403
 * {@code APPROVER_INACTIVE} 只可能在“认证通过后、决定落库前账号被停用”的竞态窗口出现。这里直接调用真实
 * 服务（真实 MySQL、真实审计）验证该分支：403 + {@code outcome=DENIED} 审计，审批与运行保持等待。
 */
class WorkflowApprovalInactiveApproverTest extends WorkflowApprovalTestSupport {

    /** 审批服务。 */
    @Autowired
    private WorkflowApprovalService service;

    @Test
    @DisplayName("SEC-WF-001 停用账号决定返回 403 并写 denied 审计")
    void decide_inactiveApprover_forbiddenAndAudited() {
        Fixture fixture = fixture("wap");
        String workflowId = publishApprovalWorkflow(fixture, "停用审批人");
        UUID approvalId = seedAwaitingApproval(
                fixture,
                workflowId,
                fixture.ownerId(),
                "ROLE",
                DEFAULT_APPROVER_ROLE,
                null,
                Instant.now().minusSeconds(20));
        UUID runId = approvalRunId(fixture.tenant(), approvalId);

        LoginSession disabledEditor = seedAndLoginLocalUser(
                fixture.platform(),
                fixture.tenant(),
                "disabled" + UUID.randomUUID().toString().substring(0, 8),
                "disabled-password-12",
                List.of("WORKFLOW_EDITOR"));
        UUID disabledEditorId = actorId(disabledEditor);
        disableUser(fixture, disabledEditorId);

        WorkflowApprovalDecisionCommand command =
                new WorkflowApprovalDecisionCommand(WorkflowApprovalDecision.APPROVE, "停用");
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(fixture.tenant()))) {
            assertThatThrownBy(() -> service.decide(
                            new WorkflowApprovalActor(disabledEditorId, fixture.tenant(), true),
                            approvalId,
                            command,
                            "trace-inactive",
                            "request-inactive"))
                    .isInstanceOfSatisfying(
                            WorkflowApprovalForbiddenException.class,
                            exception -> assertThat(exception.reason())
                                    .isEqualTo(WorkflowApprovalForbiddenException.Reason.APPROVER_INACTIVE));
        }

        assertThat(approvalRow(fixture.tenant(), approvalId).status()).isEqualTo("PENDING");
        assertThat(runRow(fixture.tenant(), runId).status()).isEqualTo("WAITING_APPROVAL");
        List<AuditRow> audits = tenantAuditRows(fixture.tenant(), AuditActions.WORKFLOW_APPROVAL_DECIDED);
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().outcome()).isEqualTo("DENIED");
        JsonNode details = auditDetails(audits.getFirst());
        assertThat(details.path("approverUserId").asText()).isEqualTo(disabledEditorId.toString());
        assertThat(details.path("decision").asText()).isEqualTo("APPROVE");
        assertThat(details.has("decisionComment")).isFalse();
    }
}
