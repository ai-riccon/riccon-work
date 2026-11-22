package com.riccon.worker.run.service;

import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.domain.OutboxLeaseRenewer;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Outbox 单事件租约心跳（ADR-0023 第 7.3 节）。
 *
 * <p>handler 执行期间每个 {@code leaseDuration/3} 续期一次；续期返回 0 或抛异常时视为租约丢失，
 * 立即中断处理线程并让 {@link OutboxPoller} 放弃本次结果，避免与新租约持有者双执行。心跳任务
 * 按事件创建、按事件取消，不使用共享定时器批量续期；同一个 {@code eventId} + {@code leaseOwner}
 * 只允许一个活跃心跳。
 *
 * <p>另设单事件最大处理时长 {@code RICCON_WORKER_OUTBOX_MAX_PROCESSING}（默认 30 分钟）：超时后
 * 中断 handler，并由轮询器按可重试失败推进退避，不把事件直接判死。
 */
@Component
public class OutboxLeaseHeartbeat {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(OutboxLeaseHeartbeat.class);

    /** 租约续期端口。 */
    private final OutboxLeaseRenewer renewer;

    /** 心跳与超时共用的守护线程调度器；任务按事件创建，不做批量续期。 */
    private final ScheduledExecutorService scheduler;

    /**
     * 创建心跳组件。
     *
     * @param renewer 租约续期端口
     */
    public OutboxLeaseHeartbeat(OutboxLeaseRenewer renewer) {
        this.renewer = Objects.requireNonNull(renewer, "renewer must not be null");
        this.scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "outbox-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 为单个事件启动心跳与超时看门狗。
     *
     * @param event 已领取事件
     * @param leaseOwner 当前租约持有者
     * @param leaseDuration 领取时使用的租约时长
     * @param maxProcessing 单事件最大处理时长；null 表示不设超时
     * @return 可关闭的租约句柄
     */
    public Lease start(ClaimedOutboxEvent event, String leaseOwner, Duration leaseDuration, Duration maxProcessing) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(leaseOwner, "leaseOwner must not be null");
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        return new Lease(event, leaseOwner, leaseDuration, maxProcessing);
    }

    /** 关闭调度器；容器退出时取消所有心跳与看门狗。 */
    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * 单个事件的租约句柄。
     *
     * <p>关闭后不再续期，也不会再标记丢失/超时；轮询器必须在 handler 结束后调用 {@link #close()}。
     */
    public final class Lease implements AutoCloseable {

        /** 事件租户。 */
        private final String tenantId;

        /** 事件 ID。 */
        private final java.util.UUID eventId;

        /** 事件 ID 的字符串形式，仅用于日志。 */
        private final String eventIdForLog;

        /** 租约持有者。 */
        private final String leaseOwner;

        /** 租约时长。 */
        private final Duration leaseDuration;

        /** 最大处理时长；null 表示不设超时。 */
        private final Duration maxProcessing;

        /** 处理线程；租约丢失或超时时中断。 */
        private final Thread processingThread;

        /** 是否已丢失租约。 */
        private final AtomicBoolean lost = new AtomicBoolean();

        /** 是否已超时。 */
        private final AtomicBoolean timedOut = new AtomicBoolean();

        /** 是否已关闭；关闭后心跳不再改变状态。 */
        private final AtomicBoolean closed = new AtomicBoolean();

        /** 续期任务。 */
        private final ScheduledFuture<?> renewTask;

        /** 超时任务；未配置超时为空。 */
        private final ScheduledFuture<?> timeoutTask;

        /**
         * 创建并启动单个事件的心跳。
         *
         * @param event 已领取事件
         * @param leaseOwner 租约持有者
         * @param leaseDuration 租约时长
         * @param maxProcessing 最大处理时长
         */
        private Lease(ClaimedOutboxEvent event, String leaseOwner, Duration leaseDuration, Duration maxProcessing) {
            this.tenantId = event.tenantId();
            this.eventId = event.id();
            this.eventIdForLog = String.valueOf(event.id());
            this.leaseOwner = leaseOwner;
            this.leaseDuration = leaseDuration;
            this.maxProcessing = maxProcessing;
            this.processingThread = Thread.currentThread();
            long renewMillis = Math.max(1L, leaseDuration.toMillis() / 3L);
            this.renewTask =
                    scheduler.scheduleAtFixedRate(this::renewSafely, renewMillis, renewMillis, TimeUnit.MILLISECONDS);
            if (maxProcessing == null) {
                this.timeoutTask = null;
            } else {
                this.timeoutTask = scheduler.schedule(
                        this::timeout, Math.max(1L, maxProcessing.toMillis()), TimeUnit.MILLISECONDS);
            }
        }

        /**
         * 返回是否已丢失租约。
         *
         * @return true 表示本次结果必须丢弃
         */
        public boolean lost() {
            return lost.get();
        }

        /**
         * 返回是否已超过最大处理时长。
         *
         * @return true 表示按可重试失败处理
         */
        public boolean timedOut() {
            return timedOut.get();
        }

        /** 续期一次；异常与 0 行都按租约丢失处理，不能继续写入结果。 */
        private void renewSafely() {
            if (closed.get() || lost.get()) {
                return;
            }
            try {
                int updated = renewer.renew(tenantId, eventId, leaseOwner, leaseDuration);
                if (updated == 0 && !closed.get() && lost.compareAndSet(false, true)) {
                    LOG.warn(
                            "outbox.lease_lost eventId={} tenantId={} leaseOwner={} action=renew",
                            eventIdForLog,
                            tenantId,
                            leaseOwner);
                    interruptProcessing();
                }
            } catch (RuntimeException ex) {
                // 数据库瞬时不可用时不能假设租约仍有效：放弃结果，等待新租约重放。
                if (!closed.get() && lost.compareAndSet(false, true)) {
                    LOG.warn(
                            "outbox.lease_lost eventId={} tenantId={} leaseOwner={} action=renew error={}",
                            eventIdForLog,
                            tenantId,
                            leaseOwner,
                            ex.getClass().getSimpleName());
                    interruptProcessing();
                }
            }
        }

        /** 到达最大处理时长：中断处理线程，轮询器按可重试失败推进。 */
        private void timeout() {
            if (closed.get() || lost.get()) {
                return;
            }
            if (timedOut.compareAndSet(false, true)) {
                LOG.warn(
                        "outbox.processing_timeout eventId={} tenantId={} leaseOwner={} maxProcessing={}",
                        eventIdForLog,
                        tenantId,
                        leaseOwner,
                        maxProcessing);
                interruptProcessing();
            }
        }

        /** 中断当前处理线程；handler 需要响应中断才能及时退出。 */
        private void interruptProcessing() {
            processingThread.interrupt();
        }

        /** 取消心跳与看门狗；重复关闭无副作用。 */
        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            renewTask.cancel(false);
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
        }
    }
}
