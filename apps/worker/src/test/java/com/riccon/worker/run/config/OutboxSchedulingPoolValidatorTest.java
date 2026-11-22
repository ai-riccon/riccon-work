package com.riccon.worker.run.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Outbox 分通道调度池容量校验（M05-B1 F-003）。
 *
 * <p>{@code OutboxLaneScheduler} 为 default 提交 1 个任务、为 heavy 提交 N 个任务；Spring 调度池小于
 * 任务数时后提交的任务会排队，配置的 heavy 并发被静默截断。本测试锁定 1/2/4/8 的边界与不足场景，
 * 并确认关闭调度或回滚到旧调度时不再要求 heavy 容量。
 */
class OutboxSchedulingPoolValidatorTest {

    /** default+heavy 时池大小必须 >= 1 + heavy 并发；等于下限可通过。 */
    @Test
    @DisplayName("default+heavy 的池下限为 1+N")
    void defaultAndHeavy_requiresOnePlusConcurrency() {
        for (int heavyConcurrency : List.of(1, 2, 4, 8)) {
            OutboxLaneProperties laneProperties = laneProperties(heavyConcurrency);
            int required = 1 + heavyConcurrency;
            assertThatCode(() ->
                            validator(required, true, false, laneProperties).afterPropertiesSet())
                    .as("heavy=%s 时 pool=%s 应通过", heavyConcurrency, required)
                    .doesNotThrowAnyException();
        }
    }

    /** 池小于 1 + heavy 并发时启动失败，错误信息给出两个可操作配置键。 */
    @Test
    @DisplayName("池不足时 fail-fast 并给出可读错误")
    void insufficientPool_failsFastWithActionableMessage() {
        for (int heavyConcurrency : List.of(1, 2, 4, 8)) {
            OutboxLaneProperties laneProperties = laneProperties(heavyConcurrency);
            int insufficient = heavyConcurrency;
            assertThatThrownBy(() ->
                            validator(insufficient, true, false, laneProperties).afterPropertiesSet())
                    .as("heavy=%s 时 pool=%s 必须失败", heavyConcurrency, insufficient)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.task.scheduling.pool.size=" + insufficient)
                    .hasMessageContaining("required " + (1 + heavyConcurrency))
                    .hasMessageContaining("RICCON_WORKER_SCHEDULER_POOL_SIZE")
                    .hasMessageContaining("RICCON_WORKER_OUTBOX_HEAVY_CONCURRENCY");
        }
    }

    /** 只启用 heavy 的分实例部署不需要为 default 通道额外留 1 个线程。 */
    @Test
    @DisplayName("只启用 heavy 时池下限为 N")
    void heavyOnly_requiresConcurrency() {
        OutboxLaneProperties laneProperties = laneProperties(2);
        laneProperties.setLanes(List.of("heavy"));
        laneProperties.afterPropertiesSet();
        assertThatCode(() -> validator(2, true, false, laneProperties).afterPropertiesSet())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator(1, true, false, laneProperties).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required 2")
                .hasMessageContaining("heavy-concurrency=2");
    }

    /** 关闭调度或启用旧调度时不提交 heavy 任务，容量校验跳过。 */
    @Test
    @DisplayName("关闭调度或 legacy 回滚时跳过校验")
    void disabledOrLegacy_skipsValidation() {
        OutboxLaneProperties laneProperties = laneProperties(8);
        assertThatCode(() -> validator(1, false, false, laneProperties).afterPropertiesSet())
                .as("scheduling-enabled=false 时没有调度任务")
                .doesNotThrowAnyException();
        assertThatCode(() -> validator(1, true, true, laneProperties).afterPropertiesSet())
                .as("legacy=true 时只有旧调度器的 1 个任务")
                .doesNotThrowAnyException();
    }

    /**
     * 构造任意 heavy 并发的通道配置。
     *
     * @param heavyConcurrency heavy 并发
     * @return 已完成启动校验的配置
     */
    private static OutboxLaneProperties laneProperties(int heavyConcurrency) {
        OutboxLaneProperties properties = new OutboxLaneProperties();
        properties.setHeavyConcurrency(heavyConcurrency);
        properties.afterPropertiesSet();
        return properties;
    }

    /**
     * 构造调度池校验器。
     *
     * @param poolSize Spring 调度池大小
     * @param schedulingEnabled 是否启用 outbox 调度
     * @param legacy 是否启用旧的全事件调度
     * @param laneProperties 通道配置
     * @return 校验器
     */
    private static OutboxSchedulingPoolValidator validator(
            int poolSize, boolean schedulingEnabled, boolean legacy, OutboxLaneProperties laneProperties) {
        return new OutboxSchedulingPoolValidator(poolSize, schedulingEnabled, legacy, laneProperties);
    }
}
