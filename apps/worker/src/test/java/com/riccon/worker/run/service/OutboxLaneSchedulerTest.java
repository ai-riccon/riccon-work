package com.riccon.worker.run.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.riccon.worker.config.OutboxProperties;
import com.riccon.worker.run.config.OutboxLaneProperties;
import com.riccon.worker.run.domain.OutboxLane;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/**
 * Outbox 分通道调度器的注册、互斥与异常隔离（M05-B1 F-002/F-005）。
 *
 * <p>调度器是双通道并发语义的入口：default 必须固定 1 个任务，heavy 必须按配置提交 N 个任务，
 * 每个任务只能领取自己通道的事件；{@code legacy-all-lane-scheduling=true} 时必须切换回旧调度器，
 * 不能让新旧调度同时领取。测试用记录型 {@link TaskScheduler}，不启动真实线程池或数据库。
 */
class OutboxLaneSchedulerTest {

    /** default 1 个 + heavy N 个任务，且每个任务只轮询自己的通道。 */
    @Test
    @DisplayName("按通道注册 default=1 与 heavy=N 个任务")
    void start_registersConfiguredWorkersPerLane() {
        for (int heavyConcurrency : List.of(1, 2, 4, 8)) {
            OutboxPoller poller = mock(OutboxPoller.class);
            RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();
            OutboxLaneProperties laneProperties = laneProperties(heavyConcurrency);

            new OutboxLaneScheduler(poller, laneProperties, outboxProperties(), taskScheduler).start();

            assertThat(taskScheduler.tasks)
                    .as("heavy=%s 时任务数", heavyConcurrency)
                    .hasSize(1 + heavyConcurrency);
            // 逐个执行任务：第一个任务只领取 default，其余只领取 heavy，通道不交叉。
            taskScheduler.tasks.get(0).run();
            verify(poller).pollOnce(OutboxLane.DEFAULT);
            verify(poller, never()).pollOnce(OutboxLane.HEAVY);
            for (int worker = 1; worker <= heavyConcurrency; worker++) {
                taskScheduler.tasks.get(worker).run();
            }
            verify(poller, times(heavyConcurrency)).pollOnce(OutboxLane.HEAVY);
            verify(poller, never()).pollOnce();
        }
    }

    /** 分实例只启用 heavy 时不为 default 通道注册任务。 */
    @Test
    @DisplayName("只启用 heavy 时只注册 heavy 任务")
    void start_heavyOnly_registersNoDefaultTask() {
        OutboxPoller poller = mock(OutboxPoller.class);
        RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();
        OutboxLaneProperties laneProperties = laneProperties(2);
        laneProperties.setLanes(List.of("heavy"));
        laneProperties.afterPropertiesSet();

        new OutboxLaneScheduler(poller, laneProperties, outboxProperties(), taskScheduler).start();

        assertThat(taskScheduler.tasks).hasSize(2);
        taskScheduler.tasks.forEach(Runnable::run);
        verify(poller, times(2)).pollOnce(OutboxLane.HEAVY);
        verify(poller, never()).pollOnce(OutboxLane.DEFAULT);
    }

    /** 单轮轮询异常不能逃出调度任务，否则固定延迟任务会被取消。 */
    @Test
    @DisplayName("轮询异常被隔离")
    void poll_exception_isIsolated() {
        OutboxPoller poller = mock(OutboxPoller.class);
        when(poller.pollOnce(any(OutboxLane.class))).thenThrow(new IllegalStateException("database down"));
        RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();
        OutboxLaneScheduler scheduler =
                new OutboxLaneScheduler(poller, laneProperties(2), outboxProperties(), taskScheduler);
        scheduler.start();

        assertThatCode(() -> taskScheduler.tasks.forEach(Runnable::run)).doesNotThrowAnyException();

        verify(poller, times(3)).pollOnce(any(OutboxLane.class));
    }

    /** 停止时取消全部任务，重复停止无副作用。 */
    @Test
    @DisplayName("停止取消全部任务")
    void stop_cancelsEveryTask() {
        OutboxPoller poller = mock(OutboxPoller.class);
        RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();
        OutboxLaneScheduler scheduler =
                new OutboxLaneScheduler(poller, laneProperties(2), outboxProperties(), taskScheduler);
        scheduler.start();
        assertThat(taskScheduler.futures).hasSize(3);

        scheduler.stop();
        taskScheduler.futures.forEach(future -> verify(future).cancel(false));
        assertThatCode(scheduler::stop).doesNotThrowAnyException();
    }

