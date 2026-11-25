package com.riccon.worker.workflow.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.worker.audit.domain.AuditEvent;
import com.riccon.worker.audit.domain.WorkerAuditActions;
import com.riccon.worker.audit.repository.AuditRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 运行队列审计：非法租户写 platform denied；WF_INTERNAL 写 workflow.run.failed。
 *
 * <p>锁定租户归属、目标类型、结果枚举与 details 键，防止 TM-85 拒绝路径漏审计或写入业务内容。
 */
class WorkflowQueueAuditorTest {

    /** 运行 ID。 */
    private static final UUID RUN_ID = UUID.fromString("01990000-0000-7000-8000-000000000003");

    /** 固定 UTC 时间。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC);

    /** 非法租户拒绝写 platform + DENIED，details 只含尝试租户与原因。 */
    @Test
    @DisplayName("非法租户写 platform denied 审计")
    void claimDenied_writesPlatformDenied() {
        AuditRepository repository = mock(AuditRepository.class);
        WorkflowQueueAuditor auditor = new WorkflowQueueAuditor(repository, CLOCK);

        auditor.claimDenied(RUN_ID, "bad tenant!");

        AuditEvent event = capture(repository);
        assertThat(event.tenantId()).isEqualTo("platform");
        assertThat(event.action()).isEqualTo(WorkerAuditActions.TENANT_ACCESS_DENIED);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(event.targetType()).isEqualTo(AuditTargetType.TENANT);
        assertThat(event.traceId()).isEqualTo("workflow-claim-" + RUN_ID);
        assertThat(event.details())
                .containsEntry("attemptedTenant", "bad tenant!")
                .containsEntry("reason", "INVALID_TENANT");
        assertThat(TenantContext.get()).isEmpty();
    }

    /** WF_INTERNAL 写租户内 FAILURE，details 带错误码与节点。 */
    @Test
    @DisplayName("WF_INTERNAL 写运行失败审计")
    void claimExhausted_writesRunFailed() {
        AuditRepository repository = mock(AuditRepository.class);
        WorkflowQueueAuditor auditor = new WorkflowQueueAuditor(repository, CLOCK);

        auditor.claimExhausted(TenantId.of("tenant-a"), RUN_ID, "model");

        AuditEvent event = capture(repository);
        assertThat(event.tenantId()).isEqualTo("tenant-a");
        assertThat(event.action()).isEqualTo(AuditActions.WORKFLOW_RUN_FAILED);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(event.targetType()).isEqualTo(AuditTargetType.WORKFLOW_RUN);
        assertThat(event.targetId()).isEqualTo(RUN_ID.toString());
        assertThat(event.details())
                .containsEntry("workflowRunId", RUN_ID.toString())
                .containsEntry("errorCode", "WF_INTERNAL")
                .containsEntry("failedNodeId", "model");
        assertThat(TenantContext.get()).isEmpty();
    }

    /**
     * 捕获写入事件。
     *
     * @param repository 仓储 mock
     * @return 事件
     */
    private static AuditEvent capture(AuditRepository repository) {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).append(captor.capture());
        return captor.getValue();
    }
}
