package com.riccon.worker.workflow.heartbeat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import com.riccon.worker.workflow.heartbeat.domain.WorkflowLease;
import com.riccon.worker.workflow.queue.config.WorkflowQueueProperties;
import com.riccon.worker.workflow.queue.domain.ClaimedWorkflowRun;
import com.riccon.worker.workflow.queue.repository.WorkflowRunLeaseRepository;
import com.riccon.worker.workflow.queue.service.WorkflowQueueMetrics;
import com.riccon.worker.workflow.queue.support.RecordingTaskScheduler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 租约心跳：领取后立即续期、间隔 = lease/3、丢失/取消/续期异常的状态机（ADR-0024 §4.3）。
 *
 * <p>心跳只在 {@code status='RUNNING'} 且租约持有者匹配时成功；返回 0 时区分取消与丢失，
 * 异常 fail-closed 视为丢失。测试用记录型调度器驱动续期，不依赖真实时间。
 */
class WorkflowLeaseHeartbeatTest {

    /** 运行 ID。 */
    private static final UUID RUN_ID = UUID.fromString("01990000-0000-7000-8000-000000000002");

    /** 租户。 */
    private static final TenantId TENANT = TenantId.of("tenant-a");

    /** 租约持有者。 */
    private static final String OWNER = "test-instance#slot-0";

    /** 租约仓储。 */
    private WorkflowRunLeaseRepository repository;

    /** 指标注册表。 */
    private SimpleMeterRegistry meterRegistry;

    /** 指标。 */
    private WorkflowQueueMetrics metrics;

    /** 记录型心跳池。 */
    private RecordingTaskScheduler scheduler;

    /** 被测心跳。 */
    private WorkflowLeaseHeartbeat heartbeat;

    /** 领取条目。 */
    private ClaimedWorkflowRun claim;

