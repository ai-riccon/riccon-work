package com.riccon.worker.run.service;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.config.OutboxProperties;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.domain.EventProcessingException;
import com.riccon.worker.run.domain.OutboxEventHandler;
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
import org.springframework.stereotype.Component;

/**
 * Outbox 轮询与事件分发。
 *
 * <p>每轮领取一批事件，逐条打开租户作用域，校验 {@code agent_run} 的租户/类别/状态后交给处理器。
 * 投递语义为 at-least-once：成功写 DONE；可重试失败回到 PENDING 并退避；不可重试或超过上限写 DEAD。
 * 处理器必须幂等，租约过期后的再次领取不得产生重复产物。
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

    /**
     * 创建轮询器。
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
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 执行一轮轮询。
     *
     * @return 本轮领取并处理的事件数量
     */
    public int pollOnce() {
        List<ClaimedOutboxEvent> events = outboxRepository.claim();
        for (ClaimedOutboxEvent event : events) {
            process(event);
        }
        return events.size();
    }

    /**
     * 在租户作用域内处理单条事件。
     *
     * @param event 已领取事件
     */
    private void process(ClaimedOutboxEvent event) {
        TenantId tenantId;
        try {
            tenantId = TenantId.of(event.tenantId());
        } catch (IllegalArgumentException ex) {
            // 租户 ID 非法时无法打开作用域，只能直接终止，避免污染其他租户。
            markDead(event, "invalid tenant id on outbox row");
            return;
        }
        try (TenantContext.TenantScope ignored = TenantContext.open(tenantId)) {
            Optional<OutboxEventHandler> handler = dispatcher.find(event.eventType());
            if (handler.isEmpty()) {
                markDead(event, "unsupported event type: " + event.eventType());
                return;
            }
            Optional<RunRecord> run = runRepository.find(tenantId, event.runId());
            if (run.isEmpty()) {
                markDead(event, "agent_run not found for run");
                return;
            }
            if (run.get().kind() != handler.get().runKind()) {
                markDead(event, "agent_run kind mismatch for event type: " + event.eventType());
                return;
            }
            boolean terminalRun = run.get().isTerminal();
            if (terminalRun && !handler.get().handlesTerminalRun()) {
                // 重复投递：运行已到终态，不再次执行。
                markDone(event);
                return;
            }
            if (!terminalRun && !runRepository.markRunning(tenantId, event.runId(), event.attempts())) {
                markDone(event);
                return;
            }
            try {
                handler.get().handle(event);
                if (terminalRun) {
                    // 终态运行的收尾事件只补齐缺失的阶段/审批终态，不得再推进运行状态。
                    markDone(event);
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
                    markDone(event);
                    LOG.info(
                            "outbox.event.paused eventId={} tenantId={} runId={} reason=WAITING_APPROVAL",
                            event.id(),
                            event.tenantId(),
                            event.runId());
                    return;
                }
                runRepository.markSucceeded(tenantId, event.runId());
                markDone(event);
            } catch (EventProcessingException ex) {
                handleFailure(event, tenantId, ex);
            } catch (RuntimeException ex) {
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
                                WorkerErrorCodes.UNEXPECTED, true, "unexpected event processing failure", ex));
            }
        }
    }

    /**
     * 根据可重试标记与尝试次数推进失败状态。
     *
     * @param event 已领取事件
     * @param tenantId 事件租户
     * @param failure 处理失败
     */
    private void handleFailure(ClaimedOutboxEvent event, TenantId tenantId, EventProcessingException failure) {
        boolean retry = failure.retryable() && event.attempts() < properties.maxAttempts();
        String reason = failure.errorCode() + ": " + failure.getMessage();
        if (retry) {
            Instant availableAt = clock.instant().plus(retryDelay(event.attempts()));
            runRepository.markRetryWait(
                    tenantId, event.runId(), event.attempts(), failure.errorCode(), failure.getMessage());
            int updated = outboxRepository.markRetry(event, availableAt, reason);
            if (updated == 0) {
                LOG.warn("outbox.lease_lost eventId={} tenantId={} action=retry", event.id(), event.tenantId());
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
        markDead(event, reason);
    }

    /**
     * 标记事件成功，并检测租约是否已丢失。
     *
     * @param event 已领取事件
     */
    private void markDone(ClaimedOutboxEvent event) {
        int updated = outboxRepository.markDone(event);
        if (updated == 0) {
            LOG.warn("outbox.lease_lost eventId={} tenantId={} action=done", event.id(), event.tenantId());
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
     * 标记事件不可重试终止。
     *
     * @param event 已领取事件
     * @param reason 脱敏原因
     */
    private void markDead(ClaimedOutboxEvent event, String reason) {
        int updated = outboxRepository.markDead(event, reason);
        if (updated == 0) {
            LOG.warn("outbox.lease_lost eventId={} tenantId={} action=dead", event.id(), event.tenantId());
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
