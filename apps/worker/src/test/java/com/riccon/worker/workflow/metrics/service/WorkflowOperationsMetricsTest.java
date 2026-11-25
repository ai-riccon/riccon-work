package com.riccon.worker.workflow.metrics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.riccon.worker.workflow.config.WorkflowRuntimeProperties;
import com.riccon.worker.workflow.metrics.domain.WorkflowApprovalWaitSnapshot;
import com.riccon.worker.workflow.metrics.repository.WorkflowApprovalWaitRepository;
import com.riccon.worker.workflow.queue.config.WorkflowQueueProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Worker 编排运维指标名称与降级行为（OPS-M06-003/OPS-M06-007/OPS-M06-009）。
 *
 * <p>锁定 RUNBOOK 依赖的指标名：approvals_pending、approval_oldest_wait_seconds、
 * feature_enabled、claim_enabled。审批聚合读取失败时 Gauge 必须返回 0 而不是让 Prometheus 抓取失败。
 */
class WorkflowOperationsMetricsTest {

    /** 默认运行限制。 */
    private static final WorkflowRuntimeProperties RUNTIME = new WorkflowRuntimeProperties(
            true, "", 50, 200, 300, 120, 60, 30, 10, 5, 5_242_880L, 180);

    /** 默认队列配置（claim 开启）。 */
    private static final WorkflowQueueProperties QUEUE =
            new WorkflowQueueProperties(10, 1, 1_000L, 5_000L, 10, 10, 30, 90, true, 120, "it");

    /** 审批 Gauge 输出待审批数与最老等待。 */
    @Test
    @DisplayName("审批等待 Gauge 输出计数与最老等待秒数")
    void approvalGauges_exposePendingAndOldestWait() {
        WorkflowApprovalWaitRepository repository = mock(WorkflowApprovalWaitRepository.class);
        when(repository.waitStats()).thenReturn(new WorkflowApprovalWaitSnapshot(3L, 42L));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new WorkflowOperationsMetrics(registry, repository, RUNTIME, QUEUE);

        assertThat(registry.get("riccon_workflow_approvals_pending").gauge().value())
                .isEqualTo(3.0);
        assertThat(registry.get("riccon_workflow_approval_oldest_wait_seconds")
                        .gauge()
                        .value())
                .isEqualTo(42.0);
    }

    /** feature/claim 开关 Gauge 反映部署值。 */
    @Test
    @DisplayName("feature/claim 开关 Gauge 反映部署开关")
    void switchGauges_reflectDeploymentFlags() {
        WorkflowApprovalWaitRepository repository = mock(WorkflowApprovalWaitRepository.class);
        when(repository.waitStats()).thenReturn(WorkflowApprovalWaitSnapshot.EMPTY);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new WorkflowOperationsMetrics(
                registry,
                repository,
                new WorkflowRuntimeProperties(false, "演练", 50, 200, 300, 120, 60, 30, 10, 5, 5_242_880L, 180),
                new WorkflowQueueProperties(10, 1, 1_000L, 5_000L, 10, 10, 30, 90, false, 120, "it"));

        assertThat(registry.get("riccon_workflow_feature_enabled").gauge().value())
                .isZero();
        assertThat(registry.get("riccon_workflow_claim_enabled").gauge().value())
                .isZero();
    }

    /** 审批聚合失败时保留空快照并继续返回 Gauge，不破坏抓取。 */
    @Test
    @DisplayName("审批聚合失败不破坏指标抓取")
    void approvalFailure_keepsGaugeReadable() {
        WorkflowApprovalWaitRepository repository = mock(WorkflowApprovalWaitRepository.class);
        when(repository.waitStats()).thenThrow(new IllegalStateException("db down"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new WorkflowOperationsMetrics(registry, repository, RUNTIME, QUEUE);

        assertThat(registry.get("riccon_workflow_approvals_pending").gauge().value())
                .isZero();
        assertThat(registry.get("riccon_workflow_approval_oldest_wait_seconds")
                        .gauge()
                        .value())
                .isZero();
        assertThat(registry.get("riccon_workflow_feature_enabled").gauge().value())
                .isEqualTo(1.0);
    }
}
