package com.riccon.worker.workflow.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.riccon.worker.workflow.queue.config.WorkflowQueueProperties;
import com.riccon.worker.workflow.queue.support.RecordingTaskScheduler;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 运行槽位调度器：每槽位一个任务、空队列退避、领取复位、异常隔离与停止领取
 * （ADR-0024 §4.3、OPS-M06-002）。
 *
 * <p>使用记录型调度器精确驱动槽位轮次，不启动线程池、不 sleep；延迟序列就是生产空队列退避。
 */
class WorkflowRunSchedulerTest {

    /** 每槽位只注册 1 个任务，且初始延迟等于 claim 轮询周期。 */
    @Test
    @DisplayName("每个槽位注册一个固定延迟任务")
    void start_registersOneTaskPerSlot() {
        WorkflowRunQueuePoller poller = availablePoller();
        when(poller.pollOnce(anyInt())).thenReturn(false);
        RecordingTaskScheduler scheduler = new RecordingTaskScheduler();
        WorkflowRunScheduler runScheduler = new WorkflowRunScheduler(poller, properties(3, true, 120), scheduler);

        runScheduler.start();

        assertThat(scheduler.tasks()).hasSize(3);
        assertThat(scheduler.tasks()).allMatch(task -> task.delayMillis() == 1000L);
        scheduler.tasks().get(0).task().run();
        verify(poller).pollOnce(0);
        assertThat(scheduler.last().delayMillis()).isEqualTo(2000L);
    }

    /** 空队列按 1000 → 2000 → 4000 → 5000 封顶。 */
    @Test
    @DisplayName("空队列指数退避并封顶")
    void emptyQueue_backsOffUpToMaximum() {
        WorkflowRunQueuePoller poller = availablePoller();
        when(poller.pollOnce(anyInt())).thenReturn(false);
        RecordingTaskScheduler scheduler = new RecordingTaskScheduler();
        WorkflowRunScheduler runScheduler = new WorkflowRunScheduler(poller, properties(1, true, 120), scheduler);
        runScheduler.start();

        scheduler.runLast();
        assertThat(scheduler.last().delayMillis()).isEqualTo(2000L);
        scheduler.runLast();
        assertThat(scheduler.last().delayMillis()).isEqualTo(4000L);
        scheduler.runLast();
        assertThat(scheduler.last().delayMillis()).isEqualTo(5000L);
        scheduler.runLast();
        assertThat(scheduler.last().delayMillis()).isEqualTo(5000L);
    }

    /** 领取成功后立即复位到轮询周期。 */
    @Test
    @DisplayName("领取成功复位退避")
    void claimedRun_resetsBackoff() {
        WorkflowRunQueuePoller poller = availablePoller();
        when(poller.pollOnce(anyInt())).thenReturn(false, false, true, false);
        RecordingTaskScheduler scheduler = new RecordingTaskScheduler();
        WorkflowRunScheduler runScheduler = new WorkflowRunScheduler(poller, properties(1, true, 120), scheduler);
        runScheduler.start();

        scheduler.runLast();
        assertThat(scheduler.last().delayMillis()).isEqualTo(2000L);
        scheduler.runLast();
        assertThat(scheduler.last().delayMillis()).isEqualTo(4000L);
        scheduler.runLast();
        assertThat(scheduler.last().delayMillis()).isEqualTo(1000L);
    }

    /** 单轮异常不逃逸，槽位继续按退避运行。 */
    @Test
    @DisplayName("槽位异常被隔离")
    void exception_isIsolated() {
        WorkflowRunQueuePoller poller = availablePoller();
        when(poller.pollOnce(anyInt())).thenThrow(new IllegalStateException("db down"));
        RecordingTaskScheduler scheduler = new RecordingTaskScheduler();
        WorkflowRunScheduler runScheduler = new WorkflowRunScheduler(poller, properties(2, true, 120), scheduler);
        runScheduler.start();

        assertThatCode(scheduler::runLast).doesNotThrowAnyException();
        assertThat(scheduler.last().delayMillis()).isEqualTo(2000L);
        assertThat(runScheduler.activeSlots()).isZero();
    }

    /** claim-enabled=false 时不注册任何槽位。 */
    @Test
    @DisplayName("停止领取时不注册槽位")
    void claimDisabled_registersNoSlots() {
        WorkflowRunQueuePoller poller = availablePoller();
        RecordingTaskScheduler scheduler = new RecordingTaskScheduler();
        WorkflowRunScheduler runScheduler = new WorkflowRunScheduler(poller, properties(2, false, 120), scheduler);

        runScheduler.start();

        assertThat(scheduler.tasks()).isEmpty();
        verify(poller, never()).pollOnce(anyInt());
    }

    /** T06b 引擎未注册时不领取任何运行。 */
    @Test
    @DisplayName("执行器缺失时不注册槽位")
    void executorMissing_registersNoSlots() {
        WorkflowRunQueuePoller poller = mock(WorkflowRunQueuePoller.class);
        when(poller.executorAvailable()).thenReturn(false);
        RecordingTaskScheduler scheduler = new RecordingTaskScheduler();
        WorkflowRunScheduler runScheduler = new WorkflowRunScheduler(poller, properties(2, true, 120), scheduler);

        runScheduler.start();

        assertThat(scheduler.tasks()).isEmpty();
        verify(poller, never()).pollOnce(anyInt());
    }

    /** 停止时取消每槽位当前任务，并阻止后续自调度。 */
    @Test
    @DisplayName("停止取消槽位任务")
    void stop_cancelsSlotsAndStopsRescheduling() {
        WorkflowRunQueuePoller poller = availablePoller();
        when(poller.pollOnce(anyInt())).thenReturn(false);
        RecordingTaskScheduler scheduler = new RecordingTaskScheduler();
        WorkflowRunScheduler runScheduler = new WorkflowRunScheduler(poller, properties(2, true, 0), scheduler);
        runScheduler.start();
        List<RecordingTaskScheduler.Recording> initial = List.copyOf(scheduler.tasks());

        runScheduler.stop();

        assertThat(initial).hasSize(2);
        initial.forEach(task -> verify(task.future()).cancel(false));
        int scheduledAfterStop = scheduler.tasks().size();
        scheduler.runLast();
        assertThat(scheduler.tasks()).hasSize(scheduledAfterStop);
    }

    /**
     * 构造可用执行器的轮询器替身。
     *
     * @return 轮询器 mock
     */
    private static WorkflowRunQueuePoller availablePoller() {
        WorkflowRunQueuePoller poller = mock(WorkflowRunQueuePoller.class);
        when(poller.executorAvailable()).thenReturn(true);
        return poller;
    }

    /**
     * 构造测试配置。
     *
     * @param concurrency 并发
     * @param claimEnabled 是否领取
     * @param grace 关闭宽限秒
     * @return 配置
     */
    private static WorkflowQueueProperties properties(int concurrency, boolean claimEnabled, int grace) {
        return new WorkflowQueueProperties(
                concurrency, 1, 1000L, 5000L, concurrency, concurrency, 30, 90, claimEnabled, grace, "test");
    }
}
