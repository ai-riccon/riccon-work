package com.riccon.worker.workflow.heartbeat.service;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import com.riccon.worker.workflow.heartbeat.domain.WorkflowLease;
import com.riccon.worker.workflow.queue.config.WorkflowQueueProperties;
import com.riccon.worker.workflow.queue.domain.ClaimedWorkflowRun;
import com.riccon.worker.workflow.queue.domain.WorkflowTaskScheduler;
import com.riccon.worker.workflow.queue.repository.WorkflowRunLeaseRepository;
import com.riccon.worker.workflow.queue.service.WorkflowQueueMetrics;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 运行执行租约心跳与看门狗（ADR-0024 §4.3，TM-81）。
 *
 * <p>每个已领取运行在进入执行器前先启动独立心跳：领取后立即同步续期一次，随后按
 * {@code RICCON_WORKFLOW_HEARTBEAT_INTERVAL_SECONDS}（默认 30s，启动校验必须 ≤ lease/3）在
 * 独立心跳池中续期。续期只在 {@code status='RUNNING'} 且 {@code lease_owner} 匹配时成功：
 *
 * <ul>
 *   <li>返回 0 且运行已取消 → 置 {@code cancelled}，执行器应尽快停止；
 *   <li>返回 0 且仍为 RUNNING → 租约被其他实例重领，置 {@code lost} 并增加
 *       {@code lease_lost_total}，执行器必须丢弃结果；
 *   <li>续期异常同样 fail-closed 视为丢失：无法证明仍持有租约时不得写入结果
 *       （与既有 Outbox 心跳语义一致），不等待下一次续期。
 * </ul>
 *
 * <p>心跳不读取输入/结果/图，只续期并读取运行状态；失败日志只带 ID、租约持有者与错误类型。
 */
