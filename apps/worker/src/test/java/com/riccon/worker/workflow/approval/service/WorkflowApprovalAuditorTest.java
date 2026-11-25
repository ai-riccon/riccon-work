package com.riccon.worker.workflow.approval.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalDecision;
import com.riccon.worker.audit.domain.AuditEvent;
import com.riccon.worker.audit.repository.AuditRepository;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 审批审计 fail-closed 语义（ADR-0024 §8.2）。
 *
 * <p>审计仓储异常必须向上抛出，不能在审计组件内吞掉；调用方事务据此回滚审批停车/决定。
 * 决定理由超过 500 字符时在落库前被白名单拒绝，不产生审计写入。
 */
class WorkflowApprovalAuditorTest {

    /** 审计写入失败必须传播。 */
    @Test
    @DisplayName("IT-WF-108 审批请求审计失败向上抛出")
    void requested_propagatesAuditFailure() {
        AuditRepository repository = mock(AuditRepository.class);
        doThrow(new DataAccessResourceFailureException("audit unavailable"))
                .when(repository)
                .append(any(AuditEvent.class));
        WorkflowApprovalAuditor auditor = new WorkflowApprovalAuditor(repository, Clock.systemUTC());

        assertThatThrownBy(() -> auditor.requested(
                        TenantId.of("tenant-audit"),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "approval",
                        1,
                        WorkflowApprovalActionKind.CONTINUE,
                        null,
                        "workflow-run-trace"))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    /** 超长理由在写审计前被拒绝。 */
    @Test
    @DisplayName("IT-WF-108 超长决定理由不写审计")
    void decided_rejectsTooLongCommentBeforeAudit() {
        AuditRepository repository = mock(AuditRepository.class);
        WorkflowApprovalAuditor auditor = new WorkflowApprovalAuditor(repository, Clock.systemUTC());

        assertThatThrownBy(() -> auditor.decided(
                        TenantId.of("tenant-audit"),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "approval",
                        WorkflowApprovalDecision.REJECT,
                        UUID.randomUUID(),
                        null,
                        "理".repeat(501),
                        "workflow-run-trace"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }
}
