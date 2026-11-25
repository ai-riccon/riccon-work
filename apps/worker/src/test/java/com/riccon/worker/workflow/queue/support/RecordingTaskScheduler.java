package com.riccon.worker.workflow.queue.support;

import com.riccon.worker.workflow.queue.domain.WorkflowTaskScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.mockito.Mockito;

/**
 * 记录型调度器：把 {@code schedule(task, delay)} 记录为可手动驱动的任务列表。
 *
 * <p>用于精确断言槽位注册数、退避延迟与心跳续期次数，不启动真实线程池、不依赖 {@code sleep}。
 */
public final class RecordingTaskScheduler implements WorkflowTaskScheduler {

    /** 已记录任务。 */
    private final List<Recording> recordings = new ArrayList<>();

    /** {@inheritDoc} */
    @Override
    public ScheduledFuture<?> schedule(Runnable task, long delayMillis) {
        ScheduledFuture<?> future = Mockito.mock(ScheduledFuture.class);
        recordings.add(new Recording(task, delayMillis, future));
        return future;
    }

    /**
     * 返回全部任务（按提交顺序）。
     *
     * @return 记录列表
     */
    public List<Recording> tasks() {
        return recordings;
    }

    /**
     * 返回最近一次提交的任务。
     *
     * @return 记录
     */
    public Recording last() {
        return recordings.get(recordings.size() - 1);
    }

    /**
     * 执行最近一次提交的任务。
     */
    public void runLast() {
        last().task().run();
    }

    /**
     * 单条调度记录。
     *
     * @param task 任务
     * @param delayMillis 延迟毫秒
     * @param future 可取消句柄
     */
    public record Recording(Runnable task, long delayMillis, ScheduledFuture<?> future) {}
}
