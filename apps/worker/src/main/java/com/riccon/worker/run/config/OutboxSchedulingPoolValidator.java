package com.riccon.worker.run.config;

import com.riccon.worker.run.domain.OutboxLane;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Outbox 分通道调度与 Spring 调度池容量校验（M05-B1 F-003）。
 *
 * <p>{@link com.riccon.worker.run.service.OutboxLaneScheduler} 为 default 通道提交 1 个固定延迟任务，
 * 为 heavy 通道提交 {@code heavy-concurrency} 个任务；若 {@code spring.task.scheduling.pool.size}
 * 小于任务数，多出的任务会排队等待，实际并发低于配置值且没有错误提示。本类在启动期比较两者，
 * 不满足时 fail-fast 并给出可直接照做的环境变量提示，避免客户把 heavy 并发从 2 调到 4–8 后
 * 静默得到旧容量。
 *
 * <p>只在分通道调度生效时校验：{@code scheduling-enabled=false} 时没有调度任务，
 * {@code legacy-all-lane-scheduling=true} 时只有旧调度器提交 1 个任务，两种情况都不受
 * heavy 并发影响，因此跳过校验。
 */
@Component
public class OutboxSchedulingPoolValidator implements InitializingBean {

    /** Spring 调度线程池大小，与 {@code spring.task.scheduling.pool.size} 同源。 */
    private final int poolSize;

    /** 是否启用 outbox 调度。 */
    private final boolean schedulingEnabled;

    /** 是否启用旧的全事件调度（回滚窗口）。 */
    private final boolean legacyAllLaneScheduling;

    /** 通道与 heavy 并发配置。 */
    private final OutboxLaneProperties laneProperties;

    /**
     * 创建调度池校验器。
     *
     * @param poolSize Spring 调度线程池大小
     * @param schedulingEnabled 是否启用 outbox 调度
     * @param legacyAllLaneScheduling 是否启用旧的全事件调度
     * @param laneProperties 通道与 heavy 并发配置
     */
    public OutboxSchedulingPoolValidator(
            @Value("${spring.task.scheduling.pool.size:1}") int poolSize,
            @Value("${riccon.worker.outbox.scheduling-enabled:true}") boolean schedulingEnabled,
            @Value("${riccon.worker.outbox.legacy-all-lane-scheduling:false}") boolean legacyAllLaneScheduling,
            OutboxLaneProperties laneProperties) {
        this.poolSize = poolSize;
        this.schedulingEnabled = schedulingEnabled;
        this.legacyAllLaneScheduling = legacyAllLaneScheduling;
        this.laneProperties = laneProperties;
    }

    /**
     * 校验分通道调度所需的最小池大小。
     *
     * <p>default 通道固定 1 个任务，heavy 通道按配置提交 N 个任务；只启用 heavy 的分实例部署
     * 只需要 N 个线程。错误信息同时给出两个可操作的配置键，避免只改池或只改并发造成再次漂移。
     */
    @Override
    public void afterPropertiesSet() {
        if (!schedulingEnabled || legacyAllLaneScheduling) {
            return;
        }
        int heavyConcurrency = laneProperties.heavyConcurrency();
        boolean defaultLaneEnabled = laneProperties.enabledLanes().contains(OutboxLane.DEFAULT);
        int requiredPoolSize = defaultLaneEnabled ? 1 + heavyConcurrency : heavyConcurrency;
        if (poolSize < requiredPoolSize) {
            throw new IllegalStateException("spring.task.scheduling.pool.size=" + poolSize
                    + " is below required " + requiredPoolSize + " for "
                    + (defaultLaneEnabled ? "1 default lane + " : "")
                    + "heavy-concurrency=" + heavyConcurrency
                    + "; set RICCON_WORKER_SCHEDULER_POOL_SIZE >= " + requiredPoolSize
                    + " or lower RICCON_WORKER_OUTBOX_HEAVY_CONCURRENCY");
        }
    }
}