@Component
public class WorkflowLeaseHeartbeat {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowLeaseHeartbeat.class);

    /** 租约仓储。 */
    private final WorkflowRunLeaseRepository repository;

    /** 队列指标。 */
    private final WorkflowQueueMetrics metrics;

    /** 部署配置（租约时长与续期间隔）。 */
    private final WorkflowQueueProperties properties;

    /** 独立心跳池。 */
    private final WorkflowTaskScheduler scheduler;

    /**
     * 创建心跳组件。
     *
     * @param repository 租约仓储
     * @param metrics 队列指标
     * @param properties 部署配置
     * @param scheduler 独立心跳池
     */
    public WorkflowLeaseHeartbeat(
            WorkflowRunLeaseRepository repository,
            WorkflowQueueMetrics metrics,
            WorkflowQueueProperties properties,
            @Qualifier("workflowHeartbeatTaskScheduler") WorkflowTaskScheduler scheduler) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    }

    /**
     * 为已领取运行启动心跳。
     *
     * @param claim 领取元数据
     * @return 租约句柄；调用方必须关闭
     */
    public WorkflowLease start(ClaimedWorkflowRun claim) {
        Objects.requireNonNull(claim, "claim must not be null");
        Lease lease = new Lease(claim);
        // 领取后立即首次续期：先用真实租约覆盖领取时的近似时间，再进入执行器。
        lease.renewNow();
        if (lease.active()) {
            lease.scheduleNext();
        }
        return lease;
    }

    /**
     * 续期一次并在失败时置位租约状态。
     *
     * @param lease 租约句柄
     */
    private void renew(Lease lease) {
        if (!lease.active()) {
            return;
        }
        try {
            int updated = TenantContext.callWithTenant(
                    lease.tenantIdObject(),
                    () -> repository.renew(
                            lease.tenantIdObject(), lease.runId(), lease.leaseOwner(), lease.leaseDuration()));
            if (updated > 0) {
                return;
            }
            metrics.heartbeatRenewFailure();
            WorkflowRunStatus status = TenantContext.callWithTenant(
                            lease.tenantIdObject(), () -> repository.findStatus(lease.tenantIdObject(), lease.runId()))
                    .orElse(null);
            if (status == WorkflowRunStatus.CANCELLED) {
                lease.markCancelled();
                LOG.info(
                        "workflow.lease.cancelled runId={} tenantId={} leaseOwner={}",
                        lease.runId(),
                        lease.tenantId(),
                        lease.leaseOwner());
                return;
            }
            lease.markLost();
            LOG.warn(
                    "workflow.lease.lost runId={} tenantId={} leaseOwner={} status={}",
                    lease.runId(),
                    lease.tenantId(),
                    lease.leaseOwner(),
                    status);
        } catch (RuntimeException ex) {
            // 续期异常无法证明仍持有租约：fail-closed，放弃本次结果，等待租约到期后由其他实例恢复。
            metrics.heartbeatRenewFailure();
            lease.markLost();
            LOG.warn(
                    "workflow.lease.renew_failed runId={} tenantId={} leaseOwner={} error={}",
                    lease.runId(),
                    lease.tenantId(),
                    lease.leaseOwner(),
                    ex.getClass().getSimpleName());
        }
    }

    /**
     * 单个运行的租约句柄。
     *
     * <p>首次续期同步执行；后续任务按间隔自调度。句柄关闭后不再续期，重复关闭无副作用。
     */
    public final class Lease implements WorkflowLease {

        /** 运行 ID。 */
        private final UUID runId;

        /** 原始租户字符串。 */
        private final String tenantId;

        /** 已校验租户。 */
        private final TenantId tenantIdObject;

        /** 租约持有者。 */
        private final String leaseOwner;

        /** 租约时长。 */
        private final Duration leaseDuration;

        /** 续期间隔。 */
        private final long intervalMillis;

        /** 是否丢失租约。 */
        private final AtomicBoolean lost = new AtomicBoolean();

        /** 是否已取消。 */
        private final AtomicBoolean cancelled = new AtomicBoolean();

        /** 是否已停止。 */
        private final AtomicBoolean stopped = new AtomicBoolean();

        /** 下一次续期任务。 */
        private volatile ScheduledFuture<?> nextRenewal;

        /**
         * 创建租约句柄。
         *
         * @param claim 领取元数据
         */
        private Lease(ClaimedWorkflowRun claim) {
            this.runId = claim.runId();
            this.tenantId = claim.tenantId();
            this.tenantIdObject = TenantId.of(claim.tenantId());
            this.leaseOwner = claim.leaseOwner();
            this.leaseDuration = Duration.ofSeconds(Math.max(1, properties.leaseSeconds()));
            this.intervalMillis = Math.max(
                    1L,
                    Duration.ofSeconds(properties.heartbeatIntervalSeconds()).toMillis());
        }

        /** 立即续期一次。 */
        private void renewNow() {
            renew(this);
        }

        /** 安排下一次续期。 */
        private void scheduleNext() {
            if (!active()) {
                return;
            }
            nextRenewal = scheduler.schedule(this::onRenewalDue, intervalMillis);
        }

        /** 定时任务入口：续期后继续自调度，直到停止或失去租约。 */
        private void onRenewalDue() {
            renewNow();
            if (active()) {
                scheduleNext();
            }
        }

        /** 取消已安排的下一次续期。 */
        private void cancelNext() {
            ScheduledFuture<?> future = nextRenewal;
            if (future != null) {
                future.cancel(false);
            }
        }

        /** 标记租约丢失并停止续期。 */
        private void markLost() {
            if (lost.compareAndSet(false, true)) {
                metrics.leaseLost();
            }
            cancelNext();
        }

        /** 标记运行已取消并停止续期。 */
        private void markCancelled() {
            cancelled.set(true);
            cancelNext();
        }

        /**
         * 是否仍处于可续期/可执行状态。
         *
         * @return true 表示未停止、未丢失、未取消
         */
        private boolean active() {
            return !stopped.get() && !lost.get() && !cancelled.get();
        }

        /** 返回租户值对象。 */
        private TenantId tenantIdObject() {
            return tenantIdObject;
        }

        /** 返回租约持有者。 */
        private String leaseOwner() {
            return leaseOwner;
        }

        /** 返回租约时长。 */
        private Duration leaseDuration() {
            return leaseDuration;
        }

        /** {@inheritDoc} */
        @Override
        public UUID runId() {
            return runId;
        }

        /** {@inheritDoc} */
        @Override
        public String tenantId() {
            return tenantId;
        }

        /** {@inheritDoc} */
        @Override
        public boolean lost() {
            return lost.get();
        }

        /** {@inheritDoc} */
        @Override
        public boolean cancelled() {
            return cancelled.get();
        }

        /** {@inheritDoc} */
        @Override
        public boolean stopped() {
            return stopped.get() || lost.get() || cancelled.get();
        }

        /** {@inheritDoc} */
        @Override
        public void close() {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            cancelNext();
        }
    }
}
