package com.riccon.api.knowledge.connector.repository;

import com.riccon.api.knowledge.connector.domain.ConnectorConfig;
import com.riccon.api.knowledge.connector.domain.ConnectorDataKeyRow;
import com.riccon.api.knowledge.connector.domain.ConnectorDocumentRow;
import com.riccon.api.knowledge.connector.domain.ConnectorItemRecord;
import com.riccon.api.knowledge.connector.domain.ConnectorRecord;
import com.riccon.api.knowledge.connector.persistence.ConnectorJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.knowledge.ConnectorItemStatus;
import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorType;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 连接器仓储。
 *
 * <p>服务层只通过本类访问连接器、条目、凭据 DEK 与导入文档；SQL 细节留在 persistence 包。
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

    /** 插入连接器。 */
    public void insertConnector(TenantId tenantId, ConnectorRecord connector) {
        connectorJdbc.insertConnector(tenantId, connector);
    }

    /** 按 ID 读取未删除连接器。 */
    public Optional<ConnectorRecord> findActiveById(TenantId tenantId, UUID connectorId) {
        return connectorJdbc.findActiveById(tenantId, connectorId);
    }

    /** 按 ID 读取连接器（含软删除）。 */
    public Optional<ConnectorRecord> findByIdIncludingDeleted(TenantId tenantId, UUID connectorId) {
        return connectorJdbc.findByIdIncludingDeleted(tenantId, connectorId);
    }

    /** 锁定未删除连接器。 */
    public Optional<ConnectorRecord> lockActive(TenantId tenantId, UUID connectorId) {
        return connectorJdbc.lockActive(tenantId, connectorId);
    }

    /** 锁定连接器（含软删除）。 */
    public Optional<ConnectorRecord> lockIncludingDeleted(TenantId tenantId, UUID connectorId) {
        return connectorJdbc.lockIncludingDeleted(tenantId, connectorId);
    }

    /** 统计连接器。 */
    public long countConnectors(TenantId tenantId, ConnectorStatus status, ConnectorType type) {
        return connectorJdbc.countConnectors(tenantId, status, type);
    }

    /** 分页查询连接器。 */
    public List<ConnectorRecord> listConnectors(
            TenantId tenantId, ConnectorStatus status, ConnectorType type, int limit, int offset) {
        return connectorJdbc.listConnectors(tenantId, status, type, limit, offset);
    }

    /** 更新连接器可编辑字段。 */
    public int updateConnector(
            TenantId tenantId,
            UUID connectorId,
            String name,
            String endpointId,
            ConnectorConfig config,
            KnowledgeVisibility visibility,
            UUID departmentId,
            boolean scheduleEnabled,
            Instant nextSyncAt,
            ConnectorStatus status,
            Instant now) {
        return connectorJdbc.updateConnector(
                tenantId,
                connectorId,
                name,
                endpointId,
                config,
                visibility,
                departmentId,
                scheduleEnabled,
                nextSyncAt,
                status,
                now);
    }

    /** 覆盖写入凭据密文、keyId 与掩码。 */
    public int updateCredential(
            TenantId tenantId, UUID connectorId, byte[] ciphertext, String keyId, String maskedHint, Instant now) {
        return connectorJdbc.updateCredential(tenantId, connectorId, ciphertext, keyId, maskedHint, now);
    }

    /** 受理同步时刷新开始时间。 */
    public int markSyncAccepted(TenantId tenantId, UUID connectorId, Instant now) {
        return connectorJdbc.markSyncAccepted(tenantId, connectorId, now);
    }

    /** 软删除连接器。 */
    public int softDeleteConnector(TenantId tenantId, UUID connectorId, Instant now) {
        return connectorJdbc.softDeleteConnector(tenantId, connectorId, now);
    }

    /** 查询启用部门。 */
    public Optional<UUID> findActiveDepartment(TenantId tenantId, UUID departmentId) {
        return connectorJdbc.findActiveDepartment(tenantId, departmentId);
    }

    /** 查询导入文档。 */
    public List<ConnectorDocumentRow> findActiveDocuments(TenantId tenantId, UUID connectorId) {
        return connectorJdbc.findActiveDocuments(tenantId, connectorId);
    }

    /** 软删除导入文档。 */
    public int softDeleteDocument(TenantId tenantId, UUID documentId, Instant now) {
        return connectorJdbc.softDeleteDocument(tenantId, documentId, now);
    }

    /** 是否存在排队/处理中的同步事件。 */
    public boolean hasPendingSyncEvent(TenantId tenantId, UUID connectorId) {
        return connectorJdbc.hasPendingSyncEvent(tenantId, connectorId);
    }

    /** 读取活跃连接器凭据 DEK。 */
    public Optional<ConnectorDataKeyRow> findActiveDataKey(TenantId tenantId, String purpose) {
        return connectorJdbc.findActiveDataKey(tenantId, purpose);
    }

    /** 按 keyId 读取连接器凭据 DEK。 */
    public Optional<ConnectorDataKeyRow> findDataKey(TenantId tenantId, String purpose, String keyId) {
        return connectorJdbc.findDataKey(tenantId, purpose, keyId);
    }

    /** 插入连接器凭据 DEK。 */
    public void insertDataKey(
            TenantId tenantId,
            String keyId,
            String purpose,
            byte[] wrappedDek,
            byte[] wrapNonce,
            String algorithm,
            String masterKeyId) {
        connectorJdbc.insertDataKey(tenantId, keyId, purpose, wrappedDek, wrapNonce, algorithm, masterKeyId);
    }

    /** 统计条目。 */
    public long countItems(TenantId tenantId, UUID connectorId, ConnectorItemStatus status) {
        return connectorJdbc.countItems(tenantId, connectorId, status);
    }

    /** 分页查询条目。 */
    public List<ConnectorItemRecord> listItems(
            TenantId tenantId, UUID connectorId, ConnectorItemStatus status, int limit, int offset) {
        return connectorJdbc.listItems(tenantId, connectorId, status, limit, offset);
    }

    /** 计算下一次索引尝试号。 */
    public int nextIndexAttempt(TenantId tenantId, UUID documentId) {
        return connectorJdbc.nextIndexAttempt(tenantId, documentId);
    }
}
