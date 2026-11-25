package com.riccon.worker.workflow.queue.config;

import com.riccon.worker.workflow.queue.domain.WorkflowTaskScheduler;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M06 运行队列与心跳线程池配置（ADR-0024 §4.3）。
 *
 * <p>运行槽位与心跳各自使用独立的 {@link ScheduledExecutorService}，不复用
 * {@code spring.task.scheduling.pool.size} 或 outbox 调度线程：
 *
 * <ul>
 *   <li>{@code workflowRunSlotExecutor}：大小 = {@code RICCON_WORKFLOW_SCHEDULER_POOL_SIZE}，
 *       每槽位同一时刻只跑一个领取/执行任务；
 *   <li>{@code workflowHeartbeatExecutor}：大小 = {@code RICCON_WORKFLOW_HEARTBEAT_CONCURRENCY}，
 *       与槽位解耦，避免执行槽位占满后心跳无法续期；
 *   <li>两者均为守护线程，关闭由 Spring destroyMethod 与调度器 {@code @PreDestroy} 协作。
 * </ul>
 *
 * <p>不把线程池暴露为 Spring {@code TaskScheduler} Bean，避免 {@code @Scheduled} 自动选中并复用，
 * 破坏“独立调度池”的语义。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkflowQueueProperties.class)
public class WorkflowQueueConfiguration {

    /**
     * 运行槽位执行池。
     *
     * @param properties 运行队列配置
     * @return 固定大小守护线程池
     */
    @Bean(name = "workflowRunSlotExecutor", destroyMethod = "shutdown")
    public ScheduledExecutorService workflowRunSlotExecutor(WorkflowQueueProperties properties) {
        return Executors.newScheduledThreadPool(properties.schedulerPoolSize(), daemonThreadFactory("riccon-wf-slot-"));
    }

    /**
     * 租约心跳执行池。
     *
     * @param properties 运行队列配置
     * @return 固定大小守护线程池
     */
    @Bean(name = "workflowHeartbeatExecutor", destroyMethod = "shutdown")
    public ScheduledExecutorService workflowHeartbeatExecutor(WorkflowQueueProperties properties) {
        return Executors.newScheduledThreadPool(
                properties.heartbeatConcurrency(), daemonThreadFactory("riccon-wf-heartbeat-"));
    }

    /**
     * 运行槽位调度端口。
     *
     * @param executor 运行槽位执行池
     * @return 调度端口
     */
    @Bean(name = "workflowRunTaskScheduler")
    public WorkflowTaskScheduler workflowRunTaskScheduler(
            @Qualifier("workflowRunSlotExecutor") ScheduledExecutorService executor) {
        return (task, delayMillis) -> executor.schedule(task, delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * 心跳调度端口。
     *
     * @param executor 心跳执行池
     * @return 调度端口
     */
    @Bean(name = "workflowHeartbeatTaskScheduler")
    public WorkflowTaskScheduler workflowHeartbeatTaskScheduler(
            @Qualifier("workflowHeartbeatExecutor") ScheduledExecutorService executor) {
        return (task, delayMillis) -> executor.schedule(task, delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * 创建带序号与固定前缀的守护线程工厂。
     *
     * @param prefix 线程名前缀
     * @return 线程工厂
     */
    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