    /** legacy × scheduling-enabled 四种组合下新旧调度器必须互斥。 */
    @Test
    @DisplayName("legacy 与分通道调度互斥")
    void legacyAndLaneScheduler_areMutuallyExclusive() {
        assertSchedulerComposition("true", "true", false, true);
        assertSchedulerComposition("true", "false", false, false);
        assertSchedulerComposition("false", "true", true, false);
        assertSchedulerComposition("false", "false", false, false);
    }

    /**
     * 断言一种调度器组合。
     *
     * @param legacy legacy-all-lane-scheduling 值
     * @param schedulingEnabled scheduling-enabled 值
     * @param laneSchedulerExpected 是否期望分通道调度器
     * @param legacySchedulerExpected 是否期望旧调度器
     */
    private static void assertSchedulerComposition(
            String legacy, String schedulingEnabled, boolean laneSchedulerExpected, boolean legacySchedulerExpected) {
        new ApplicationContextRunner()
                .withUserConfiguration(OutboxLaneScheduler.class, OutboxScheduler.class)
                .withBean(OutboxPoller.class, () -> mock(OutboxPoller.class))
                .withBean(OutboxProperties.class, OutboxLaneSchedulerTest::outboxProperties)
                .withBean(OutboxLaneProperties.class, OutboxLaneProperties::new)
                .withBean(TaskScheduler.class, OutboxLaneSchedulerTest::schedulingTaskScheduler)
                .withPropertyValues(
                        "riccon.worker.outbox.legacy-all-lane-scheduling=" + legacy,
                        "riccon.worker.outbox.scheduling-enabled=" + schedulingEnabled)
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .as("legacy=%s schedulingEnabled=%s 上下文必须启动成功", legacy, schedulingEnabled)
                            .isNull();
                    assertThat(context.containsBean("outboxLaneScheduler"))
                            .as("legacy=%s schedulingEnabled=%s 分通道调度器", legacy, schedulingEnabled)
                            .isEqualTo(laneSchedulerExpected);
                    assertThat(context.containsBean("outboxScheduler"))
                            .as("legacy=%s schedulingEnabled=%s 旧调度器", legacy, schedulingEnabled)
                            .isEqualTo(legacySchedulerExpected);
                });
    }

    /**
     * 构造测试用轮询配置。
     *
     * @return 轮询配置
     */
    private static OutboxProperties outboxProperties() {
        return new OutboxProperties(
                Duration.ofMillis(500),
                Duration.ofSeconds(5),
                10,
                Duration.ofSeconds(90),
                3,
                List.of(Duration.ofSeconds(2), Duration.ofSeconds(8), Duration.ofSeconds(30)),
                Duration.ofMillis(200),
                "");
    }

    /**
     * 构造指定 heavy 并发的通道配置。
     *
     * @param heavyConcurrency heavy 并发
     * @return 已校验的通道配置
     */
    private static OutboxLaneProperties laneProperties(int heavyConcurrency) {
        OutboxLaneProperties properties = new OutboxLaneProperties();
        properties.setHeavyConcurrency(heavyConcurrency);
        properties.afterPropertiesSet();
        return properties;
    }

    /**
     * 构造返回可取消句柄的调度器，供上下文条件测试使用。
     *
     * @return 调度器
     */
    private static TaskScheduler schedulingTaskScheduler() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenAnswer(invocation -> mock(ScheduledFuture.class));
        return scheduler;
    }

    /** 记录提交任务的 TaskScheduler；只实现固定延迟调度。 */
    private static final class RecordingTaskScheduler implements TaskScheduler {

        /** 已提交任务，按注册顺序。 */
        private final List<Runnable> tasks = new ArrayList<>();

        /** 返回给调度器的句柄，用于断言停止时取消。 */
        private final List<ScheduledFuture<?>> futures = new ArrayList<>();

        /** 不支持一次性调度。 */
        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            throw new UnsupportedOperationException("unexpected one-shot schedule");
        }

        /** 不支持一次性调度。 */
        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            throw new UnsupportedOperationException("unexpected one-shot schedule");
        }

        /** 不支持固定频率调度。 */
        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            throw new UnsupportedOperationException("unexpected fixed-rate schedule");
        }

        /** 不支持固定频率调度。 */
        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            throw new UnsupportedOperationException("unexpected fixed-rate schedule");
        }

        /** 记录固定延迟任务并返回可取消句柄。 */
        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            tasks.add(task);
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            futures.add(future);
            return future;
        }

        /** 不支持不带起点的固定延迟调度。 */
        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            throw new UnsupportedOperationException("unexpected fixed-delay schedule without start time");
        }
    }
}
