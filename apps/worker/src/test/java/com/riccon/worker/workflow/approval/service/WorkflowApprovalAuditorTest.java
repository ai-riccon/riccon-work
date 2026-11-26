package com.riccon.worker.workflow.approval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalDecision;
import com.riccon.worker.audit.domain.AuditEvent;
import com.riccon.worker.audit.repository.AuditRepository;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 审批审计 fail-closed 语义（ADR-0024 §8.2、DEC-0050=B）。
 *
 * <p>审计仓储异常必须向上抛出，不能在审计组件内吞掉；调用方事务据此回滚审批停车/决定。
 * 决定审计只写理由哈希，不写明文 {@code decisionComment}。
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

    /** 决定审计只写哈希，不写明文理由。 */
    @Test
    @DisplayName("U-05 决定审计 details 只写 commentSha256")
    void decided_writesCommentHashOnly() {
        AuditRepository repository = mock(AuditRepository.class);
        WorkflowApprovalAuditor auditor = new WorkflowApprovalAuditor(repository, Clock.systemUTC());
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);

        auditor.decided(
                TenantId.of("tenant-audit"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "approval",
                WorkflowApprovalDecision.REJECT,
                UUID.randomUUID(),
                null,
                "不合规",
                "workflow-run-trace");

        verify(repository).append(captor.capture());
        assertThat(captor.getValue().details())
                .containsEntry("commentSha256", WorkflowApprovalCommentHash.sha256("不合规"))
                .doesNotContainKey("decisionComment");
    }

    /** 决定 details 在写库前仍走白名单；异常直接抛出。 */
    @Test
    @DisplayName("U-05 决定审计写入失败时向上抛出且不吞异常")
    void decided_propagatesAuditFailure() {
        AuditRepository repository = mock(AuditRepository.class);
        doThrow(new DataAccessResourceFailureException("audit unavailable"))
                .when(repository)
                .append(any(AuditEvent.class));
        WorkflowApprovalAuditor auditor = new WorkflowApprovalAuditor(repository, Clock.systemUTC());

        assertThatThrownBy(() -> auditor.decided(
                        TenantId.of("tenant-audit"),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "approval",
                        WorkflowApprovalDecision.APPROVE,
                        UUID.randomUUID(),
                        null,
                        "同意",
                        "workflow-run-trace"))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    /** 无理由时没有可写 hash；denied 审计不写 hash 也不写明文。 */
    @Test
    @DisplayName("U-05 拒绝尝试审计不带理由或 hash")
    void denied_omitsReasonFields() {
        AuditRepository repository = mock(AuditRepository.class);
        WorkflowApprovalAuditor auditor = new WorkflowApprovalAuditor(repository, Clock.systemUTC());
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);

        auditor.denied(
                TenantId.of("tenant-audit"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "approval",
                WorkflowApprovalDecision.APPROVE,
                UUID.randomUUID(),
                null,
                "workflow-run-trace");

        verify(repository).append(captor.capture());
        assertThat(captor.getValue().details()).doesNotContainKeys("decisionComment", "commentSha256");
    }
}
