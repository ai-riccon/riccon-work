package com.riccon.worker.connector.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import com.riccon.worker.connector.repository.ConnectorRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 连接器文档写入与索引事件投递短事务。
 *
 * <p>新建/更新文档时，{@code knowledge_document} 与 {@code kb_connector_item} 的 SYNCED 状态以及
 * {@code riccon.knowledge.index.requested.v1} outbox 事件必须在同一事务：任何一步失败都回滚，下一次同步
 * 会重新处理该项并补投事件，避免“文档已写、索引事件丢失、状态永久 PENDING”（ADR-0023 第 6.3 节）。
 *
 * <p>平台对象写入在事务之前完成；若事务失败会留下可重写对象，由下次同步覆盖，不产生重复文档。
 */
@Service
public class ConnectorDocumentWriteService {

    /** 仓储。 */
    private final ConnectorRepository connectorRepository;

    /** 索引事件投递。 */
    private final ConnectorEventPublisher eventPublisher;

    /**
     * 创建写入服务。
     *
     * @param connectorRepository 仓储
     * @param eventPublisher 索引事件投递
     */
    public ConnectorDocumentWriteService(
            ConnectorRepository connectorRepository, ConnectorEventPublisher eventPublisher) {
        this.connectorRepository = Objects.requireNonNull(connectorRepository, "connectorRepository must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
    }

    /**
     * 新建连接器文档、绑定条目并投递索引事件。
     *
     * @param tenantId 租户
     * @param itemId 条目 ID
     * @param documentId 文档 ID
     * @param title 标题
     * @param connectorId 连接器 ID
     * @param externalId 源对象 key（仅库内）
     * @param checksum 内容哈希
     * @param mimeType MIME
     * @param sizeBytes 大小
     * @param visibility 可见范围
     * @param departmentId 部门
     * @param createdBy 创建人
     * @param retentionExpiresAt 保留到期
     * @param externalVersion ETag
     * @param actorId 索引事件发起人
     * @param traceId 追踪 ID
     * @param now 时间
     */
    @Transactional
    public void createDocument(
            TenantId tenantId,
            UUID itemId,
            UUID documentId,
            String title,
            UUID connectorId,
            String externalId,
            String checksum,
            String mimeType,
            long sizeBytes,
            KnowledgeVisibility visibility,
            UUID departmentId,
            UUID createdBy,
            Instant retentionExpiresAt,
            String externalVersion,
            UUID actorId,
            String traceId,
            Instant now) {
        connectorRepository.insertDocument(
                tenantId,
                documentId,
                title,
                connectorId,
                externalId,
                checksum,
                mimeType,
                sizeBytes,
                visibility,
                departmentId,
                createdBy,
                retentionExpiresAt,
                now);
        connectorRepository.markItemSynced(tenantId, itemId, documentId, checksum, externalVersion, now);
        eventPublisher.enqueueIndex(tenantId, documentId, 1, actorId, traceId, now);
    }

    /**
     * 更新连接器文档内容、绑定条目并投递重索引事件。
     *
     * @param tenantId 租户
     * @param itemId 条目 ID
     * @param documentId 文档 ID
     * @param aclVersion ACL 版本（保持不变）
     * @param checksum 新内容哈希
     * @param mimeType MIME
     * @param sizeBytes 新大小
     * @param externalVersion ETag
     * @param actorId 索引事件发起人
     * @param traceId 追踪 ID
     * @param now 时间
     */
    @Transactional
    public void updateDocument(
            TenantId tenantId,
            UUID itemId,
            UUID documentId,
            int aclVersion,
            String checksum,
            String mimeType,
            long sizeBytes,
            String externalVersion,
            UUID actorId,
            String traceId,
            Instant now) {
        if (connectorRepository.updateDocumentContent(tenantId, documentId, checksum, sizeBytes, mimeType, now) == 0) {
            throw new IllegalStateException("connector document update affected no rows");
        }
        connectorRepository.markItemSynced(tenantId, itemId, documentId, checksum, externalVersion, now);
        eventPublisher.enqueueIndex(tenantId, documentId, aclVersion, actorId, traceId, now);
    }
}
