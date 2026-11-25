package com.riccon.worker.workflow.queue.service;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import com.riccon.worker.workflow.heartbeat.domain.WorkflowLease;
import com.riccon.worker.workflow.heartbeat.service.WorkflowLeaseHeartbeat;
import com.riccon.worker.workflow.queue.config.WorkflowQueueProperties;
import com.riccon.worker.workflow.queue.domain.ClaimedWorkflowRun;
import com.riccon.worker.workflow.queue.domain.WorkflowQueueLimits;
import com.riccon.worker.workflow.queue.domain.WorkflowRunClaimState;
import com.riccon.worker.workflow.queue.domain.WorkflowRunExecutor;
import com.riccon.worker.workflow.queue.repository.WorkflowRunQueueRepository;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 单槽位运行领取与执行编排（ADR-0024 §4.3，TM-81/TM-85）。
 *
 * <p>一次调用只领取 1 个运行：非法租户 fail-closed；合法租户立即打开上下文并核对
 * {@code status/lease_owner} 防止重复执行；{@code claim_attempts} 超过上限时推进
 * {@code WF_INTERNAL} 终态；随后先启动心跳再调用执行器。所有异常都转换为稳定指标并返回
 * “本轮已处理/未领取”，绝不逃逸到调度线程，避免固定延迟任务被取消。
 *
 * <p>执行器是 T06b 端口：本任务没有实现 Bean 时 {@link #executorAvailable()} 为 false，
 * 调度器不领取任何运行，避免把 PENDING 运行领走后无人推进。
 */
@Component
public class WorkflowRunQueuePoller {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowRunQueuePoller.class);

    /** claim 失败原因：非法租户。 */
    static final String REASON_INVALID_TENANT = "INVALID_TENANT";

    /** claim 失败原因：数据库异常。 */
    static final String REASON_DB_ERROR = "DB_ERROR";

    /** claim 失败原因：领取后的处理异常。 */
    static final String REASON_CLAIM_EXCEPTION = "CLAIM_EXCEPTION";

    /** 租约标识最大长度，与 {@code workflow_run.lease_owner} VARCHAR(128) 对齐。 */
    private static final int MAX_LEASE_OWNER_LENGTH = 128;

    /** 队列仓储。 */
    private final WorkflowRunQueueRepository repository;

    /** 租约心跳。 */
    private final WorkflowLeaseHeartbeat heartbeat;

    /** 部署配置。 */
    private final WorkflowQueueProperties properties;

    /** 指标。 */
    private final WorkflowQueueMetrics metrics;

    /** 审计。 */
    private final WorkflowQueueAuditor auditor;

    /** UTC 时钟。 */
    private final Clock clock;

    /** 执行器提供者；生产环境由 T06b 注册。 */
    private final ObjectProvider<WorkflowRunExecutor> executorProvider;

    /** 测试注入的直接执行器；生产为空。 */
    private final WorkflowRunExecutor directExecutor;

    /** 本实例租约前缀，进程生命周期内稳定。 */
    private final String leaseOwnerPrefix;

    /**
     * Spring 构造器。
     *
     * @param repository 队列仓储
     * @param heartbeat 租约心跳
     * @param properties 部署配置
     * @param metrics 指标
     * @param auditor 审计
     * @param clock UTC 时钟
     * @param executorProvider 执行器提供者
     */
    @Autowired
    public WorkflowRunQueuePoller(
            WorkflowRunQueueRepository repository,
            WorkflowLeaseHeartbeat heartbeat,
            WorkflowQueueProperties properties,
            WorkflowQueueMetrics metrics,
            WorkflowQueueAuditor auditor,
            Clock clock,
            ObjectProvider<WorkflowRunExecutor> executorProvider) {
        this(repository, heartbeat, properties, metrics, auditor, clock, executorProvider, null);
    }

    /**
     * 测试构造器：直接指定执行器，便于单元/集成测试精确断言调用顺序。
     *
     * @param repository 队列仓储
     * @param heartbeat 租约心跳
     * @param properties 部署配置
     * @param metrics 指标
     * @param auditor 审计
     * @param clock UTC 时钟
     * @param executor 执行器
     */
    WorkflowRunQueuePoller(
            WorkflowRunQueueRepository repository,
            WorkflowLeaseHeartbeat heartbeat,
            WorkflowQueueProperties properties,
            WorkflowQueueMetrics metrics,
            WorkflowQueueAuditor auditor,
            Clock clock,
            WorkflowRunExecutor executor) {
        this(repository, heartbeat, properties, metrics, auditor, clock, null, executor);
    }

    /**
     * 内部统一构造器。
     *
     * @param repository 队列仓储
     * @param heartbeat 租约心跳
     * @param properties 部署配置
     * @param metrics 指标
     * @param auditor 审计
     * @param clock UTC 时钟
     * @param executorProvider 执行器提供者
     * @param directExecutor 测试执行器
     */
    private WorkflowRunQueuePoller(
            WorkflowRunQueueRepository repository,
            WorkflowLeaseHeartbeat heartbeat,
            WorkflowQueueProperties properties,
            WorkflowQueueMetrics metrics,
            WorkflowQueueAuditor auditor,
            Clock clock,
            ObjectProvider<WorkflowRunExecutor> executorProvider,
            WorkflowRunExecutor directExecutor) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.auditor = Objects.requireNonNull(auditor, "auditor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.executorProvider = executorProvider;
        this.directExecutor = directExecutor;
        this.leaseOwnerPrefix = resolveLeaseOwnerPrefix(properties.instanceId());
    }

    /**
     * 执行器是否可用。
     *
     * @return true 表示 T06b 引擎已注册
     */
    public boolean executorAvailable() {
        return executor() != null;
    }

    /**
     * 执行一个槽位的一轮领取。
     *
     * @param slot 槽位序号；只用于租约标识与日志
     * @return true 表示本轮领取到了运行（无论是否执行成功）
     */
    public boolean pollOnce(int slot) {
        if (!properties.claimEnabled()) {
            return false;
        }
        if (!executorAvailable()) {
            return false;
        }
        Optional<ClaimedWorkflowRun> claimed;
        try {
            claimed = repository.claim(leaseOwner(slot), Duration.ofSeconds(Math.max(1, properties.leaseSeconds())));
        } catch (DataAccessException ex) {
            metrics.claimFailure(REASON_DB_ERROR);
            LOG.warn(
                    "workflow.claim.db_failed slot={} error={}",
                    slot,
                    ex.getClass().getSimpleName());
            return false;
        } catch (RuntimeException ex) {
            metrics.claimFailure(REASON_CLAIM_EXCEPTION);
            LOG.error("workflow.claim.unexpected_failed slot={}", slot, ex);
            return false;
        }
        if (claimed.isEmpty()) {
            return false;
        }
        try {
            process(claimed.get(), slot);
        } catch (RuntimeException ex) {
            metrics.claimFailure(REASON_CLAIM_EXCEPTION);
            LOG.error(
                    "workflow.claim.process_failed runId={} tenantId={} slot={}",
                    claimed.get().runId(),
                    claimed.get().tenantId(),
                    slot,
                    ex);
        }
        return true;
    }

    /**
     * 处理已领取运行：租户校验、重复领取防护、WF_INTERNAL 兜底、心跳与执行器调用。
     *
     * @param claim 领取元数据
     * @param slot 槽位序号
     */
    private void process(ClaimedWorkflowRun claim, int slot) {
        TenantId tenantId;
        try {
            tenantId = TenantId.of(claim.tenantId());
        } catch (IllegalArgumentException ex) {
            // TM-85：非法租户不打开上下文、不读取业务列，只记指标与 platform denied 审计。
            metrics.claimFailure(REASON_INVALID_TENANT);
            auditor.claimDenied(claim.runId(), claim.tenantId());
            LOG.warn("workflow.claim.invalid_tenant runId={} slot={}", claim.runId(), slot);
            return;
        }
        WorkflowRunClaimState state = TenantContext.callWithTenant(
                        tenantId, () -> repository.findClaimState(tenantId, claim.runId()))
                .orElse(null);
        if (state == null) {
            LOG.warn("workflow.claim.state_missing runId={} tenantId={}", claim.runId(), tenantId.value());
            return;
        }
        if (state.status() != WorkflowRunStatus.RUNNING || !claim.leaseOwner().equals(state.leaseOwner())) {
            // 租约已被取消/重领：当前槽位必须放弃，否则会与新持有者双执行。
            LOG.warn(
                    "workflow.claim.lease_race runId={} tenantId={} status={} expectedOwner={} actualOwner={}",
                    claim.runId(),
                    tenantId.value(),
                    state.status(),
                    claim.leaseOwner(),
                    state.leaseOwner());
            return;
        }
        if (state.claimAttempts() > WorkflowQueueLimits.MAX_CLAIM_ATTEMPTS) {
            boolean marked = TenantContext.callWithTenant(
                    tenantId,
                    () -> repository.markClaimExhausted(
                            tenantId, claim.runId(), claim.leaseOwner(), state.currentNodeKey(), clock.instant()));
            if (!marked) {
                LOG.warn("workflow.claim.exhausted_race runId={} tenantId={}", claim.runId(), tenantId.value());
                return;
            }
            metrics.claimExhausted();
            auditor.claimExhausted(tenantId, claim.runId(), state.currentNodeKey());
            LOG.error(
                    "workflow.claim.exhausted runId={} tenantId={} claimAttempts={} nodeKey={} errorCode=WF_INTERNAL",
                    claim.runId(),
                    tenantId.value(),
                    state.claimAttempts(),
                    state.currentNodeKey());
            return;
        }
        WorkflowRunExecutor executor = executor();
        if (executor == null) {
            LOG.warn("workflow.claim.executor_missing runId={} slot={}", claim.runId(), slot);
            return;
        }
        // 先启动心跳（首轮同步续期）再进入执行器；租约丢失/取消时不执行。
        WorkflowLease lease = heartbeat.start(claim);
        if (lease.lost() || lease.cancelled()) {
            LOG.warn(
                    "workflow.claim.lease_unavailable runId={} tenantId={} lost={} cancelled={}",
                    claim.runId(),
                    tenantId.value(),
                    lease.lost(),
                    lease.cancelled());
            lease.close();
            return;
        }
        metrics.slotAcquired();
        try {
            // 执行器在租户作用域内运行；引擎只通过 TenantJdbc 访问业务数据（TM-85）。
            TenantContext.runWithTenant(tenantId, () -> executor.execute(claim, lease));
        } catch (RuntimeException ex) {
            // 执行异常不在这里改运行状态：保留租约等待过期重领，由 claim_attempts 兜底为 WF_INTERNAL。
            LOG.error("workflow.execution.failed runId={} tenantId={}", claim.runId(), tenantId.value(), ex);
        } finally {
            metrics.slotReleased();
            lease.close();
        }
    }

    /**
     * 解析执行器。
     *
     * @return 执行器；未注册时为空
     */
    private WorkflowRunExecutor executor() {
        if (directExecutor != null) {
            return directExecutor;
        }
        return executorProvider == null ? null : executorProvider.getIfAvailable();
    }

    /**
     * 生成槽位租约标识。
     *
     * @param slot 槽位序号
     * @return 实例前缀 + 槽位，长度不超过列宽
     */
    String leaseOwner(int slot) {
        String owner = leaseOwnerPrefix + "#slot-" + slot;
        return owner.length() <= MAX_LEASE_OWNER_LENGTH ? owner : owner.substring(0, MAX_LEASE_OWNER_LENGTH);
    }

    /**
     * 优先使用显式实例标识，否则使用主机名 + PID。
     *
     * @param configured 配置值
     * @return 非空实例前缀
     */
    static String resolveLeaseOwnerPrefix(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        long pid = ProcessHandle.current().pid();
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + pid;
        } catch (UnknownHostException ex) {
            return "worker-" + pid;
        }
    }
}
