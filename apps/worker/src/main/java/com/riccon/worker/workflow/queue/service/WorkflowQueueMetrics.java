package com.riccon.worker.workflow.queue.service;

import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import com.riccon.worker.workflow.queue.domain.WorkflowRunQueueStats;
import com.riccon.worker.workflow.queue.repository.WorkflowRunQueueRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * M06 运行队列与租约指标（ADR-0024 §10.2，Micrometer）。
 *
 * <p>指标固定为 ADR 名称，避免 RUNBOOK 告警与实现漂移：
 *
 * <ul>
 *   <li>{@code riccon_workflow_queue_depth{status}}：PENDING / WAITING_APPROVAL 深度；
 *   <li>{@code riccon_workflow_queue_oldest_pending_age_seconds}：最老 PENDING 年龄；
 *   <li>{@code riccon_workflow_runs_active}：当前执行槽位占用；
 *   <li>{@code riccon_workflow_claim_failures_total{reason}}：INVALID_TENANT / DB_ERROR / CLAIM_EXCEPTION；
 *   <li>{@code riccon_workflow_lease_lost_total}：任何增长都告警；
 *   <li>{@code riccon_workflow_claim_exhausted_total}：claim_attempts 耗尽进入 WF_INTERNAL；
 *   <li>{@code riccon_workflow_heartbeat_renew_failures_total}：续期失败前置信号。
 * </ul>
 *
 * <p>Gauge 采用短 TTL 缓存：Prometheus 抓取可能并发读取多个 Gauge，缓存把每次抓取的 3 次聚合查询
 * 收敛为一次；数据库瞬时不可用时返回上一份快照并记录 WARN，不让指标读取破坏抓取或掩盖队列主路径。
 */
@Component
public class WorkflowQueueMetrics {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowQueueMetrics.class);

    /** 指标缓存有效期（纳秒）。 */
    private static final long CACHE_TTL_NANOS = 1_000_000_000L;

    /** 队列仓储。 */
    private final WorkflowRunQueueRepository repository;

    /** Micrometer 注册表；Gauge 与 Counter 共用。 */
    private final MeterRegistry registry;

    /** 当前占用执行槽位数。 */
    private final AtomicInteger activeSlots = new AtomicInteger();

    /** 最近一次聚合快照。 */
    private volatile WorkflowRunQueueStats cachedStats = WorkflowRunQueueStats.EMPTY;

    /** 最近一次聚合时间（{@link System#nanoTime()}）；初始值避免减法溢出。 */
    private volatile long cachedAtNanos = Long.MIN_VALUE / 2;

    /**
     * 创建指标组件并注册 Gauge。
     *
     * @param registry Micrometer 注册表
     * @param repository 队列仓储
     */
    public WorkflowQueueMetrics(MeterRegistry registry, WorkflowRunQueueRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        Gauge.builder("riccon_workflow_queue_depth", this, metrics -> metrics.queueDepth(WorkflowRunStatus.PENDING))
                .tag("status", WorkflowRunStatus.PENDING.name())
                .description("Workflow runs waiting for a worker slot")
                .register(registry);
        Gauge.builder(
                        "riccon_workflow_queue_depth",
                        this,
                        metrics -> metrics.queueDepth(WorkflowRunStatus.WAITING_APPROVAL))
                .tag("status", WorkflowRunStatus.WAITING_APPROVAL.name())
                .description("Workflow runs waiting for approval")
                .register(registry);
        Gauge.builder(
                        "riccon_workflow_queue_oldest_pending_age_seconds",
                        this,
                        WorkflowQueueMetrics::oldestPendingAgeSeconds)
                .description("Age of the oldest PENDING workflow run")
                .register(registry);
        Gauge.builder("riccon_workflow_runs_active", activeSlots, AtomicInteger::get)
                .description("Occupied workflow execution slots")
                .register(registry);
    }

    /**
     * 记录一次 claim 失败。
     *
     * @param reason 稳定原因：INVALID_TENANT / DB_ERROR / CLAIM_EXCEPTION
     */
    public void claimFailure(String reason) {
        Counter.builder("riccon_workflow_claim_failures_total")
                .tag("reason", reason)
                .description("Workflow claim failures by reason")
                .register(registry)
                .increment();
    }

    /** 记录一次租约丢失（续期返回 0 或续期异常）。 */
    public void leaseLost() {
        leaseLostCounter().increment();
    }

    /** 记录一次 claim_attempts 耗尽。 */
    public void claimExhausted() {
        claimExhaustedCounter().increment();
    }

    /** 记录一次续期失败（先于 lease_lost 的前置信号）。 */
    public void heartbeatRenewFailure() {
        renewFailureCounter().increment();
    }

    /** 占用一个执行槽位。 */
    public void slotAcquired() {
        activeSlots.incrementAndGet();
    }

    /** 释放一个执行槽位。 */
    public void slotReleased() {
        activeSlots.updateAndGet(current -> Math.max(0, current - 1));
    }

    /**
     * 当前占用槽位数，用于测试与日志。
     *
     * @return 活跃槽位
     */
    public int activeSlots() {
        return activeSlots.get();
    }

    /**
     * 读取 PENDING 或 WAITING_APPROVAL 深度。
     *
     * @param status 状态
     * @return 深度
     */
    private double queueDepth(WorkflowRunStatus status) {
        WorkflowRunQueueStats stats = stats();
        return status == WorkflowRunStatus.WAITING_APPROVAL ? stats.waitingApprovalDepth() : stats.pendingDepth();
    }

    /**
     * 最老 PENDING 年龄。
     *
     * @return 秒
     */
    private double oldestPendingAgeSeconds() {
        return stats().oldestPendingAgeSeconds();
    }

    /**
     * 返回带 TTL 的聚合快照；失败时保留上一份并记录 WARN。
     *
     * @return 队列快照
     */
    private WorkflowRunQueueStats stats() {
        long now = System.nanoTime();
        WorkflowRunQueueStats current = cachedStats;
        if (now - cachedAtNanos < CACHE_TTL_NANOS) {
            return current;
        }
        synchronized (this) {
            now = System.nanoTime();
            if (now - cachedAtNanos < CACHE_TTL_NANOS) {
                return cachedStats;
            }
            try {
                cachedStats = repository.queueStats();
                cachedAtNanos = now;
            } catch (RuntimeException ex) {
                // 指标读取不得破坏 Prometheus 抓取；保留上一份快照并保留堆栈供排障。
                LOG.warn("workflow.metrics.queue_stats_failed", ex);
            }
            return cachedStats;
        }
    }

    /**
     * 计数器注册使用的注册表；Gauge 与 Counter 共用同一个 Micrometer 注册表。
     *
     * @return Micrometer 注册表
     */
    private MeterRegistry registryOfCounters() {
        return registry;
    }

    /** claim 失败计数器。 */
    private Counter leaseLostCounter() {
        return Counter.builder("riccon_workflow_lease_lost_total")
                .description("Workflow run leases lost")
                .register(registryOfCounters());
    }

    /** claim 耗尽的运行计数器。 */
    private Counter claimExhaustedCounter() {
        return Counter.builder("riccon_workflow_claim_exhausted_total")
                .description("Workflow runs failed with WF_INTERNAL after claim attempts exhausted")
                .register(registryOfCounters());
    }

    /** 心跳续期失败计数器。 */
    private Counter renewFailureCounter() {
        return Counter.builder("riccon_workflow_heartbeat_renew_failures_total")
                .description("Workflow lease heartbeat renewal failures")
                .register(registryOfCounters());
    }
}
