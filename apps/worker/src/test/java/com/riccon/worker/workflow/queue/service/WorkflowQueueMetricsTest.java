package com.riccon.worker.workflow.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.riccon.worker.workflow.queue.domain.WorkflowRunQueueStats;
import com.riccon.worker.workflow.queue.repository.WorkflowRunQueueRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M06 运行队列指标名称、标签与槽位占用（ADR-0024 §10.2）。
 *
 * <p>使用 {@link SimpleMeterRegistry} 直接读取 Gauge/Counter，锁定 RUNBOOK 依赖的指标名与
 * {@code status}/{@code reason} 标签，避免后续重构改名导致告警失效。
 */
class WorkflowQueueMetricsTest {

    /** queue_depth 按状态分标签，oldest_pending_age 取秒。 */
    @Test
    @DisplayName("队列 Gauge 输出深度与最老 PENDING 年龄")
    void gauges_exposeDepthAndOldestAge() {
        WorkflowRunQueueRepository repository = mock(WorkflowRunQueueRepository.class);
        when(repository.queueStats()).thenReturn(new WorkflowRunQueueStats(3L, 2L, 42L));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowQueueMetrics metrics = new WorkflowQueueMetrics(registry, repository);

        assertThat(registry.get("riccon_workflow_queue_depth")
                        .tag("status", "PENDING")
                        .gauge()
                        .value())
                .isEqualTo(3.0);
        assertThat(registry.get("riccon_workflow_queue_depth")
                        .tag("status", "WAITING_APPROVAL")
                        .gauge()
                        .value())
                .isEqualTo(2.0);
        assertThat(registry.get("riccon_workflow_queue_oldest_pending_age_seconds")
                        .gauge()
                        .value())
                .isEqualTo(42.0);
    }

    /** 槽位 Gauge 随占用/释放变化。 */
    @Test
    @DisplayName("运行槽位占用 Gauge")
    void runsActive_tracksSlots() {
        WorkflowRunQueueRepository repository = mock(WorkflowRunQueueRepository.class);
        when(repository.queueStats()).thenReturn(WorkflowRunQueueStats.EMPTY);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowQueueMetrics metrics = new WorkflowQueueMetrics(registry, repository);

        assertThat(registry.get("riccon_workflow_runs_active").gauge().value()).isZero();
        metrics.slotAcquired();
        metrics.slotAcquired();
        assertThat(registry.get("riccon_workflow_runs_active").gauge().value()).isEqualTo(2.0);
        metrics.slotReleased();
        assertThat(registry.get("riccon_workflow_runs_active").gauge().value()).isEqualTo(1.0);
        metrics.slotReleased();
        metrics.slotReleased();
        assertThat(registry.get("riccon_workflow_runs_active").gauge().value()).isZero();
    }

    /** 四种 Counter 名称与标签固定。 */
    @Test
    @DisplayName("claim/租约/耗尽/续期失败计数")
    void counters_useContractNamesAndTags() {
        WorkflowRunQueueRepository repository = mock(WorkflowRunQueueRepository.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowQueueMetrics metrics = new WorkflowQueueMetrics(registry, repository);

        metrics.claimFailure("INVALID_TENANT");
        metrics.claimFailure("DB_ERROR");
        metrics.leaseLost();
        metrics.claimExhausted();
        metrics.heartbeatRenewFailure();

        assertThat(counter(registry, "riccon_workflow_claim_failures_total", "reason", "INVALID_TENANT"))
                .isEqualTo(1.0);
        assertThat(counter(registry, "riccon_workflow_claim_failures_total", "reason", "DB_ERROR"))
                .isEqualTo(1.0);
        assertThat(counter(registry, "riccon_workflow_lease_lost_total")).isEqualTo(1.0);
        assertThat(counter(registry, "riccon_workflow_claim_exhausted_total")).isEqualTo(1.0);
        assertThat(counter(registry, "riccon_workflow_heartbeat_renew_failures_total"))
                .isEqualTo(1.0);
    }

    /** 聚合查询只在缓存过期时调用；Gauge 读取不抛异常。 */
    @Test
    @DisplayName("Gauge 读取聚合快照")
    void gauge_readsCachedSnapshot() {
        WorkflowRunQueueRepository repository = mock(WorkflowRunQueueRepository.class);
        when(repository.queueStats()).thenReturn(new WorkflowRunQueueStats(7L, 0L, 10L));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowQueueMetrics metrics = new WorkflowQueueMetrics(registry, repository);

        assertThat(registry.get("riccon_workflow_queue_depth")
                        .tag("status", "PENDING")
                        .gauge()
                        .value())
                .isEqualTo(7.0);
        assertThat(registry.get("riccon_workflow_queue_oldest_pending_age_seconds")
                        .gauge()
                        .value())
                .isEqualTo(10.0);
        assertThat(metrics.activeSlots()).isZero();
    }

    /**
     * 读取 Counter 数值。
     *
     * @param registry 注册表
     * @param name 指标名
     * @param tagKey 标签键
     * @param tagValue 标签值
     * @return 计数；不存在时为 0
     */
    private static double counter(SimpleMeterRegistry registry, String name, String tagKey, String tagValue) {
        Counter counter = registry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /**
     * 读取无标签 Counter。
     *
     * @param registry 注册表
     * @param name 指标名
     * @return 计数；不存在时为 0
     */
    private static double counter(SimpleMeterRegistry registry, String name) {
        Counter counter = registry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
