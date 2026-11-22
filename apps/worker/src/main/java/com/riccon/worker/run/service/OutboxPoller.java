package com.riccon.worker.run.service;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.config.OutboxProperties;
import com.riccon.worker.run.config.OutboxLaneProperties;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.domain.EventProcessingException;
import com.riccon.worker.run.domain.OutboxEventHandler;
import com.riccon.worker.run.domain.OutboxLane;
import com.riccon.worker.run.domain.RunRecord;
import com.riccon.worker.run.domain.WorkerErrorCodes;
import com.riccon.worker.run.repository.OutboxRepository;
import com.riccon.worker.run.repository.RunRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Outbox 轮询与事件分发。
 *
 * <p>每轮按通道领取一批事件，逐条打开租户作用域，校验 {@code agent_run} 的租户/类别/状态后交给处理器。
 * 投递语义为 at-least-once：成功写 DONE；可重试失败回到 PENDING 并退避；不可重试或超过上限写 DEAD。
 * 处理器必须幂等，租约过期后的再次领取不得产生重复产物。
 *
 * <p>M05 起：按通道领取（{@link #pollOnce(OutboxLane)}）时启动单事件租约心跳；租约丢失立即中断并
 * 丢弃结果，超过事件通道的最大处理时长（default 30 分钟、heavy 65 分钟）按可重试失败处理。
 * 无通道的 {@link #pollOnce()} 保持旧行为，供既有集成测试与回滚窗口使用。
 */
@Component
public class OutboxPoller {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPoller.class);

    /** Outbox 仓库。 */
    private final OutboxRepository outboxRepository;

    /** 运行状态仓库。 */
    private final RunRepository runRepository;

    /** 事件处理器路由。 */
    private final OutboxEventDispatcher dispatcher;

    /** 重试与批量配置。 */
    private final OutboxProperties properties;

    /** UTC 时钟。 */
    private final Clock clock;

    /** 租约心跳；旧构造器为 null（测试与回滚窗口不启用）。 */
    private final OutboxLeaseHeartbeat heartbeat;

    /** 通道与最大处理时长；旧构造器为 null。 */
    private final OutboxLaneProperties laneProperties;

    /**
     * 创建不带租约心跳的轮询器（兼容 M02–M04 单元/集成测试）。
     *
     * @param outboxRepository outbox 仓库
     * @param runRepository 运行状态仓库
     * @param dispatcher 处理器路由
     * @param properties 重试配置
     * @param clock UTC 时钟
     */
    public OutboxPoller(
            OutboxRepository outboxRepository,
            RunRepository runRepository,
            OutboxEventDispatcher dispatcher,
            OutboxProperties properties,
            Clock clock) {
        this(outboxRepository, runRepository, dispatcher, properties, clock, null, null);
    }

    /**
     * 创建启用租约心跳的轮询器（Spring 主路径）。
     *
     * @param outboxRepository outbox 仓库
     * @param runRepository 运行状态仓库
     * @param dispatcher 处理器路由
     * @param properties 重试配置
     * @param clock UTC 时钟
     * @param heartbeat 租约心跳
     * @param laneProperties 通道与最大处理时长
     */
    @Autowired
    public OutboxPoller(
            OutboxRepository outboxRepository,
            RunRepository runRepository,
            OutboxEventDispatcher dispatcher,
            OutboxProperties properties,
            Clock clock,
            OutboxLeaseHeartbeat heartbeat,
            OutboxLaneProperties laneProperties) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.heartbeat = heartbeat;
        this.laneProperties = laneProperties;
    }

    /**
     * 执行一轮全通道轮询（旧行为）。
     *
     * @return 本轮领取并处理的事件数量
     */
    public int pollOnce() {
        return pollOnce(null);
    }

    /**
     * 执行指定通道的一轮轮询。
     *
     * @param lane 通道；null 表示领取全部事件
     * @return 本轮领取并处理的事件数量
     */
    public int pollOnce(OutboxLane lane) {
        List<ClaimedOutboxEvent> events = lane == null ? outboxRepository.claim() : outboxRepository.claim(lane);
        for (ClaimedOutboxEvent event : events) {
            process(event, lane);
        }
        return events.size();
    }

    /**
     * 在租户作用域内处理单条事件。
     *
     * @param event 已领取事件
     * @param lane 领取通道；null 表示旧的全事件领取
     */
    private void process(ClaimedOutboxEvent event, OutboxLane lane) {
        TenantId tenantId;
        try {
            tenantId = TenantId.of(event.tenantId());
        } catch (IllegalArgumentException ex) {
            // 租户 ID 非法时无法打开作用域，只能直接终止，避免污染其他租户。
            markDead(event, "invalid tenant id on outbox row", lane);
            return;
        }
        try (TenantContext.TenantScope ignored = TenantContext.open(tenantId)) {
            Optional<OutboxEventHandler> handler = dispatcher.find(event.eventType());
            if (handler.isEmpty()) {
                markDead(event, "unsupported event type: " + event.eventType(), lane);
                return;
            }
            Optional<RunRecord> run = runRepository.find(tenantId, event.runId());
            if (run.isEmpty()) {
                markDead(event, "agent_run not found for run", lane);
                return;
            }
            if (run.get().kind() != handler.get().runKind()) {
                markDead(event, "agent_run kind mismatch for event type: " + event.eventType(), lane);
                return;
            }
            boolean terminalRun = run.get().isTerminal();
            if (terminalRun && !handler.get().handlesTerminalRun()) {
                // 重复投递：运行已到终态，不再次执行。
                markDone(event, lane);
                return;
            }
            if (!terminalRun && !runRepository.markRunning(tenantId, event.runId(), event.attempts())) {
                markDone(event, lane);
                return;
            }
            processHandler(event, tenantId, lane, handler.get(), terminalRun);
        }
    }

    /**
     * 执行处理器并推进租约/运行/outbox 状态。
     *
     * <p>心跳覆盖 handler 执行期间；租约丢失时丢弃结果且不标记 outbox，让新租约持有者重放。
     *
     * @param event 已领取事件
     * @param tenantId 事件租户
     * @param lane 通道
     * @param handler 处理器
     * @param terminalRun 运行是否已是终态
     */
    private void processHandler(
            ClaimedOutboxEvent event,
            TenantId tenantId,
            OutboxLane lane,
            OutboxEventHandler handler,
            boolean terminalRun) {
        OutboxLeaseHeartbeat.Lease lease = startLease(event, lane);
        try {
            try {
                handler.handle(event);
            } catch (EventProcessingException ex) {
                if (discardIfLeaseLost(lease, event, "failure")) {
                    return;
                }
                if (timedOut(lease)) {
                    handleFailure(
                            event,
                            tenantId,
                            new EventProcessingException(
                                    WorkerErrorCodes.PROCESSING_TIMEOUT,
                                    true,
                                    "outbox processing exceeded max-processing"),
                            lane);
                    return;
                }
                handleFailure(event, tenantId, ex, lane);
                return;
            } catch (RuntimeException ex) {
                if (discardIfLeaseLost(lease, event, "failure")) {
                    return;
                }
                if (timedOut(lease)) {
                    handleFailure(
                            event,
                            tenantId,
                            new EventProcessingException(
                                    WorkerErrorCodes.PROCESSING_TIMEOUT,
                                    true,
                                    "outbox processing exceeded max-processing"),
                            lane);
                    return;
                }
                // 未预期异常按有界可重试处理，保留堆栈供人工排查；不得静默吞掉。
                LOG.error(
                        "outbox.event.failed eventId={} tenantId={} runId={} attempts={} errorCode={}",
                        event.id(),
                        event.tenantId(),
                        event.runId(),
                        event.attempts(),
                        WorkerErrorCodes.UNEXPECTED,
                        ex);
                handleFailure(
                        event,
                        tenantId,
                        new EventProcessingException(
                                WorkerErrorCodes.UNEXPECTED, true, "unexpected event processing failure", ex),
                        lane);
                return;
            }
            if (discardIfLeaseLost(lease, event, "success")) {
                return;
            }
            if (timedOut(lease)) {
                handleFailure(
                        event,
                        tenantId,
                        new EventProcessingException(
                                WorkerErrorCodes.PROCESSING_TIMEOUT, true, "outbox processing exceeded max-processing"),
                        lane);
                return;
            }
            if (terminalRun) {
                // 终态运行的收尾事件只补齐缺失的阶段/审批终态，不得再推进运行状态。
                markDone(event, lane);
                LOG.info(
                        "outbox.event.terminal_run_reconciled eventId={} tenantId={} runId={} eventType={}",
                        event.id(),
                        event.tenantId(),
                        event.runId(),
                        event.eventType());
                return;
            }
            // 审批挂起不是失败：运行进入 WAITING_APPROVAL，outbox 事件完成但不标记运行成功。
            Optional<RunRecord> current = runRepository.find(tenantId, event.runId());
            if (current.isPresent()
                    && current.get().status() == com.riccon.platform.domain.research.RunStatus.WAITING_APPROVAL) {
                markDone(event, lane);
                LOG.info(
                        "outbox.event.paused eventId={} tenantId={} runId={} reason=WAITING_APPROVAL",
                        event.id(),
                        event.tenantId(),
                        event.runId());
                return;
            }
            runRepository.markSucceeded(tenantId, event.runId());
            markDone(event, lane);
        } finally {
            if (lease != null) {
                lease.close();
            }
        }
    }

    /**
     * 为事件启动租约心跳；旧构造器路径返回 null。
     *
     * @param event 已领取事件
     * @param lane 通道
     * @return 租约句柄；未启用时为空
     */
    private OutboxLeaseHeartbeat.Lease startLease(ClaimedOutboxEvent event, OutboxLane lane) {
        if (heartbeat == null || laneProperties == null) {
            return null;
        }
        return heartbeat.start(
                event,
                outboxRepository.leaseOwner(lane),
                properties.leaseDuration(),
                laneProperties.maxProcessing(lane));
    }

    /**
     * 租约已丢失时输出 WARN 并返回 true；调用方必须放弃结果。
     *
     * @param lease 租约句柄
     * @param event 已领取事件
     * @param action 丢弃阶段（success/failure）
     * @return 是否应丢弃
     */
    private boolean discardIfLeaseLost(OutboxLeaseHeartbeat.Lease lease, ClaimedOutboxEvent event, String action) {
        if (lease == null || !lease.lost()) {
            return false;
        }
        LOG.warn(
                "outbox.event.lease_lost_discarded eventId={} tenantId={} runId={} action={} errorCode={}",
                event.id(),
                event.tenantId(),
                event.runId(),
                action,
                WorkerErrorCodes.LEASE_LOST);
        return true;
    }

    /**
     * 是否已超过单事件最大处理时长。
     *
     * @param lease 租约句柄
     * @return true 表示按可重试失败处理
     */
    private static boolean timedOut(OutboxLeaseHeartbeat.Lease lease) {
        return lease != null && lease.timedOut();
    }

    /**
     * 根据可重试标记与尝试次数推进失败状态。
     *
     * @param event 已领取事件
     * @param tenantId 事件租户
     * @param failure 处理失败
     * @param lane 通道
     */
    private void handleFailure(
            ClaimedOutboxEvent event, TenantId tenantId, EventProcessingException failure, OutboxLane lane) {
        boolean retry = failure.retryable() && event.attempts() < properties.maxAttempts();
        String reason = failure.errorCode() + ": " + failure.getMessage();
        if (retry) {
            Instant availableAt = clock.instant().plus(retryDelay(event.attempts()));
            runRepository.markRetryWait(
                    tenantId, event.runId(), event.attempts(), failure.errorCode(), failure.getMessage());
            int updated = markRetry(event, availableAt, reason, lane);
            if (updated == 0) {
                LOG.warn(
                        "outbox.lease_lost eventId={} tenantId={} action=retry errorCode={}",
                        event.id(),
                        event.tenantId(),
                        WorkerErrorCodes.LEASE_LOST);
            } else {
                LOG.warn(
                        "outbox.event.retry_scheduled eventId={} tenantId={} runId={} attempts={} errorCode={} availableAt={}",
                        event.id(),
                        event.tenantId(),
                        event.runId(),
                        event.attempts(),
                        failure.errorCode(),
                        availableAt);
            }
            return;
        }
        runRepository.markFailed(tenantId, event.runId(), failure.errorCode(), failure.getMessage());
        markDead(event, reason, lane);
    }

    /**
     * 标记事件成功，并检测租约是否已丢失。
     *
     * @param event 已领取事件
     * @param lane 通道
     */
    private void markDone(ClaimedOutboxEvent event, OutboxLane lane) {
        int updated = lane == null ? outboxRepository.markDone(event) : outboxRepository.markDone(event, lane);
        if (updated == 0) {
            LOG.warn(
                    "outbox.lease_lost eventId={} tenantId={} action=done errorCode={}",
                    event.id(),
                    event.tenantId(),
                    WorkerErrorCodes.LEASE_LOST);
            return;
        }
        LOG.info(
                "outbox.event.completed eventId={} tenantId={} runId={} attempts={}",
                event.id(),
                event.tenantId(),
                event.runId(),
                event.attempts());
    }

    /**
     * 按通道标记可重试失败。
     *
     * @param event 已领取事件
     * @param availableAt 下次可领取时间
     * @param reason 脱敏失败原因
     * @param lane 通道
     * @return 受影响行数
     */
    private int markRetry(ClaimedOutboxEvent event, Instant availableAt, String reason, OutboxLane lane) {
        return lane == null
                ? outboxRepository.markRetry(event, availableAt, reason)
                : outboxRepository.markRetry(event, availableAt, reason, lane);
    }

    /**
     * 标记事件不可重试终止。
     *
     * @param event 已领取事件
     * @param reason 脱敏原因
     * @param lane 通道
     */
    private void markDead(ClaimedOutboxEvent event, String reason, OutboxLane lane) {
        int updated = lane == null
                ? outboxRepository.markDead(event, reason)
                : outboxRepository.markDead(event, reason, lane);
        if (updated == 0) {
            LOG.warn(
                    "outbox.lease_lost eventId={} tenantId={} action=dead errorCode={}",
                    event.id(),
                    event.tenantId(),
                    WorkerErrorCodes.LEASE_LOST);
            return;
        }
        LOG.warn(
                "outbox.event.dead eventId={} tenantId={} runId={} attempts={} reason={}",
                event.id(),
                event.tenantId(),
                event.runId(),
                event.attempts(),
                reason);
    }

    /**
     * 计算第 N 次失败后的退避，附加有界抖动。
     *
     * @param attempts 本次尝试号（1 开始）
     * @return 退避时长
     */
    Duration retryDelay(int attempts) {
        int index = Math.min(Math.max(attempts - 1, 0), properties.retryDelays().size() - 1);
        Duration base = properties.retryDelays().get(index);
        long jitterMillis = properties.retryJitter().toMillis();
        if (jitterMillis <= 0) {
            return base;
        }
        return base.plusMillis(ThreadLocalRandom.current().nextLong(jitterMillis + 1));
    }
}
