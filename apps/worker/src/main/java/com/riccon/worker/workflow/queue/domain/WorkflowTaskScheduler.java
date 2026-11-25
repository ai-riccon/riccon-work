package com.riccon.worker.workflow.queue.domain;

import java.util.concurrent.ScheduledFuture;

/**
 * 工作流任务调度端口。
 *
 * <p>运行槽位与租约心跳分别持有独立的 {@code ScheduledExecutorService}（ADR-0024 §4.3），
 * 本接口只暴露“延迟执行一次”的最小能力，便于单元测试用记录型实现精确驱动时序，
 * 不把 JDK 线程池细节扩散到业务类。
 */
@FunctionalInterface
public interface WorkflowTaskScheduler {

    /**
     * 延迟提交一次任务。
     *
     * @param task 待执行任务
     * @param delayMillis 延迟毫秒；必须 ≥ 0
     * @return 可取消的句柄
     */
    ScheduledFuture<?> schedule(Runnable task, long delayMillis);
}
