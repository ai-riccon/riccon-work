package com.riccon.worker.workflow.queue.service;

import com.riccon.worker.workflow.queue.config.WorkflowQueueProperties;
import com.riccon.worker.workflow.queue.domain.WorkflowTaskScheduler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * M06 运行队列槽位调度器（ADR-0024 §4.3，OPS-M06-002/OPS-M06-022）。
 *
 * <p>使用<b>独立</b>的调度/执行池（{@code RICCON_WORKFLOW_SCHEDULER_POOL_SIZE}），不复用
 * {@code spring.task.scheduling.pool.size} 或 outbox 调度线程：每槽位同一时刻只有一个
 * 领取/执行任务，池大小等于槽位数，避免“一次领 10 个再串行执行”。每个槽位自调度：
 *
 * <ol>
 *   <li>空队列按 {@link WorkflowClaimBackoff} 指数退避，从
 *       {@code claim-poll-interval-ms} 到 {@code claim-idle-backoff-max-ms}；
 *   <li>领取成功后立即复位为轮询周期，执行完成后再安排下一轮；
 *   <li>领取/执行异常只记录并继续退避，不取消槽位；
 *   <li>{@code RICCON_WORKFLOW_CLAIM_ENABLED=false} 时不注册槽位；关闭时取消后续任务，
 *       在途槽位最多等待 {@code shutdown-grace-seconds}。
 * </ol>
 *
 * <p>没有 {@code WorkflowRunExecutor} Bean（T06b 未落地）时不启动槽位，避免把运行领走却无人推进。
 */
@Component
public class WorkflowRunScheduler {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowRunScheduler.class);

    /** 队列领取器。 */
    private final WorkflowRunQueuePoller poller;

    /** 部署配置。 */
    private final WorkflowQueueProperties properties;

    /** 独立调度/执行池端口。 */
    private final WorkflowTaskScheduler taskScheduler;

    /** 每槽位退避状态；只由该槽位任务读写。 */
    private final List<WorkflowClaimBackoff> backoffs = new ArrayList<>();

    /** 每槽位当前待执行任务；关闭时统一取消。 */
    private final AtomicReferenceArray<ScheduledFuture<?>> currentFutures;

    /** 正在执行的槽位计数与关闭等待。 */
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    /** 槽位空闲信号。 */
    private final Condition slotsIdle = lifecycleLock.newCondition();

    /** 当前执行中的槽位数。 */
    private int activeSlots;

    /** 是否运行中。 */
    private volatile boolean running = true;

    /**
     * 创建调度器。
     *
     * @param poller 队列领取器
     * @param properties 部署配置
     * @param taskScheduler 独立调度池
     */
    public WorkflowRunScheduler(
            WorkflowRunQueuePoller poller,
            WorkflowQueueProperties properties,
            @Qualifier("workflowRunTaskScheduler") WorkflowTaskScheduler taskScheduler) {
        this.poller = poller;
        this.properties = properties;
        this.taskScheduler = taskScheduler;
        this.currentFutures = new AtomicReferenceArray<>(Math.max(1, properties.workerConcurrency()));
    }

    /** 启动槽位；缺少执行器或显式停止领取时不注册任务。 */
    @PostConstruct
    void start() {
        if (!properties.claimEnabled()) {
            LOG.info("workflow.queue.scheduler.disabled reason=CLAIM_ENABLED_FALSE");
            return;
        }
        if (!poller.executorAvailable()) {
            LOG.warn("workflow.queue.scheduler.executor_missing claim_slots=0");
            return;
        }
        for (int slot = 0; slot < properties.workerConcurrency(); slot++) {
            backoffs.add(
                    new WorkflowClaimBackoff(properties.claimPollIntervalMs(), properties.claimIdleBackoffMaxMs()));
            schedule(slot, properties.claimPollIntervalMs());
        }
        LOG.info(
                "workflow.queue.scheduler.started concurrency={} pollIntervalMs={} maxBackoffMs={}"
                        + " schedulerPoolSize={} heartbeatConcurrency={} leaseSeconds={}",
                properties.workerConcurrency(),
                properties.claimPollIntervalMs(),
                properties.claimIdleBackoffMaxMs(),
                properties.schedulerPoolSize(),
                properties.heartbeatConcurrency(),
                properties.leaseSeconds());
    }

    /** 停止领取并等待在途槽位到宽限期；超时记录 WARN 后退出。 */
    @PreDestroy
    void stop() {
        running = false;
        for (int slot = 0; slot < currentFutures.length(); slot++) {
            ScheduledFuture<?> future = currentFutures.getAndSet(slot, null);
            if (future != null) {
                future.cancel(false);
            }
        }
        lifecycleLock.lock();
        try {
            long remainingNanos = TimeUnit.SECONDS.toNanos(Math.max(0, properties.shutdownGraceSeconds()));
            while (activeSlots > 0 && remainingNanos > 0) {
                remainingNanos = slotsIdle.awaitNanos(remainingNanos);
            }
            if (activeSlots > 0) {
                LOG.warn("workflow.queue.scheduler.shutdown_grace_exceeded activeSlots={}", activeSlots);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOG.warn("workflow.queue.scheduler.shutdown_interrupted activeSlots={}", activeSlots);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 安排一个槽位在指定延迟后运行。
     *
     * @param slot 槽位序号
     * @param delayMillis 延迟毫秒
     */
    private void schedule(int slot, long delayMillis) {
        currentFutures.set(slot, taskScheduler.schedule(() -> pollSlot(slot), delayMillis));
    }

    /**
     * 执行一个槽位的一轮领取，并根据结果安排下一轮。
     *
     * @param slot 槽位序号
     */
    void pollSlot(int slot) {
        if (!running || !properties.claimEnabled()) {
            return;
        }
        boolean claimed;
        lifecycleLock.lock();
        try {
            activeSlots++;
        } finally {
            lifecycleLock.unlock();
        }
        try {
            claimed = poller.pollOnce(slot);
        } catch (RuntimeException ex) {
            // 调度任务不能把异常抛回线程池，否则后续槽位轮次会停止。
            LOG.error("workflow.queue.slot.failed slot={}", slot, ex);
            claimed = false;
        } finally {
            lifecycleLock.lock();
            try {
                activeSlots--;
                slotsIdle.signalAll();
            } finally {
                lifecycleLock.unlock();
            }
        }
        if (!running || !properties.claimEnabled()) {
            return;
        }
        long delay = claimed ? backoffs.get(slot).reset() : backoffs.get(slot).nextAfterEmpty();
        schedule(slot, delay);
    }

    /**
     * 当前执行中的槽位数，供关闭日志使用。
     *
     * @return 活跃槽位
     */
    int activeSlots() {
        lifecycleLock.lock();
        try {
            return activeSlots;
        } finally {
            lifecycleLock.unlock();
        }
    }
}
