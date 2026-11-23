package com.riccon.worker.connector.service;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.knowledge.ConnectorSyncTrigger;
import com.riccon.worker.common.UuidV7;
import com.riccon.worker.connector.config.ConnectorProperties;
import com.riccon.worker.connector.domain.ConnectorRecord;
import com.riccon.worker.connector.domain.DueConnector;
import com.riccon.worker.connector.repository.ConnectorRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 连接器固定周期调度。
 *
 * <p>{@code schedule_enabled=true} 且状态 ACTIVE 的连接器按部署
 * {@code RICCON_CONNECTOR_SYNC_INTERVAL_MINUTES} 计算 {@code next_sync_at}；Worker 用条件更新领取到期行，
 * 多实例只有一个扫描者成功。不提供 per-connector cron（ADR-0023 第 6.3 节）。
 *
 * <p>扫描是跨租户只读查询（与 outbox 领取同类例外），随后在租户作用域内写事件与审计。
 */
@Component
public class ConnectorSyncScheduler {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ConnectorSyncScheduler.class);

    /** 单轮最多领取的到期连接器数。 */
    private static final int DUE_BATCH_SIZE = 20;

    /** 仓储。 */
    private final ConnectorRepository connectorRepository;

    /** 配置。 */
    private final ConnectorProperties properties;

    /** 事件发布。 */
    private final ConnectorEventPublisher eventPublisher;

    /** 审计。 */
    private final ConnectorAuditWriter auditWriter;

    /** 时钟。 */
    private final Clock clock;

    /**
     * 创建调度器。
     *
     * @param connectorRepository 仓储
     * @param properties 配置
     * @param eventPublisher 事件发布
     * @param auditWriter 审计
     * @param clock 时钟
     */
    public ConnectorSyncScheduler(
            ConnectorRepository connectorRepository,
            ConnectorProperties properties,
            ConnectorEventPublisher eventPublisher,
            ConnectorAuditWriter auditWriter,
            Clock clock) {
        this.connectorRepository = Objects.requireNonNull(connectorRepository, "connectorRepository must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 定时扫描到期连接器；固定周期由部署调度器控制，单轮最多 20 个。
     */
    @Scheduled(
            fixedDelayString = "${riccon.connector.scheduler-fixed-delay-ms:60000}",
            initialDelayString = "${riccon.connector.scheduler-initial-delay-ms:15000}")
    public void scheduleDue() {
        if (!properties.enabled() || !properties.schedulerEnabled()) {
            return;
        }
        Instant now = clock.instant();
        List<DueConnector> due;
        try {
            due = connectorRepository.findDue(now, DUE_BATCH_SIZE);
        } catch (RuntimeException exception) {
            LOG.error("connector.scheduler.scan_failed", exception);
            return;
        }
        int scheduled = 0;
        for (DueConnector candidate : due) {
            try {
                if (scheduleOne(candidate, now)) {
                    scheduled++;
                }
            } catch (RuntimeException exception) {
                // 单连接器失败不阻塞其他租户；下一轮继续尝试。
                LOG.error("connector.scheduler.connector_failed connectorId={}", candidate.connectorId(), exception);
            }
        }
        if (scheduled > 0) {
            LOG.info("connector.scheduler.scheduled count={}", scheduled);
        }
    }

    /**
     * 领取并投递单个连接器。
     *
     * @param candidate 到期项
     * @param now 当前时间
     * @return 是否投递
     */
    private boolean scheduleOne(DueConnector candidate, Instant now) {
        TenantId tenantId = TenantId.of(candidate.tenantId());
        Instant nextAt = now.plus(Duration.ofMinutes(properties.syncIntervalMinutes()));
        try (TenantContext.TenantScope ignored = TenantContext.open(tenantId)) {
            if (!connectorRepository.claimDue(tenantId, candidate.connectorId(), candidate.dueAt(), nextAt)) {
                return false;
            }
            if (connectorRepository.hasPendingSyncEvent(tenantId, candidate.connectorId())) {
                // 手动同步已排队：本轮不重复投递，next_sync_at 已推进。
                return false;
            }
            ConnectorRecord connector = connectorRepository
                    .findConnector(tenantId, candidate.connectorId())
                    .orElse(null);
            if (connector == null || connector.deletedAt() != null) {
                return false;
            }
            UUID actorId = connector.createdBy() != null
                    ? connector.createdBy()
                    : connectorRepository.findFirstActiveUserId(tenantId).orElse(null);
            if (actorId == null) {
                LOG.warn("connector.scheduler.no_actor connectorId={}", connector.id());
                return false;
            }
            UUID syncId = UuidV7.generate();
            String traceId = "conn-" + syncId.toString().replace("-", "");
            try {
                eventPublisher.enqueueSync(tenantId, connector, syncId, actorId, traceId, now);
            } catch (DuplicateKeyException exception) {
                return false;
            }
            auditWriter.syncStarted(tenantId, connector.id(), syncId, ConnectorSyncTrigger.SCHEDULE, traceId);
            return true;
        }
    }
}
