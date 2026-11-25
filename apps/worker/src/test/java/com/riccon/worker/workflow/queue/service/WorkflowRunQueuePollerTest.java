package com.riccon.worker.workflow.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import com.riccon.worker.workflow.heartbeat.domain.WorkflowLease;
import com.riccon.worker.workflow.heartbeat.service.WorkflowLeaseHeartbeat;
import com.riccon.worker.workflow.queue.config.WorkflowQueueProperties;
import com.riccon.worker.workflow.queue.domain.ClaimedWorkflowRun;
import com.riccon.worker.workflow.queue.domain.WorkflowRunClaimState;
import com.riccon.worker.workflow.queue.domain.WorkflowRunExecutor;
import com.riccon.worker.workflow.queue.repository.WorkflowRunQueueRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 单槽位领取编排：非法租户 fail-closed、重复领取防护、WF_INTERNAL 终态、心跳先于执行器与
 * claim 失败指标（ADR-0024 §4.3，TM-81/TM-85）。
 */
class WorkflowRunQueuePollerTest {

    /** 固定 UTC 时间。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC);

    /** 运行 ID。 */
    private static final UUID RUN_ID = UUID.fromString("01990000-0000-7000-8000-000000000001");

    /** 租户。 */
    private static final TenantId TENANT = TenantId.of("tenant-a");

    /** 队列仓储。 */
    private WorkflowRunQueueRepository repository;

    /** 心跳。 */
    private WorkflowLeaseHeartbeat heartbeat;

    /** 指标注册表。 */
    private SimpleMeterRegistry meterRegistry;

    /** 指标组件。 */
    private WorkflowQueueMetrics metrics;

    /** 审计。 */
    private WorkflowQueueAuditor auditor;

    /** 执行器。 */
    private WorkflowRunExecutor executor;

    /** 被测轮询器。 */
    private WorkflowRunQueuePoller poller;

    /** 测试配置。 */
    private WorkflowQueueProperties properties;

    /** 租约句柄。 */
    private WorkflowLease lease;

    /** 每个测试重建 mock 与被测对象。 */
    @BeforeEach
    void setUp() {
        repository = mock(WorkflowRunQueueRepository.class);
        heartbeat = mock(WorkflowLeaseHeartbeat.class);
        auditor = mock(WorkflowQueueAuditor.class);
        executor = mock(WorkflowRunExecutor.class);
        lease = mock(WorkflowLease.class);
        meterRegistry = new SimpleMeterRegistry();
        metrics = new WorkflowQueueMetrics(meterRegistry, repository);
        properties = new WorkflowQueueProperties(2, 1, 100, 500, 2, 2, 3, 9, true, 5, "test-instance");
        poller = new WorkflowRunQueuePoller(repository, heartbeat, properties, metrics, auditor, CLOCK, executor);
        when(lease.lost()).thenReturn(false);
        when(lease.cancelled()).thenReturn(false);
    }

    /** 合法领取：核对租约后先启动心跳再调用执行器。 */
    @Test
    @DisplayName("先心跳后执行器")
    void validClaim_startsHeartbeatBeforeExecutor() {
        ClaimedWorkflowRun claim = claim("tenant-a", poller.leaseOwner(0));
        when(repository.claim(anyString(), any())).thenReturn(Optional.of(claim));
        when(repository.findClaimState(TENANT, RUN_ID))
                .thenReturn(Optional.of(
                        new WorkflowRunClaimState(WorkflowRunStatus.RUNNING, 0, "start", claim.leaseOwner())));
        when(heartbeat.start(claim)).thenReturn(lease);

        assertThat(poller.pollOnce(0)).isTrue();

        var ordered = inOrder(heartbeat, executor);
        ordered.verify(heartbeat).start(claim);
        ordered.verify(executor).execute(claim, lease);
        verify(lease).close();
        assertThat(metrics.activeSlots()).isZero();
        assertThat(TenantContext.get()).isEmpty();
    }

    /** 空队列不启动心跳/执行器。 */
    @Test
    @DisplayName("空队列直接返回")
    void emptyQueue_doesNothing() {
        when(repository.claim(anyString(), any())).thenReturn(Optional.empty());

        assertThat(poller.pollOnce(0)).isFalse();

        verify(heartbeat, never()).start(any());
        verify(executor, never()).execute(any(), any());
    }

    /** 非法租户 fail-closed：不打开目标租户上下文、不读业务列、不执行。 */
    @Test
    @DisplayName("非法 tenant 拒绝且不执行")
    void invalidTenant_failsClosed() {
        ClaimedWorkflowRun claim = claim("bad tenant!", poller.leaseOwner(0));
        when(repository.claim(anyString(), any())).thenReturn(Optional.of(claim));

        assertThat(poller.pollOnce(0)).isTrue();

        verify(repository, never()).findClaimState(any(), any());
        verify(heartbeat, never()).start(any());
        verify(executor, never()).execute(any(), any());
        verify(auditor).claimDenied(RUN_ID, "bad tenant!");
        assertThat(counter("riccon_workflow_claim_failures_total", "reason", "INVALID_TENANT"))
                .isEqualTo(1.0);
        assertThat(TenantContext.get()).isEmpty();
    }