    /** 每个测试重建依赖。 */
    @BeforeEach
    void setUp() {
        repository = mock(WorkflowRunLeaseRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        metrics = new WorkflowQueueMetrics(
                meterRegistry, mock(com.riccon.worker.workflow.queue.repository.WorkflowRunQueueRepository.class));
        scheduler = new RecordingTaskScheduler();
        WorkflowQueueProperties properties =
                new WorkflowQueueProperties(2, 1, 1000, 5000, 2, 2, 30, 90, true, 5, "test-instance");
        heartbeat = new WorkflowLeaseHeartbeat(repository, metrics, properties, scheduler);
        claim = new ClaimedWorkflowRun(
                RUN_ID, TENANT.value(), WorkflowRunStatus.PENDING, Instant.parse("2026-11-25T00:00:09Z"), OWNER);
    }

    /** 领取后同步首次续期，随后按 30s 调度下一次。 */
    @Test
    @DisplayName("领取后立即续期并安排 30s 心跳")
    void start_renewsImmediatelyThenSchedules() {
        when(repository.renew(TENANT, RUN_ID, OWNER, Duration.ofSeconds(90))).thenReturn(1);

        WorkflowLease lease = heartbeat.start(claim);

        verify(repository).renew(TENANT, RUN_ID, OWNER, Duration.ofSeconds(90));
        assertThat(scheduler.tasks()).hasSize(1);
        assertThat(scheduler.last().delayMillis()).isEqualTo(30_000L);
        assertThat(lease.lost()).isFalse();
        assertThat(lease.cancelled()).isFalse();
        assertThat(lease.stopped()).isFalse();
        assertThat(TenantContext.get()).isEmpty();
    }

    /** 续期返回 0 且仍 RUNNING：租约丢失，停止续期并计数。 */
    @Test
    @DisplayName("续期 0 行且运行中视为租约丢失")
    void renewZero_running_marksLost() {
        when(repository.renew(TENANT, RUN_ID, OWNER, Duration.ofSeconds(90))).thenReturn(0);
        when(repository.findStatus(TENANT, RUN_ID)).thenReturn(Optional.of(WorkflowRunStatus.RUNNING));

        WorkflowLease lease = heartbeat.start(claim);

        assertThat(lease.lost()).isTrue();
        assertThat(lease.stopped()).isTrue();
        assertThat(scheduler.tasks()).isEmpty();
        assertThat(counter("riccon_workflow_lease_lost_total")).isEqualTo(1.0);
        assertThat(counter("riccon_workflow_heartbeat_renew_failures_total")).isEqualTo(1.0);
    }

    /** 续期返回 0 且运行已取消：置 cancelled，不算租约丢失。 */
    @Test
    @DisplayName("续期 0 行且运行取消时置 cancelled")
    void renewZero_cancelled_marksCancelled() {
        when(repository.renew(TENANT, RUN_ID, OWNER, Duration.ofSeconds(90))).thenReturn(0);
        when(repository.findStatus(TENANT, RUN_ID)).thenReturn(Optional.of(WorkflowRunStatus.CANCELLED));

        WorkflowLease lease = heartbeat.start(claim);

        assertThat(lease.cancelled()).isTrue();
        assertThat(lease.lost()).isFalse();
        assertThat(scheduler.tasks()).isEmpty();
        assertThat(counter("riccon_workflow_lease_lost_total")).isZero();
        assertThat(counter("riccon_workflow_heartbeat_renew_failures_total")).isEqualTo(1.0);
    }

    /** 续期异常 fail-closed 视为丢失，不等待下一次续期。 */
    @Test
    @DisplayName("续期异常视为租约丢失")
    void renewException_marksLost() {
        when(repository.renew(eq(TENANT), eq(RUN_ID), eq(OWNER), any()))
                .thenThrow(new DataAccessResourceFailureException("down"));

        WorkflowLease lease = heartbeat.start(claim);

        assertThat(lease.lost()).isTrue();
        assertThat(counter("riccon_workflow_lease_lost_total")).isEqualTo(1.0);
        assertThat(counter("riccon_workflow_heartbeat_renew_failures_total")).isEqualTo(1.0);
    }

    /** 定时续期成功会继续自调度；关闭后不再续期。 */
    @Test
    @DisplayName("定时续期持续自调度，关闭后停止")
    void scheduledRenewal_continuesUntilClosed() {
        when(repository.renew(TENANT, RUN_ID, OWNER, Duration.ofSeconds(90))).thenReturn(1);
        WorkflowLease lease = heartbeat.start(claim);

        scheduler.runLast();
        assertThat(scheduler.tasks()).hasSize(2);
        verify(repository, org.mockito.Mockito.times(2)).renew(TENANT, RUN_ID, OWNER, Duration.ofSeconds(90));

        lease.close();
        assertThat(lease.stopped()).isTrue();
        scheduler.runLast();
        verify(repository, org.mockito.Mockito.times(2)).renew(TENANT, RUN_ID, OWNER, Duration.ofSeconds(90));
        assertThat(scheduler.tasks()).hasSize(2);
    }

    /** 运行中途租约被重领：心跳置 lost 并停止。 */
    @Test
    @DisplayName("运行中租约被重领时置 lost")
    void lostMidRun_stopsHeartbeat() {
        when(repository.renew(TENANT, RUN_ID, OWNER, Duration.ofSeconds(90))).thenReturn(1, 0);
        when(repository.findStatus(TENANT, RUN_ID)).thenReturn(Optional.of(WorkflowRunStatus.RUNNING));
        WorkflowLease lease = heartbeat.start(claim);

        scheduler.runLast();

        assertThat(lease.lost()).isTrue();
        assertThat(counter("riccon_workflow_lease_lost_total")).isEqualTo(1.0);
        // 第一次同步续期 + 一次定时续期；丢失后不再安排。
        assertThat(scheduler.tasks()).hasSize(1);
        verify(repository, org.mockito.Mockito.times(2)).renew(TENANT, RUN_ID, OWNER, Duration.ofSeconds(90));
    }

    /**
     * 读取无标签 Counter。
     *
     * @param name 指标名
     * @return 计数
     */
    private double counter(String name) {
        Counter counter = meterRegistry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
