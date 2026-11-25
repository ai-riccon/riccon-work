package com.riccon.worker.workflow.queue.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M06 运行队列启动校验（ADR-0024 §4.3/§10.1）。
 *
 * <p>锁定 batch=1、调度/心跳池 ≥ 并发、interval ≤ lease/3、轮询/退避与连接池 ≥ 并发 + 4
 * 六类不变量；失败信息必须带可操作的环境变量键。
 */
class WorkflowQueueStartupValidatorTest {

    /** 合法默认配置通过。 */
    @Test
    @DisplayName("默认配置通过启动校验")
    void defaults_pass() {
        assertThatCode(() -> validator(properties(10, 1, 1000, 5000, 10, 10, 30, 90, 120), 16)
                        .afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    /** 并发、调度池、心跳池的边界：等于下限通过，低于下限失败。 */
    @Test
    @DisplayName("调度池与心跳池不得低于 worker 并发")
    void pools_mustCoverConcurrency() {
        assertThatCode(() -> validator(properties(4, 1, 1000, 5000, 4, 4, 30, 90, 120), 8)
                        .afterPropertiesSet())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator(properties(4, 1, 1000, 5000, 3, 4, 30, 90, 120), 8)
                        .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_WORKFLOW_SCHEDULER_POOL_SIZE=3")
                .hasMessageContaining("RICCON_WORKFLOW_WORKER_CONCURRENCY=4");
        assertThatThrownBy(() -> validator(properties(4, 1, 1000, 5000, 4, 3, 30, 90, 120), 8)
                        .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_WORKFLOW_HEARTBEAT_CONCURRENCY=3");
    }

    /** v1 每槽位必须只领 1 个运行；batch>1 直接拒绝启动。 */
    @Test
    @DisplayName("claim batch 固定为 1")
    void claimBatch_mustBeOne() {
        for (int batch : List.of(0, 2, 10)) {
            assertThatThrownBy(() -> validator(properties(2, batch, 1000, 5000, 2, 2, 30, 90, 120), 6)
                            .afterPropertiesSet())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("RICCON_WORKFLOW_CLAIM_BATCH must be 1");
        }
    }

    /** 续期间隔必须 ≤ lease/3，避免租约在两次续期之间过期。 */
    @Test
    @DisplayName("心跳间隔不超过租约三分之一")
    void heartbeatInterval_mustNotExceedLeaseThird() {
        assertThatCode(() -> validator(properties(2, 1, 1000, 5000, 2, 2, 30, 90, 120), 6)
                        .afterPropertiesSet())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator(properties(2, 1, 1000, 5000, 2, 2, 31, 90, 120), 6)
                        .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_WORKFLOW_HEARTBEAT_INTERVAL_SECONDS=31")
                .hasMessageContaining("RICCON_WORKFLOW_LEASE_SECONDS/3=30");
        assertThatThrownBy(() -> validator(properties(2, 1, 1000, 5000, 2, 2, 0, 90, 120), 6)
                        .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_WORKFLOW_HEARTBEAT_INTERVAL_SECONDS must be >= 1");
    }

    /** 轮询周期与退避必须为正，退避上限不得小于轮询周期，否则空队列会反向加速。 */
    @Test
    @DisplayName("轮询与退避边界")
    void pollingAndBackoff_mustBeSane() {
        assertThatThrownBy(() -> validator(properties(2, 1, 0, 5000, 2, 2, 30, 90, 120), 6)
                        .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_WORKFLOW_CLAIM_POLL_INTERVAL_MS must be positive");
        assertThatThrownBy(() -> validator(properties(2, 1, 1000, 999, 2, 2, 30, 90, 120), 6)
                        .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_WORKFLOW_CLAIM_IDLE_BACKOFF_MAX_MS=999")
                .hasMessageContaining("RICCON_WORKFLOW_CLAIM_POLL_INTERVAL_MS=1000");
    }

    /** 连接池必须 ≥ 并发 + 4（心跳/outbox/健康检查余量）。 */
    @Test
    @DisplayName("连接池容量规则")
    void databasePool_mustCoverConcurrencyPlusFour() {
        assertThatCode(() -> validator(properties(10, 1, 1000, 5000, 10, 10, 30, 90, 120), 14)
                        .afterPropertiesSet())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator(properties(10, 1, 1000, 5000, 10, 10, 30, 90, 120), 13)
                        .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_DB_POOL_SIZE=13")
                .hasMessageContaining("RICCON_WORKFLOW_WORKER_CONCURRENCY+4=14");
    }

    /** 并发至少 1。 */
    @Test
    @DisplayName("并发至少为 1")
    void concurrency_mustBePositive() {
        assertThatThrownBy(() -> validator(properties(0, 1, 1000, 5000, 0, 0, 30, 90, 120), 4)
                        .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_WORKFLOW_WORKER_CONCURRENCY must be >= 1");
    }

    /**
     * 构造校验器。
     *
     * @param properties 配置
     * @param poolSize 连接池
     * @return 校验器
     */
    private static WorkflowQueueStartupValidator validator(WorkflowQueueProperties properties, int poolSize) {
        return new WorkflowQueueStartupValidator(properties, poolSize);
    }

    /**
     * 构造测试配置。
     *
     * @param concurrency 并发
     * @param batch 批量
     * @param poll 轮询毫秒
     * @param backoff 退避上限
     * @param schedulerPool 调度池
     * @param heartbeatPool 心跳池
     * @param heartbeatInterval 续期间隔秒
     * @param lease 租约秒
     * @param grace 关闭宽限秒
     * @return 配置
     */
    private static WorkflowQueueProperties properties(
            int concurrency,
            int batch,
            long poll,
            long backoff,
            int schedulerPool,
            int heartbeatPool,
            int heartbeatInterval,
            int lease,
            int grace) {
        return new WorkflowQueueProperties(
                concurrency,
                batch,
                poll,
                backoff,
                schedulerPool,
                heartbeatPool,
                heartbeatInterval,
                lease,
                true,
                grace,
                "test-instance");
    }
}
