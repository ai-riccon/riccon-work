package com.riccon.worker.workflow.metrics.service;

import com.riccon.worker.workflow.config.WorkflowRuntimeProperties;
import com.riccon.worker.workflow.metrics.domain.WorkflowApprovalWaitSnapshot;
import com.riccon.worker.workflow.metrics.repository.WorkflowApprovalWaitRepository;
import com.riccon.worker.workflow.queue.config.WorkflowQueueProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Worker 侧编排运维指标（ADR-0024 §10.2，OPS-M06-003/007/009）。
 *
 * <p>队列深度、最老 PENDING、租约丢失、claim 失败/耗尽与心跳失败已由 T06a 的
 * {@code WorkflowQueueMetrics} 注册；本类补齐 ADR §10.2 的其余固定名称：
 *
 * <ul>
 *   <li>{@code riccon_workflow_approvals_pending}：待审批数；
 *   <li>{@code riccon_workflow_approval_oldest_wait_seconds}：最老审批等待秒数；
 *   <li>{@code riccon_workflow_feature_enabled}：Worker 观测到的总开关（与 API 同名指标比对漂移）；
 *   <li>{@code riccon_workflow_claim_enabled}：Worker 是否仍在领取；用于发现“API 已关但 Worker 仍在领”。
 * </ul>
 *
 * <p>审批聚合采用 1s TTL 缓存：Prometheus 抓取会并发读取多个 Gauge，缓存把每次抓取收敛为一次查询；
 * 数据库瞬时不可用时保留上一份快照并记录 WARN，不让指标读取破坏抓取或审批主路径。feature/claim
 * 开关是静态部署值，不缓存。
 */
@Component
public class WorkflowOperationsMetrics {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowOperationsMetrics.class);

    /** 指标缓存有效期（纳秒）。 */
    private static final long CACHE_TTL_NANOS = 1_000_000_000L;

    /** 待审批等待聚合仓储。 */
    private final WorkflowApprovalWaitRepository approvalWaitRepository;

    /** 运行限制配置（enabled）。 */
    private final WorkflowRuntimeProperties runtimeProperties;

    /** 队列配置（claimEnabled）。 */
    private final WorkflowQueueProperties queueProperties;

    /** 最近一次审批聚合快照。 */
    private volatile WorkflowApprovalWaitSnapshot cachedStats = WorkflowApprovalWaitSnapshot.EMPTY;

    /** 最近一次聚合时间（{@link System#nanoTime()}）；负偏移避免减法溢出。 */
    private volatile long cachedAtNanos = Long.MIN_VALUE / 2;

    /**
     * 创建指标组件并注册 Gauge。
     *
     * @param meterRegistry 指标注册表
     * @param approvalWaitRepository 待审批聚合仓储
     * @param runtimeProperties 运行限制配置
     * @param queueProperties 队列配置
     */
    public WorkflowOperationsMetrics(
            MeterRegistry meterRegistry,
            WorkflowApprovalWaitRepository approvalWaitRepository,
            WorkflowRuntimeProperties runtimeProperties,
            WorkflowQueueProperties queueProperties) {
        Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.approvalWaitRepository =
                Objects.requireNonNull(approvalWaitRepository, "approvalWaitRepository must not be null");
        this.runtimeProperties = Objects.requireNonNull(runtimeProperties, "runtimeProperties must not be null");
        this.queueProperties = Objects.requireNonNull(queueProperties, "queueProperties must not be null");
        Gauge.builder(
                        "riccon_workflow_approvals_pending",
                        this,
                        metrics -> metrics.approvalStats().pending())
                .description("Workflow approvals waiting for a decision")
                .register(meterRegistry);
        Gauge.builder(
                        "riccon_workflow_approval_oldest_wait_seconds",
                        this,
                        metrics -> metrics.approvalStats().oldestWaitSeconds())
                .description("Age of the oldest pending workflow approval")
                .register(meterRegistry);
        Gauge.builder(
                        "riccon_workflow_feature_enabled",
                        runtimeProperties,
                        properties -> properties.enabled() ? 1d : 0d)
                .description("Workflow feature switch as observed by the worker (1=on, 0=off)")
                .register(meterRegistry);
        Gauge.builder(
                        "riccon_workflow_claim_enabled",
                        queueProperties,
                        properties -> properties.claimEnabled() ? 1d : 0d)
                .description("Whether the worker keeps claiming workflow runs (1=claiming, 0=stopped)")
                .register(meterRegistry);
    }

    /**
     * 返回带 TTL 的审批聚合；失败时保留上一份并记录 WARN。
     *
     * @return 审批等待快照
     */
    private WorkflowApprovalWaitSnapshot approvalStats() {
        long now = System.nanoTime();
        if (now - cachedAtNanos < CACHE_TTL_NANOS) {
            return cachedStats;
        }
        synchronized (this) {
            now = System.nanoTime();
            if (now - cachedAtNanos < CACHE_TTL_NANOS) {
                return cachedStats;
            }
            try {
                cachedStats = approvalWaitRepository.waitStats();
                cachedAtNanos = now;
            } catch (RuntimeException ex) {
                // 指标读取不得破坏 Prometheus 抓取；保留上一份快照并保留堆栈供排障。
                LOG.warn("workflow.metrics.approval_wait_failed", ex);
            }
            return cachedStats;
        }
    }
}