    /** 租约在领取后已被取消或重领时放弃执行。 */
    @Test
    @DisplayName("重复领取/租约竞争时放弃执行")
    void leaseRace_skipsExecution() {
        ClaimedWorkflowRun claim = claim("tenant-a", poller.leaseOwner(0));
        when(repository.claim(anyString(), any())).thenReturn(Optional.of(claim));
        when(repository.findClaimState(TENANT, RUN_ID))
                .thenReturn(
                        Optional.of(new WorkflowRunClaimState(WorkflowRunStatus.RUNNING, 0, "start", "other-owner")));

        assertThat(poller.pollOnce(0)).isTrue();

        verify(heartbeat, never()).start(any());
        verify(executor, never()).execute(any(), any());
    }

    /** claim_attempts 超过上限时推进 WF_INTERNAL 终态并写审计/指标。 */
    @Test
    @DisplayName("claim 次数耗尽进入 WF_INTERNAL")
    void claimExhausted_marksTerminal() {
        ClaimedWorkflowRun claim = claim("tenant-a", poller.leaseOwner(0));
        when(repository.claim(anyString(), any())).thenReturn(Optional.of(claim));
        when(repository.findClaimState(TENANT, RUN_ID))
                .thenReturn(Optional.of(
                        new WorkflowRunClaimState(WorkflowRunStatus.RUNNING, 6, "model", claim.leaseOwner())));
        when(repository.markClaimExhausted(eq(TENANT), eq(RUN_ID), eq(claim.leaseOwner()), eq("model"), any()))
                .thenReturn(true);

        assertThat(poller.pollOnce(0)).isTrue();

        verify(repository).markClaimExhausted(TENANT, RUN_ID, claim.leaseOwner(), "model", CLOCK.instant());
        verify(auditor).claimExhausted(TENANT, RUN_ID, "model");
        verify(heartbeat, never()).start(any());
        verify(executor, never()).execute(any(), any());
        assertThat(counter("riccon_workflow_claim_exhausted_total")).isEqualTo(1.0);
    }

    /** 心跳首轮续期即丢失时不执行。 */
    @Test
    @DisplayName("心跳丢失时不执行")
    void leaseLost_skipsExecution() {
        ClaimedWorkflowRun claim = claim("tenant-a", poller.leaseOwner(0));
        when(repository.claim(anyString(), any())).thenReturn(Optional.of(claim));
        when(repository.findClaimState(TENANT, RUN_ID))
                .thenReturn(Optional.of(
                        new WorkflowRunClaimState(WorkflowRunStatus.RUNNING, 0, "start", claim.leaseOwner())));
        when(lease.lost()).thenReturn(true);
        when(heartbeat.start(claim)).thenReturn(lease);

        assertThat(poller.pollOnce(0)).isTrue();

        verify(executor, never()).execute(any(), any());
        verify(lease).close();
    }

    /** 执行异常不逃逸到调度线程，槽位占用恢复。 */
    @Test
    @DisplayName("执行异常被隔离")
    void executorException_isIsolated() {
        ClaimedWorkflowRun claim = claim("tenant-a", poller.leaseOwner(0));
        when(repository.claim(anyString(), any())).thenReturn(Optional.of(claim));
        when(repository.findClaimState(TENANT, RUN_ID))
                .thenReturn(Optional.of(
                        new WorkflowRunClaimState(WorkflowRunStatus.RUNNING, 0, "start", claim.leaseOwner())));
        when(heartbeat.start(claim)).thenReturn(lease);
        org.mockito.Mockito.doThrow(new IllegalStateException("engine failed"))
                .when(executor)
                .execute(any(), any());

        assertThat(poller.pollOnce(0)).isTrue();

        assertThat(metrics.activeSlots()).isZero();
        verify(lease).close();
    }

    /** 数据库领取异常记 DB_ERROR 并返回未领取。 */
    @Test
    @DisplayName("领取数据库异常计 DB_ERROR")
    void claimDatabaseFailure_countsDbError() {
        when(repository.claim(anyString(), any())).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(poller.pollOnce(0)).isFalse();

        assertThat(counter("riccon_workflow_claim_failures_total", "reason", "DB_ERROR"))
                .isEqualTo(1.0);
    }

    /** 执行器未注册时不领取任何运行。 */
    @Test
    @DisplayName("执行器缺失时不领取")
    void executorMissing_doesNotClaim() {
        WorkflowRunQueuePoller withoutExecutor = new WorkflowRunQueuePoller(
                repository, heartbeat, properties, metrics, auditor, CLOCK, (ObjectProvider<WorkflowRunExecutor>) null);

        assertThat(withoutExecutor.executorAvailable()).isFalse();
        assertThat(withoutExecutor.pollOnce(0)).isFalse();
        verify(repository, never()).claim(anyString(), any());
    }

    /**
     * 构造领取条目。
     *
     * @param tenant 原始租户
     * @param owner 租约持有者
     * @return 条目
     */
    private static ClaimedWorkflowRun claim(String tenant, String owner) {
        return new ClaimedWorkflowRun(
                RUN_ID, tenant, WorkflowRunStatus.PENDING, CLOCK.instant().plusSeconds(9), owner);
    }

    /**
     * 读取 Counter。
     *
     * @param name 指标名
     * @param tagKey 标签键
     * @param tagValue 标签值
     * @return 计数
     */
    private double counter(String name, String tagKey, String tagValue) {
        Counter counter = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0.0 : counter.count();
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
