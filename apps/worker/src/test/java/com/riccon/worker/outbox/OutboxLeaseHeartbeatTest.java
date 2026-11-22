package com.riccon.worker.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.domain.OutboxLeaseRenewer;
import com.riccon.worker.run.service.OutboxLeaseHeartbeat;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Outbox 单事件租约心跳（ADR-0023 第 7.3 节）。
 *
 * <p>租约续期必须按 {@code leaseDuration/3} 发生；续期 0 行或异常视为丢失，必须中断处理线程；
 * 超过最大处理时长必须标记超时并按可重试失败处理。测试在独立线程中运行，避免中断标记污染 JUnit
 * 线程；所有等待都有显式超时，不使用 sleep。
 */
class OutboxLeaseHeartbeatTest {

    /** 固定事件，仅用于心跳。 */
    private final ClaimedOutboxEvent event = new ClaimedOutboxEvent(
            UUID.fromString("00000000-0000-7000-8000-000000000001"),
            "tenant-heartbeat",
            UUID.fromString("00000000-0000-7000-8000-000000000002"),
            EventTypes.FILE_PARSE_REQUESTED,
            1,
            "file:heartbeat:parse:1",
            "{}",
            1,
            Instant.now(),
            Instant.now().plusSeconds(30));

    /** handler 执行期间至少续期两次，且不标记丢失。 */
    @Test
    @DisplayName("心跳按租约三分之一续期")
    void renew_renewsDuringHandler() {
        CountDownLatch renewed = new CountDownLatch(2);
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean lost = new AtomicBoolean(true);
        AtomicReferenceHolder failure = new AtomicReferenceHolder();
        Thread worker = new Thread(
                () -> {
                    OutboxLeaseHeartbeat heartbeat = new OutboxLeaseHeartbeat((tenantId, eventId, owner, duration) -> {
                        calls.incrementAndGet();
                        renewed.countDown();
                        return 1;
                    });
                    try (OutboxLeaseHeartbeat.Lease lease =
                            heartbeat.start(event, "owner", Duration.ofMillis(150), Duration.ofSeconds(5))) {
                        await(renewed, Duration.ofSeconds(3));
                        lost.set(lease.lost());
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    } finally {
                        heartbeat.shutdown();
                    }
                },
                "heartbeat-renew-test");
        worker.start();
        join(worker);
        assertThat(failure.get()).isNull();
        assertThat(calls.get()).isGreaterThanOrEqualTo(2);
        assertThat(lost.get()).isFalse();
    }

    /** 续期 0 行表示租约被接管：标记丢失并中断处理线程。 */
    @Test
    @DisplayName("续期失败标记丢失并中断处理")
    void renew_zeroRows_marksLostAndInterrupts() {
        OutboxLeaseRenewer renewer = (tenantId, eventId, owner, duration) -> 0;
        LeaseOutcome outcome = runLease(renewer, Duration.ofMillis(150), Duration.ofSeconds(5), true);
        assertThat(outcome.failure()).isNull();
        assertThat(outcome.lost()).isTrue();
        assertThat(outcome.interrupted()).isTrue();
    }

    /** 续期抛异常同样按丢失处理，不能假设租约仍有效。 */
    @Test
    @DisplayName("续期异常按租约丢失处理")
    void renew_exception_marksLostDelivery() {
        OutboxLeaseRenewer renewer = (tenantId, eventId, owner, duration) -> {
            throw new IllegalStateException("database unavailable");
        };
        LeaseOutcome outcome = runLease(renewer, Duration.ofMillis(150), Duration.ofSeconds(5), true);
        assertThat(outcome.failure()).isNull();
        assertThat(outcome.lost()).isTrue();
        assertThat(outcome.interrupted()).isTrue();
    }

    /** 超过最大处理时长标记超时并中断，轮询器按可重试失败推进。 */
    @Test
    @DisplayName("超过最大处理时长标记超时")
    void timeout_marksTimedOutAndInterrupts() {
        OutboxLeaseRenewer renewer = (tenantId, eventId, owner, duration) -> 1;
        LeaseOutcome outcome = runLease(renewer, Duration.ofSeconds(30), Duration.ofMillis(100), false);
        assertThat(outcome.failure()).isNull();
        assertThat(outcome.timedOut()).isTrue();
        assertThat(outcome.lost()).isFalse();
        assertThat(outcome.interrupted()).isTrue();
    }

    /**
     * 在独立线程中启动租约并等待丢失或超时。
     *
     * @param renewer 续期端口
     * @param leaseDuration 租约时长
     * @param maxProcessing 最大处理时长
     * @param awaitLost true 等待丢失，false 等待超时
     * @return 观测结果
     */
    private LeaseOutcome runLease(
            OutboxLeaseRenewer renewer, Duration leaseDuration, Duration maxProcessing, boolean awaitLost) {
        LeaseOutcome[] holder = new LeaseOutcome[1];
        Thread worker = new Thread(
                () -> {
                    OutboxLeaseHeartbeat heartbeat = new OutboxLeaseHeartbeat(renewer);
                    Throwable failure = null;
                    boolean lost = false;
                    boolean timedOut = false;
                    boolean interrupted = false;
                    try (OutboxLeaseHeartbeat.Lease lease =
                            heartbeat.start(event, "owner", leaseDuration, maxProcessing)) {
                        awaitCondition(awaitLost ? lease::lost : lease::timedOut, Duration.ofSeconds(3));
                        lost = lease.lost();
                        timedOut = lease.timedOut();
                        // 等中断落地；park 会保留中断标记，isInterrupted 读取后不清除。
                        awaitCondition(() -> Thread.currentThread().isInterrupted(), Duration.ofSeconds(2));
                        interrupted = Thread.currentThread().isInterrupted();
                    } catch (Throwable throwable) {
                        failure = throwable;
                    } finally {
                        heartbeat.shutdown();
                    }
                    holder[0] = new LeaseOutcome(lost, timedOut, interrupted, failure);
                },
                "heartbeat-outcome-test");
        worker.start();
        join(worker);
        assertThat(holder[0]).isNotNull();
        return holder[0];
    }

    /**
     * 等待条件成立或超时；使用 parkNanos 的有界等待，不使用 sleep。
     *
     * @param condition 条件
     * @param timeout 超时
     */
    private static void awaitCondition(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("condition not met within " + timeout);
        }
    }

    /**
     * 等待 CountDownLatch 计数归零，保留中断标记。
     *
     * @param latch 计数
     * @param timeout 超时
     */
    private static void await(CountDownLatch latch, Duration timeout) {
        try {
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("latch not counted down within " + timeout);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for renewal", exception);
        }
    }

    /**
     * 等待工作线程结束。
     *
     * @param worker 工作线程
     */
    private static void join(Thread worker) {
        try {
            worker.join(5000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while joining heartbeat worker", exception);
        }
        assertThat(worker.isAlive()).isFalse();
    }

    /** 租约观测结果。 */
    private record LeaseOutcome(boolean lost, boolean timedOut, boolean interrupted, Throwable failure) {}

    /** 持有跨线程异常的可变引用。 */
    private static final class AtomicReferenceHolder {

        /** 异常。 */
        private Throwable value;

        /**
         * 记录异常。
         *
         * @param throwable 异常
         */
        void set(Throwable throwable) {
            this.value = throwable;
        }

        /**
         * 返回异常。
         *
         * @return 异常
         */
        Throwable get() {
            return value;
        }
    }
}
