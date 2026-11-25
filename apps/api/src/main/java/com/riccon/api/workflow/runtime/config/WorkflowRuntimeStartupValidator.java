package com.riccon.api.workflow.runtime.config;

import com.riccon.api.config.WorkflowProperties;
import java.util.Objects;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * API 侧编排部署配置启动校验（ADR-0024 §10.1，OPS-M06-002/OPS-M06-009/OPS-M06-022）。
 *
 * <p>Worker 的队列/租约不变量由 {@code WorkflowQueueStartupValidator}（T06a）fail-closed；API 在启动时
 * 复查同一组共享部署键，避免“API 正常受理新运行、Worker 才因配置错误拒绝启动”的半可用状态。两侧
 * 错误信息都给出环境变量键与所需值，方便私有化部署直接修正。
 *
 * <p>连接池按部署键 {@code RICCON_DB_POOL_SIZE} 校验（与 T06a 一致），不反射 Hikari 实际池大小：测试栈
 * 会显式覆盖 {@code spring.datasource.hikari.maximum-pool-size}，生产一致性以合同部署键为准；Hikari
 * 自有键被覆盖时由 Worker 的容量指标与 RUNBOOK 预检兜底。
 *
 * <p>包大小规则（OPS-M06-011）：JDBC 与服务端 {@code max_allowed_packet} 至少 8 MiB，且至少为
 * {@code RICCON_WORKFLOW_RESULT_MAX_BYTES} + 1 MiB JSON/协议余量。服务端实际变量由 T16b 的预检脚本
 * 与 RUNBOOK 核对，本类只校验部署声明值，保证“声明通过、运行时超限”不会静默发生。
 */
@Component
public class WorkflowRuntimeStartupValidator implements InitializingBean {

    /** 包大小下限：8 MiB（ADR-0024 §10.1）。 */
    static final long MIN_PACKET_BYTES = 8L * 1024L * 1024L;

    /** 结果上限之外的 JSON/协议余量：1 MiB。 */
    static final long PACKET_HEADROOM_BYTES = 1024L * 1024L;

    /** 与 Worker 默认一致的连接池余量（心跳/outbox/健康检查）。 */
    private static final int POOL_RESERVED_CONNECTIONS = 4;

    /** 编排部署配置。 */
    private final WorkflowProperties properties;

    /** 部署声明的数据库连接池大小。 */
    private final int databasePoolSize;

    /** 部署声明的 JDBC/服务端包大小上限。 */
    private final long maxAllowedPacket;

    /**
     * 创建校验器。
     *
     * @param properties 编排部署配置
     * @param databasePoolSize 部署声明的数据库连接池大小
     * @param maxAllowedPacket 部署声明的包大小上限
     */
    public WorkflowRuntimeStartupValidator(
            WorkflowProperties properties,
            @Value("${riccon.db.pool-size:16}") int databasePoolSize,
            @Value("${riccon.db.max-allowed-packet:16777216}") long maxAllowedPacket) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.databasePoolSize = databasePoolSize;
        this.maxAllowedPacket = maxAllowedPacket;
    }

    /** 执行全部跨字段校验，失败时阻断 API 启动。 */
    @Override
    public void afterPropertiesSet() {
        validateLimits();
        validateRuntimeQueueKeys();
        validatePacketSize();
    }

    /**
     * 校验节点/步数/超时/并发/保留等运行限制为正，并保证运行超时秒数不溢出契约整数。
     */
    private void validateLimits() {
        String reason = properties.disabledReason();
        require(
                reason == null || reason.length() <= 200,
                "RICCON_WORKFLOW_DISABLED_REASON must be at most 200 characters");
        require(properties.maxNodes() >= 1, "RICCON_WORKFLOW_MAX_NODES must be >= 1");
        require(properties.maxSteps() >= 1, "RICCON_WORKFLOW_MAX_STEPS must be >= 1");
        require(properties.modelTimeoutSeconds() >= 1, "RICCON_WORKFLOW_MODEL_TIMEOUT_SECONDS must be >= 1");
        require(properties.retrievalTimeoutSeconds() >= 1, "RICCON_WORKFLOW_RETRIEVAL_TIMEOUT_SECONDS must be >= 1");
        require(properties.mcpTimeoutSeconds() >= 1, "RICCON_WORKFLOW_MCP_TIMEOUT_SECONDS must be >= 1");
        require(properties.runTimeoutMinutes() >= 1, "RICCON_WORKFLOW_RUN_TIMEOUT_MINUTES must be >= 1");
        require(
                properties.runTimeoutMinutes() <= Integer.MAX_VALUE / 60,
                "RICCON_WORKFLOW_RUN_TIMEOUT_MINUTES=" + properties.runTimeoutMinutes()
                        + " would overflow WorkflowRuntimeLimits.runTimeoutSeconds; maximum is "
                        + (Integer.MAX_VALUE / 60));
        require(properties.tenantConcurrency() >= 1, "RICCON_WORKFLOW_TENANT_CONCURRENCY must be >= 1");
        require(properties.flowConcurrency() >= 1, "RICCON_WORKFLOW_FLOW_CONCURRENCY must be >= 1");
        require(properties.resultMaxBytes() >= 1L, "RICCON_WORKFLOW_RESULT_MAX_BYTES must be >= 1");
        require(properties.retentionDays() >= 1, "RICCON_WORKFLOW_RETENTION_DAYS must be >= 1");
    }

    /**
     * 校验与 Worker 共享的队列/租约/调度池部署键，避免部署漂移只在 Worker 启动时暴露。
     */
    private void validateRuntimeQueueKeys() {
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
        int requiredPool = properties.workerConcurrency() + POOL_RESERVED_CONNECTIONS;
        require(
                databasePoolSize >= requiredPool,
                "RICCON_DB_POOL_SIZE=" + databasePoolSize + " is below RICCON_WORKFLOW_WORKER_CONCURRENCY+4="
                        + requiredPool);
    }

    /** 校验包大小声明：至少 8 MiB，且覆盖运行结果上限加协议余量。 */
    private void validatePacketSize() {
        require(
                maxAllowedPacket >= MIN_PACKET_BYTES,
                "RICCON_DB_MAX_ALLOWED_PACKET=" + maxAllowedPacket + " must be >= " + MIN_PACKET_BYTES + " (8 MiB)");
        long required = properties.resultMaxBytes() + PACKET_HEADROOM_BYTES;
        require(
                maxAllowedPacket >= required,
                "RICCON_DB_MAX_ALLOWED_PACKET=" + maxAllowedPacket + " must be >= RICCON_WORKFLOW_RESULT_MAX_BYTES"
                        + "+1 MiB=" + required);
    }

    /**
     * 断言条件，失败时抛出带可操作配置键的启动异常。
     *
     * @param condition 条件
     * @param message 错误信息
     */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("workflow runtime startup validation failed: " + message);
        }
    }
}
