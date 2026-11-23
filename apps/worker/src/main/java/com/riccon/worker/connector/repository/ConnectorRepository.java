package com.riccon.worker.connector.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorSyncStatus;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import com.riccon.worker.connector.domain.ConnectorDataKeyRow;
import com.riccon.worker.connector.domain.ConnectorDocumentRow;
import com.riccon.worker.connector.domain.ConnectorItemRecord;
import com.riccon.worker.connector.domain.ConnectorRecord;
import com.riccon.worker.connector.domain.ConnectorSyncSummary;
import com.riccon.worker.connector.domain.ConnectorTenantInfo;
import com.riccon.worker.connector.domain.DueConnector;
import com.riccon.worker.connector.persistence.ConnectorJdbc;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Worker 连接器仓储门面。
 *
 * <p>同步服务只依赖本类；SQL、JSON 与字节转换留在 persistence 包。
 */
@Repository
public class ConnectorRepository {

    /** 连接器 SQL。 */
    private final ConnectorJdbc connectorJdbc;

    /**
     * 创建仓储。
     *
     * @param connectorJdbc 连接器 SQL
     */
    public ConnectorRepository(ConnectorJdbc connectorJdbc) {
        this.connectorJdbc = Objects.requireNonNull(connectorJdbc, "connectorJdbc must not be null");
    }

    /** 读取连接器。 */
    public Optional<ConnectorRecord> findConnector(TenantId tenantId, UUID connectorId) {
        return connectorJdbc.findConnector(tenantId, connectorId);
    }

    /** 读取租户状态与保留天数。 */
    public Optional<ConnectorTenantInfo> findTenant(TenantId tenantId) {
        return connectorJdbc.findTenant(tenantId);
    }

    /** 标记同步开始。 */
    public int markSyncStarted(TenantId tenantId, UUID connectorId, Instant now) {
        return connectorJdbc.markSyncStarted(tenantId, connectorId, now);
    }

    /** 写同步终态。 */
    public int finishSync(
            TenantId tenantId,
            UUID connectorId,
            Instant startedAt,
            Instant finishedAt,
            ConnectorSyncStatus status,
            boolean complete,
            String errorCode,
            ConnectorSyncSummary summary,
            ConnectorStatus connectorStatus,
            Instant nextSyncAt) {
        return connectorJdbc.finishSync(
                tenantId,
                connectorId,
                startedAt,
                finishedAt,
                status,
                complete,
                errorCode,
                summary,
                connectorStatus,
                nextSyncAt);
    }

    /** 查询到期连接器。 */
    public List<DueConnector> findDue(Instant now, int limit) {
        return connectorJdbc.findDue(now, limit);
    }

    /** 条件领取到期连接器。 */
    public boolean claimDue(TenantId tenantId, UUID connectorId, Instant dueAt, Instant nextAt) {
        return connectorJdbc.claimDue(tenantId, connectorId, dueAt, nextAt);
    }

    /** 是否存在排队/处理中的同步事件。 */
    public boolean hasPendingSyncEvent(TenantId tenantId, UUID connectorId) {
        return connectorJdbc.hasPendingSyncEvent(tenantId, connectorId);
    }

    /** 查询第一个启用账号。 */
    public Optional<UUID> findFirstActiveUserId(TenantId tenantId) {
        return connectorJdbc.findFirstActiveUserId(tenantId);
    }

    /** 读取凭据 DEK。 */
    public Optional<ConnectorDataKeyRow> findDataKey(TenantId tenantId, String keyId, String purpose) {
        return connectorJdbc.findDataKey(tenantId, keyId, purpose);
    }

    /** 查找条目。 */
    public Optional<ConnectorItemRecord> findItem(TenantId tenantId, UUID connectorId, String externalIdHash) {
        return connectorJdbc.findItem(tenantId, connectorId, externalIdHash);
    }

    /** 插入条目。 */
    public void insertItem(
            TenantId tenantId,
            UUID connectorId,
            UUID itemId,
            String externalId,
            String externalIdHash,
            String externalVersion,
            Instant now) {
        connectorJdbc.insertItem(tenantId, connectorId, itemId, externalId, externalIdHash, externalVersion, now);
    }

    /** 复用 tombstone。 */
    public int resetTombstone(TenantId tenantId, UUID itemId, String externalVersion, Instant now) {
        return connectorJdbc.resetTombstone(tenantId, itemId, externalVersion, now);
    }

    /** 未变化：更新 last_seen。 */
    public int touchItem(TenantId tenantId, UUID itemId, String externalVersion, Instant now) {
        return connectorJdbc.touchItem(tenantId, itemId, externalVersion, now);
    }

    /** 标记条目成功。 */
    public int markItemSynced(
            TenantId tenantId, UUID itemId, UUID documentId, String checksum, String externalVersion, Instant now) {
        return connectorJdbc.markItemSynced(tenantId, itemId, documentId, checksum, externalVersion, now);
    }

    /** 标记条目跳过。 */
    public int markItemSkipped(TenantId tenantId, UUID itemId, String errorCode, String externalVersion, Instant now) {
        return connectorJdbc.markItemSkipped(tenantId, itemId, errorCode, externalVersion, now);
    }

    /** 标记条目失败。 */
    public int markItemFailed(TenantId tenantId, UUID itemId, String errorCode, String externalVersion, Instant now) {
        return connectorJdbc.markItemFailed(tenantId, itemId, errorCode, externalVersion, now);
    }

    /** 标记条目删除。 */
    public int markItemDeleted(TenantId tenantId, UUID itemId, Instant now) {
        return connectorJdbc.markItemDeleted(tenantId, itemId, now);
    }

    /** 查询未见条目。 */
    public List<ConnectorItemRecord> findUnseen(TenantId tenantId, UUID connectorId, Instant syncStartedAt) {
        return connectorJdbc.findUnseen(tenantId, connectorId, syncStartedAt);
    }

    /** 写入文档。 */
    public void insertDocument(
            TenantId tenantId,
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
            Instant now) {
        connectorJdbc.insertDocument(
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
    }

    /** 读取文档快照。 */
    public Optional<ConnectorDocumentRow> findDocument(TenantId tenantId, UUID documentId) {
        return connectorJdbc.findActiveDocument(tenantId, documentId);
    }

    /** 更新文档内容元数据。 */
    public int updateDocumentContent(
            TenantId tenantId, UUID documentId, String checksum, long sizeBytes, String mimeType, Instant now) {
        return connectorJdbc.updateDocumentContent(tenantId, documentId, checksum, sizeBytes, mimeType, now);
    }

    /** 软删除文档。 */
    public int softDeleteDocument(TenantId tenantId, UUID documentId, Instant now) {
        return connectorJdbc.softDeleteDocument(tenantId, documentId, now);
    }

    /** 下一次索引尝试号。 */
    public int nextIndexAttempt(TenantId tenantId, UUID documentId) {
        return connectorJdbc.nextIndexAttempt(tenantId, documentId);
    }

    /** 写入运行。 */
    public void insertRun(TenantId tenantId, UUID runId, UUID userId, String traceId, Instant now) {
        connectorJdbc.insertRun(tenantId, runId, userId, traceId, now);
    }

    /** 写入 outbox 事件。 */
    public void insertOutboxEvent(
            TenantId tenantId,
            UUID eventId,
            UUID runId,
            String eventType,
            String idempotencyKey,
            String payloadJson,
            Instant availableAt,
            Instant now) {
        connectorJdbc.insertOutboxEvent(
                tenantId, eventId, runId, eventType, idempotencyKey, payloadJson, availableAt, now);
    }
}
