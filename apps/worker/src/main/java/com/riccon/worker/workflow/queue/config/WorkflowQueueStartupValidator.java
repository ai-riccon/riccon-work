package com.riccon.worker.workflow.queue.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * M06 运行队列启动校验（ADR-0024 §4.3/§10.1，OPS-M06-002/OPS-M06-022）。
 *
 * <p>以下不变量在启动期 fail-closed，错误信息同时给出环境变量键与所需值，避免客户调高并发后
 * 静默得到旧容量或错误租约行为：
 *
 * <ul>
 *   <li>{@code RICCON_WORKFLOW_WORKER_CONCURRENCY >= 1}；
 *   <li>{@code RICCON_WORKFLOW_CLAIM_BATCH == 1}（v1 每槽位只领 1 个运行）；
 *   <li>独立调度池与心跳池均 ≥ worker 并发；
 *   <li>{@code HEARTBEAT_INTERVAL_SECONDS * 3 <= LEASE_SECONDS}（interval ≤ lease/3）；
 *   <li>claim 轮询与空队列退避为正且退避上限 ≥ 轮询周期；
 *   <li>{@code RICCON_DB_POOL_SIZE >= WORKER_CONCURRENCY + 4}（心跳/outbox/健康检查余量）。
 * </ul>
 *
 * <p>连接池按部署键 {@code RICCON_DB_POOL_SIZE} 校验，而不是反射 Hikari 实际池大小：测试栈会显式
 * 覆盖 {@code spring.datasource.hikari.maximum-pool-size} 以控制容器连接数，生产一致性以合同
 * 部署键为准；部署侧误改 Hikari 自有键时由 RUNBOOK 预检兜底。
 */
@Component
public class WorkflowQueueStartupValidator implements InitializingBean {

    /** 运行队列配置。 */
    private final WorkflowQueueProperties properties;

    /** 部署声明的数据库连接池大小。 */
    private final int databasePoolSize;

    /**
     * 创建校验器。
     *
     * @param properties 运行队列配置
     * @param databasePoolSize 部署声明的数据库连接池大小
     */
    public WorkflowQueueStartupValidator(
            WorkflowQueueProperties properties, @Value("${riccon.db.pool-size:16}") int databasePoolSize) {
        this.properties = properties;
        this.databasePoolSize = databasePoolSize;
    }

    /** 执行全部跨字段校验。 */
    @Override
    public void afterPropertiesSet() {
        require(properties.workerConcurrency() >= 1, "RICCON_WORKFLOW_WORKER_CONCURRENCY must be >= 1");
        require(properties.claimBatch() == 1, "RICCON_WORKFLOW_CLAIM_BATCH must be 1 in v1");
        require(
                properties.schedulerPoolSize() >= properties.workerConcurrency(),
                "RICCON_WORKFLOW_SCHEDULER_POOL_SIZE=" + properties.schedulerPoolSize()
                        + " is below RICCON_WORKFLOW_WORKER_CONCURRENCY=" + properties.workerConcurrency());
        require(
                properties.heartbeatConcurrency() >= properties.workerConcurrency(),
                "RICCON_WORKFLOW_HEARTBEAT_CONCURRENCY=" + properties.heartbeatConcurrency()
                        + " is below RICCON_WORKFLOW_WORKER_CONCURRENCY=" + properties.workerConcurrency());
        require(properties.leaseSeconds() >= 3, "RICCON_WORKFLOW_LEASE_SECONDS must be >= 3");
        require(properties.heartbeatIntervalSeconds() >= 1, "RICCON_WORKFLOW_HEARTBEAT_INTERVAL_SECONDS must be >= 1");
        require(
                (long) properties.heartbeatIntervalSeconds() * 3L <= properties.leaseSeconds(),
                "RICCON_WORKFLOW_HEARTBEAT_INTERVAL_SECONDS=" + properties.heartbeatIntervalSeconds()
                        + " must be <= RICCON_WORKFLOW_LEASE_SECONDS/3=" + (properties.leaseSeconds() / 3));
        require(properties.claimPollIntervalMs() > 0L, "RICCON_WORKFLOW_CLAIM_POLL_INTERVAL_MS must be positive");
        require(
                properties.claimIdleBackoffMaxMs() >= properties.claimPollIntervalMs(),
                "RICCON_WORKFLOW_CLAIM_IDLE_BACKOFF_MAX_MS=" + properties.claimIdleBackoffMaxMs()
                        + " must be >= RICCON_WORKFLOW_CLAIM_POLL_INTERVAL_MS="
                        + properties.claimPollIntervalMs());
        require(properties.shutdownGraceSeconds() >= 0, "RICCON_WORKFLOW_SHUTDOWN_GRACE_SECONDS must be >= 0");
        int requiredPool = properties.workerConcurrency() + 4;
        require(
                databasePoolSize >= requiredPool,
                "RICCON_DB_POOL_SIZE=" + databasePoolSize + " is below RICCON_WORKFLOW_WORKER_CONCURRENCY+4="
                        + requiredPool);
    }

    /**
     * 断言条件，失败时抛出带可操作配置键的启动异常。
     *
     * @param condition 条件
     * @param message 错误信息
     */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("workflow queue startup validation failed: " + message);
        }
    }
}
