package com.riccon.api.knowledge.connector.service;

import com.riccon.api.audit.service.AuditService;
import com.riccon.api.auth.repository.TenantDirectoryRepository;
import com.riccon.api.common.UuidV7;
import com.riccon.api.error.ForbiddenException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.knowledge.connector.config.ConnectorProperties;
import com.riccon.api.knowledge.connector.domain.ConnectorRecord;
import com.riccon.api.knowledge.connector.dto.ConnectorSyncAcceptedResponse;
import com.riccon.api.knowledge.connector.repository.ConnectorRepository;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.knowledge.ConnectorSyncTrigger;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 连接器手动同步受理。
 *
 * <p>只写 outbox 并返回 202；同一连接器已有排队/处理中的同步事件时返回 409。并发判定使用数据库行锁 +
 * outbox 状态，不依赖进程内锁，能覆盖多实例 API（ADR-0023 第 6.3/7.3 节）。
 */
@Service
public class ConnectorSyncService {

    /** 连接器仓储。 */
    private final ConnectorRepository connectorRepository;

    /** 同步事件投递。 */
    private final ConnectorSyncEvents syncEvents;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 租户目录。 */
    private final TenantDirectoryRepository tenantDirectoryRepository;

    /** 连接器配置。 */
    private final ConnectorProperties properties;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建同步受理服务。
     *
     * @param connectorRepository 连接器仓储
     * @param syncEvents 同步事件投递
     * @param auditService 审计写入
     * @param tenantDirectoryRepository 租户目录
     * @param properties 连接器配置
     * @param clock 可注入时钟
     */
    public ConnectorSyncService(
            ConnectorRepository connectorRepository,
            ConnectorSyncEvents syncEvents,
            AuditService auditService,
            TenantDirectoryRepository tenantDirectoryRepository,
            ConnectorProperties properties,
            Clock clock) {
        this.connectorRepository = Objects.requireNonNull(connectorRepository, "connectorRepository must not be null");
        this.syncEvents = Objects.requireNonNull(syncEvents, "syncEvents must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.tenantDirectoryRepository =
                Objects.requireNonNull(tenantDirectoryRepository, "tenantDirectoryRepository must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 受理一次手动同步。
     *
     * @param actor 当前主体
     * @param connectorId 连接器 ID
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 202 响应
     */
    @Transactional
    public ConnectorSyncAcceptedResponse requestSync(
            RicconPrincipal actor, UUID connectorId, String traceId, String requestId) {
        if (!properties.enabled()) {
            throw new ConnectorUnavailableException("connector feature is disabled");
        }
        TenantId tenantId = TenantId.of(actor.tenantId());
        boolean activeTenant = tenantDirectoryRepository
                .findById(actor.tenantId())
                .map(tenant -> tenant.isActive())
                .orElse(false);
        if (!activeTenant) {
            throw new ForbiddenException(PermissionCodes.KNOWLEDGE_CONNECTOR_MANAGE);
        }
        ConnectorRecord connector = connectorRepository
                .lockActive(tenantId, connectorId)
                .orElseThrow(() -> new NotFoundException("connector"));
        if (connectorRepository.hasPendingSyncEvent(tenantId, connectorId)) {
            throw new ConnectorConflictException("connector sync is already pending or running");
        }
        Instant now = clock.instant();
        UUID syncId = UuidV7.generate(now);
        connectorRepository.markSyncAccepted(tenantId, connectorId, now);
        auditService.append(ConnectorAuditEvents.syncStarted(
                now,
                actor.tenantId(),
                actor.userId(),
                connectorId,
                syncId,
                ConnectorSyncTrigger.MANUAL,
                traceId,
                requestId));
        try {
            syncEvents.enqueue(tenantId, connectorId, syncId, actor.userId(), traceId, now);
        } catch (DataAccessException exception) {
            throw new ConnectorUnavailableException("connector sync queue is unavailable");
        }
        return new ConnectorSyncAcceptedResponse(syncId, connectorId, "PENDING", now);
    }
}
