package com.riccon.worker.workflow.queue.config;

import com.riccon.worker.workflow.queue.domain.WorkflowTaskScheduler;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

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
 * <p>workflow 槽位/心跳线程池不暴露为 Spring {@code TaskScheduler} Bean，避免 {@code @Scheduled}
 * 自动选中并复用，破坏“独立调度池”的语义；共享 {@code taskScheduler} 只供 outbox 与
 * {@code @Scheduled} 使用。
 *
 * <p>F-001 修复：Boot 的 {@code TaskSchedulingAutoConfiguration} 在发现任意
 * {@code ScheduledExecutorService} Bean 时不再创建默认 {@code taskScheduler}；而 outbox
 * {@code OutboxLaneScheduler} 与 {@code @Scheduled} 都依赖该 Bean。这里显式提供名为
 * {@code taskScheduler} 的 {@link ThreadPoolTaskScheduler}，参数仍取
 * {@code spring.task.scheduling.*}，与 Boot 自动配置等价；workflow 槽位池/心跳池继续用
 * {@code @Qualifier} 注入，两者不共享线程。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkflowQueueProperties.class)
public class WorkflowQueueConfiguration {

    /**
     * 显式提供 outbox 与 {@code @Scheduled} 使用的共享调度池。
     *
     * <p>该池不是 workflow 槽位池或心跳池：workflow 使用下方两个独立
     * {@link ScheduledExecutorService}（按 {@code @Qualifier} 注入）。若不显式提供，定义
     * {@code ScheduledExecutorService} 会让 Boot 的 {@code TaskSchedulingAutoConfiguration}
     * 回退，默认配置下 {@code OutboxLaneScheduler} 启动失败（F-001/REV-TASK-0224-001）。
     *
     * @param poolSize 调度池大小，取 {@code spring.task.scheduling.pool.size}
     * @param threadNamePrefix 线程名前缀，取 {@code spring.task.scheduling.thread-name-prefix}
     * @return Spring 共享调度池
     */
    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(
            @Value("${spring.task.scheduling.pool.size:1}") int poolSize,
            @Value("${spring.task.scheduling.thread-name-prefix:riccon-sched-}") String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

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
