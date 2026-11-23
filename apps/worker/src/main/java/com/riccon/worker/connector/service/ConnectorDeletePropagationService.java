package com.riccon.worker.connector.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.connector.domain.ConnectorDocumentRow;
import com.riccon.worker.connector.domain.ConnectorItemRecord;
import com.riccon.worker.connector.repository.ConnectorRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 连接器源端删除传播的短事务。
 *
 * <p>item 置 DELETED、文档软删除、{@code knowledge.document.deleted} 审计与 DELETE 索引 outbox 事件在同一
 * 事务内完成：审计或事件失败会回滚 item 和文档，避免“文档已隐藏但没有审计/索引清理”的半成品状态
 * （ADR-0023 第 6.3/6.6 节、TM-63/64）。按 item 单独提交，长同步不包大事务。
 */
@Service
public class ConnectorDeletePropagationService {

    /** 仓储。 */
    private final ConnectorRepository connectorRepository;

    /** 审计。 */
    private final ConnectorAuditWriter auditWriter;

    /** 事件投递。 */
    private final ConnectorEventPublisher eventPublisher;

    /**
     * 创建传播服务。
     *
     * @param connectorRepository 仓储
     * @param auditWriter 审计
     * @param eventPublisher 事件投递
     */
    public ConnectorDeletePropagationService(
            ConnectorRepository connectorRepository,
            ConnectorAuditWriter auditWriter,
            ConnectorEventPublisher eventPublisher) {
        this.connectorRepository = Objects.requireNonNull(connectorRepository, "connectorRepository must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
    }

    /**
     * 传播单个未见的源端条目。
     *
     * @param tenantId 租户
     * @param item 未见条目
     * @param actorId 索引事件发起人
     * @param traceId 追踪 ID
     * @param now 删除时间
     * @return item 从非 DELETED 变为 DELETED 时返回 true
     */
    @Transactional
    public boolean propagate(TenantId tenantId, ConnectorItemRecord item, UUID actorId, String traceId, Instant now) {
        if (connectorRepository.markItemDeleted(tenantId, item.id(), now) == 0) {
            return false;
        }
        if (item.documentId() == null) {
            return true;
        }
        ConnectorDocumentRow document =
                connectorRepository.findDocument(tenantId, item.documentId()).orElse(null);
        if (document == null || !document.active()) {
            return true;
        }
        if (connectorRepository.softDeleteDocument(tenantId, item.documentId(), now) == 0) {
            return true;
        }
        auditWriter.documentDeleted(tenantId, item.documentId(), document.visibility(), traceId);
        eventPublisher.enqueueDelete(tenantId, item.documentId(), document.aclVersion(), actorId, traceId, now);
        return true;
    }
}
