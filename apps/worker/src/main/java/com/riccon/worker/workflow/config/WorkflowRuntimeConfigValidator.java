package com.riccon.worker.workflow.config;

import com.riccon.worker.workflow.queue.config.WorkflowQueueProperties;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Worker 侧运行限制与包大小启动校验（ADR-0024 §10.1，OPS-M06-011/OPS-M06-016）。
 *
 * <p>T06a 的 {@code WorkflowQueueStartupValidator} 已覆盖 claim batch、调度/心跳池、lease/3、
 * 轮询/退避与部署连接池键。本类补齐 Worker 消费但 T06a 未校验的运行限制：
 *
 * <ul>
 *   <li>节点/步数/节点超时/运行超时/租户与流程并发/保留天数必须为正；
 *   <li>{@code RICCON_WORKFLOW_DISABLED_REASON} ≤200 字符（契约 {@code WorkflowRuntimeLimits} 上限）；
 *   <li>包大小：{@code RICCON_DB_MAX_ALLOWED_PACKET} ≥8 MiB 且 ≥结果上限 + 1 MiB；
 *   <li>{@code ENABLED=false} 但 {@code CLAIM_ENABLED=true} 只记 WARN：回滚顺序允许“先关 API、再停
 *       Worker 领取”，在途运行仍需收尾；该组合由 feature/claim 指标与 RUNBOOK 告警发现，不作为启动阻断。
 * </ul>
 */
@Component
public class WorkflowRuntimeConfigValidator implements InitializingBean {

    /** 包大小下限：8 MiB。 */
    static final long MIN_PACKET_BYTES = 8L * 1024L * 1024L;

    /** 结果上限之外的 JSON/协议余量：1 MiB。 */
    static final long PACKET_HEADROOM_BYTES = 1024L * 1024L;

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowRuntimeConfigValidator.class);

    /** 运行限制属性。 */
    private final WorkflowRuntimeProperties runtimeProperties;

    /** 队列属性（读取 claimEnabled）。 */
    private final WorkflowQueueProperties queueProperties;

    /** 部署声明的 JDBC/服务端包大小上限。 */
    private final long maxAllowedPacket;

    /**
     * 创建校验器。
     *
     * @param runtimeProperties 运行限制属性
     * @param queueProperties 队列属性
     * @param maxAllowedPacket 部署声明的包大小上限
     */
    public WorkflowRuntimeConfigValidator(
            WorkflowRuntimeProperties runtimeProperties,
            WorkflowQueueProperties queueProperties,
            @Value("${riccon.db.max-allowed-packet:16777216}") long maxAllowedPacket) {
        this.runtimeProperties = Objects.requireNonNull(runtimeProperties, "runtimeProperties must not be null");
        this.queueProperties = Objects.requireNonNull(queueProperties, "queueProperties must not be null");
        this.maxAllowedPacket = maxAllowedPacket;
    }

    /** 执行运行限制、开关组合与包大小校验。 */
    @Override
    public void afterPropertiesSet() {
        validateLimits();
        validateSwitchCombination();
        validatePacketSize();
    }

    /** 校验运行限制为正并防止运行超时秒数溢出。 */
    private void validateLimits() {
        String reason = runtimeProperties.disabledReason();
        require(
                reason == null || reason.length() <= 200,
                "RICCON_WORKFLOW_DISABLED_REASON must be at most 200 characters");
        require(runtimeProperties.maxNodes() >= 1, "RICCON_WORKFLOW_MAX_NODES must be >= 1");
        require(runtimeProperties.maxSteps() >= 1, "RICCON_WORKFLOW_MAX_STEPS must be >= 1");
        require(runtimeProperties.modelTimeoutSeconds() >= 1, "RICCON_WORKFLOW_MODEL_TIMEOUT_SECONDS must be >= 1");
        require(
                runtimeProperties.retrievalTimeoutSeconds() >= 1,
                "RICCON_WORKFLOW_RETRIEVAL_TIMEOUT_SECONDS must be >= 1");
        require(runtimeProperties.mcpTimeoutSeconds() >= 1, "RICCON_WORKFLOW_MCP_TIMEOUT_SECONDS must be >= 1");
        require(runtimeProperties.runTimeoutMinutes() >= 1, "RICCON_WORKFLOW_RUN_TIMEOUT_MINUTES must be >= 1");
        require(
                runtimeProperties.runTimeoutMinutes() <= Integer.MAX_VALUE / 60,
                "RICCON_WORKFLOW_RUN_TIMEOUT_MINUTES=" + runtimeProperties.runTimeoutMinutes()
                        + " would overflow WorkflowRuntimeLimits.runTimeoutSeconds; maximum is "
                        + (Integer.MAX_VALUE / 60));
        require(runtimeProperties.tenantConcurrency() >= 1, "RICCON_WORKFLOW_TENANT_CONCURRENCY must be >= 1");
        require(runtimeProperties.flowConcurrency() >= 1, "RICCON_WORKFLOW_FLOW_CONCURRENCY must be >= 1");
        require(runtimeProperties.resultMaxBytes() >= 1L, "RICCON_WORKFLOW_RESULT_MAX_BYTES must be >= 1");
        require(runtimeProperties.retentionDays() >= 1, "RICCON_WORKFLOW_RETENTION_DAYS must be >= 1");
    }

    /**
     * 检查 API/Worker 开关组合。
     *
     * <p>关闭总开关后仍允许 Worker 继续领取并收尾在途运行，因此这里只记 WARN；真正的“停止领取”由
     * {@code RICCON_WORKFLOW_CLAIM_ENABLED=false} 与优雅停机完成（ADR-0024 §4.8/§10.4）。
     */
    private void validateSwitchCombination() {
        if (!runtimeProperties.enabled() && queueProperties.claimEnabled()) {
            LOG.warn(
                    "RICCON-WORKFLOW-021 workflow disabled but claim enabled;"
                            + " new runs are rejected by API while workers keep draining in-flight runs."
                            + " Set RICCON_WORKFLOW_CLAIM_ENABLED=false for rollback/shutdown.");
        }
    }

    /** 校验包大小声明覆盖结果上限。 */
    private void validatePacketSize() {
        require(
                maxAllowedPacket >= MIN_PACKET_BYTES,
                "RICCON_DB_MAX_ALLOWED_PACKET=" + maxAllowedPacket + " must be >= " + MIN_PACKET_BYTES + " (8 MiB)");
        long required = runtimeProperties.resultMaxBytes() + PACKET_HEADROOM_BYTES;
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
            throw new IllegalStateException("workflow runtime config validation failed: " + message);
        }
    }
}
