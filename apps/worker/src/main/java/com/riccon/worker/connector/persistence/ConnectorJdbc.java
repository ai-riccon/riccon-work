package com.riccon.worker.connector.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.knowledge.ConnectorItemStatus;
import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorSyncStatus;
import com.riccon.platform.domain.knowledge.ConnectorType;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import com.riccon.worker.connector.domain.ConnectorConfig;
import com.riccon.worker.connector.domain.ConnectorDataKeyRow;
import com.riccon.worker.connector.domain.ConnectorDocumentRow;
import com.riccon.worker.connector.domain.ConnectorItemRecord;
import com.riccon.worker.connector.domain.ConnectorRecord;
import com.riccon.worker.connector.domain.ConnectorSyncSummary;
import com.riccon.worker.connector.domain.ConnectorTenantInfo;
import com.riccon.worker.connector.domain.DueConnector;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Worker 连接器 SQL。
 *
 * <p>业务读写都经过 {@link TenantJdbc} 强制 {@code tenant_id} 条件；只有到期的跨租户调度扫描使用
 * {@link JdbcClient}（与 outbox 领取同类例外），查询不带任何用户输入。凭据密文只在此层读写，日志不输出。
 */
@Component
public class ConnectorJdbc {

    /** 连接器查询列。 */
    private static final String CONNECTOR_COLUMNS = """
            id, name, type, status, endpoint_id, config, default_visibility, default_department_id,
            credential_ciphertext, credential_key_id, schedule_enabled, next_sync_at, last_sync_status,
            created_by, created_at, deleted_at
            """;

    /** 条目查询列。 */
    private static final String ITEM_COLUMNS = """
            id, external_id, external_version, checksum, document_id, generation, status, error_code,
            last_seen_at, deleted_at
            """;

    /** 租户守卫数据访问层。 */
    private final TenantJdbc tenantJdbc;

    /** 跨租户调度扫描。 */
    private final JdbcClient jdbcClient;

    /** JSON 编解码。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建持久化组件。
     *
     * @param tenantJdbc 租户守卫数据访问层
     * @param jdbcClient 跨租户调度扫描
     * @param objectMapper JSON 编解码
     */
    public ConnectorJdbc(TenantJdbc tenantJdbc, JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 读取连接器（含软删除标记）。
     *
     * @param tenantId 租户
     * @param connectorId 连接器 ID
     * @return 连接器
     */
    public Optional<ConnectorRecord> findConnector(TenantId tenantId, UUID connectorId) {
        return tenantJdbc.queryOne(
                tenantId,
                "SELECT " + CONNECTOR_COLUMNS + " FROM kb_connector"
                        + " WHERE tenant_id = :tenantId AND id = :connectorId",
                Map.of("connectorId", UuidBinary.toBytes(connectorId)),
                this::mapConnector);
    }

    /**
     * 读取租户状态与保留天数。
     *
     * @param tenantId 租户
     * @return 租户信息；不存在时为空
     */
    public Optional<ConnectorTenantInfo> findTenant(TenantId tenantId) {
        // tenant 表的键是 id 而不是 tenant_id；这是全局目录读取，不经 TenantJdbc。
        return jdbcClient
                .sql("SELECT status, retention_days FROM tenant WHERE id = :tenantId")
                .param("tenantId", tenantId.value())
                .query((rs, rowNum) -> new ConnectorTenantInfo(rs.getString("status"), rs.getInt("retention_days")))
                .optional();
    }

    /**
     * 判断连接器是否仍可同步：连接器未软删除且租户为 ACTIVE。
     *
     * <p>同步是长任务，必须在每个对象处理前复核；否则管理员在同步中删除连接器/停用租户后仍会新增文档。
     *
     * @param tenantId 租户
     * @param connectorId 连接器 ID
     * @return 可同步时返回 true
     */
    public boolean isSyncable(TenantId tenantId, UUID connectorId) {
        return tenantJdbc
                .queryOne(
                        tenantId,
                        """
                        SELECT 1 AS present
                          FROM kb_connector c
                          JOIN tenant t ON t.id = c.tenant_id
                         WHERE c.tenant_id = :tenantId
                           AND c.id = :connectorId
                           AND c.deleted_at IS NULL
                           AND t.status = 'ACTIVE'
                         LIMIT 1
                        """,
                        Map.of("connectorId", UuidBinary.toBytes(connectorId)),
                        (rs, rowNum) -> rs.getInt("present"))
                .isPresent();
    }

    /**
     * 标记同步开始；失败也由后续 finish 写结束时间。
     *
     * @param tenantId 租户
     * @param connectorId 连接器 ID
     * @param now 开始时间
     * @return 受影响行数
     */
    public int markSyncStarted(TenantId tenantId, UUID connectorId, Instant now) {
        return tenantJdbc.update(
                tenantId, """
                UPDATE kb_connector
                   SET last_sync_started_at = :now,
                       last_sync_finished_at = NULL,
                       updated_at = :now
                 WHERE tenant_id = :tenantId
                   AND id = :connectorId
                   AND deleted_at IS NULL
                """, Map.of("connectorId", UuidBinary.toBytes(connectorId), "now", toDatabase(now)));
    }

    /**
     * 写同步终态；成功也写结束时间与摘要，失败保留错误码。
     *
     * @param tenantId 租户
     * @param connectorId 连接器 ID
     * @param startedAt 开始时间
     * @param finishedAt 结束时间
     * @param status 同步状态
     * @param complete list 是否完整
     * @param errorCode 失败码；成功为空
     * @param summary 计数
     * @param connectorStatus 连接器目标状态
     * @param nextSyncAt 下一次定时同步；关闭调度为空
     * @return 受影响行数
     */
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
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("connectorId", UuidBinary.toBytes(connectorId));
        params.put("startedAt", toDatabase(startedAt));
        params.put("finishedAt", toDatabase(finishedAt));
        params.put("syncStatus", status.name());
        params.put("complete", complete);
        params.put("errorCode", errorCode);
        params.put("summary", toJson(summary));
        params.put("connectorStatus", connectorStatus.name());
        params.put("nextSyncAt", toDatabase(nextSyncAt));
        params.put("now", toDatabase(finishedAt));
        params.put("successAt", status == ConnectorSyncStatus.SUCCESS ? toDatabase(finishedAt) : null);
        return tenantJdbc.update(tenantId, """
                UPDATE kb_connector
                   SET last_sync_started_at = :startedAt,
                       last_sync_finished_at = :finishedAt,
                       last_sync_status = :syncStatus,
                       last_sync_complete = :complete,
                       last_success_at = COALESCE(:successAt, last_success_at),
                       last_error_code = :errorCode,
                       last_sync_summary = :summary,
                       status = :connectorStatus,
                       next_sync_at = :nextSyncAt,
                       updated_at = :now
                 WHERE tenant_id = :tenantId
                   AND id = :connectorId
                   AND deleted_at IS NULL
                """, params);
    }

    /**
     * 查询到期的定时同步连接器（跨租户）。
     *
     * @param now 当前时间
     * @param limit 批量上限
     * @return 到期连接器
     */
    public List<DueConnector> findDue(Instant now, int limit) {
        return jdbcClient
                .sql("""
                     SELECT tenant_id, id, next_sync_at
                       FROM kb_connector
                      WHERE status = 'ACTIVE'
                        AND schedule_enabled = TRUE
                        AND deleted_at IS NULL
                        AND next_sync_at IS NOT NULL
                        AND next_sync_at <= :now
                      ORDER BY next_sync_at ASC
                      LIMIT :limit
                     """)
                .param("now", toDatabase(now))
                .param("limit", limit)
                .query((rs, rowNum) -> new DueConnector(
                        rs.getString("tenant_id"),
                        UuidBinary.fromBytes(rs.getBytes("id")),
                        toInstant(rs.getObject("next_sync_at", LocalDateTime.class))))
                .list();
    }

    /**
     * 条件领取到期连接器并推进下一次同步时间。
     *
     * @param tenantId 租户
     * @param connectorId 连接器 ID
     * @param dueAt 扫描到的到期时间
     * @param nextAt 下一次同步时间
     * @return 领取成功返回 true
     */
    public boolean claimDue(TenantId tenantId, UUID connectorId, Instant dueAt, Instant nextAt) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("connectorId", UuidBinary.toBytes(connectorId));
        params.put("dueAt", toDatabase(dueAt));
        params.put("nextAt", toDatabase(nextAt));
        return tenantJdbc.update(tenantId, """
                UPDATE kb_connector
                   SET next_sync_at = :nextAt,
                       updated_at = :nextAt
                 WHERE tenant_id = :tenantId
                   AND id = :connectorId
                   AND status = 'ACTIVE'
                   AND schedule_enabled = TRUE
                   AND deleted_at IS NULL
                   AND next_sync_at = :dueAt
                """, params) == 1;
    }

    /**
     * 查询连接器是否已有排队/处理中的同步事件。
     *
     * @param tenantId 租户
     * @param connectorId 连接器 ID
     * @return 存在时返回 true
     */
    public boolean hasPendingSyncEvent(TenantId tenantId, UUID connectorId) {
        Long count = tenantJdbc
                .queryOne(
                        tenantId,
                        """
                        SELECT COUNT(*) FROM agent_run_outbox
                         WHERE tenant_id = :tenantId
                           AND idempotency_key LIKE CONCAT(:prefix, '%')
                           AND status IN ('PENDING', 'DISPATCHED')
                        """,
                        Map.of("prefix", "connector:" + connectorId + ":sync:"),
                        (rs, rowNum) -> rs.getLong(1))
                .orElse(0L);
        return count > 0;
    }

    /**
     * 查询第一个启用账号，作为连接器事件的发起人兜底。
     *
     * @param tenantId 租户
     * @return 账号 ID
     */
    public Optional<UUID> findFirstActiveUserId(TenantId tenantId) {
        return tenantJdbc.queryOne(tenantId, """
                SELECT id FROM app_user
                 WHERE tenant_id = :tenantId AND status = 'ACTIVE'
                 ORDER BY created_at ASC, id ASC LIMIT 1
                """, Map.of(), (rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes("id")));
    }

    /**
     * 读取连接器凭据 DEK。
     *
     * @param tenantId 租户
     * @param keyId DEK 标识
     * @param purpose 用途
     * @return DEK 行
     */
    public Optional<ConnectorDataKeyRow> findDataKey(TenantId tenantId, String keyId, String purpose) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT wrapped_dek, wrap_nonce, algorithm, master_key_id
                  FROM tenant_data_key
                 WHERE tenant_id = :tenantId
                   AND key_id = :keyId
                   AND purpose = :purpose
                   AND retired_at IS NULL
                """,
                Map.of("keyId", keyId, "purpose", purpose),
                (rs, rowNum) -> new ConnectorDataKeyRow(
                        rs.getBytes("wrapped_dek"),
                        rs.getBytes("wrap_nonce"),
                        rs.getString("algorithm"),
                        rs.getString("master_key_id")));
    }

    /**
     * 查找单个条目。
     *
     * @param tenantId 租户
     * @param connectorId 连接器 ID
     * @param externalIdHash externalId 哈希
     * @return 条目
     */
    public Optional<ConnectorItemRecord> findItem(TenantId tenantId, UUID connectorId, String externalIdHash) {
        return tenantJdbc.queryOne(
                tenantId,
                "SELECT " + ITEM_COLUMNS + " FROM kb_connector_item"
                        + " WHERE tenant_id = :tenantId AND connector_id = :connectorId"
                        + " AND external_id_hash = :hash",
                Map.of("connectorId", UuidBinary.toBytes(connectorId), "hash", externalIdHash),
                this::mapItem);
    }

    /**
     * 插入条目。
     *
     * @param tenantId 租户
     * @param connectorId 连接器 ID
     * @param itemId 条目 ID
     * @param externalId 源对象 key
     * @param externalIdHash SHA-256
     * @param externalVersion ETag
     * @param now 当前时间
     */
    public void insertItem(
            TenantId tenantId,
            UUID connectorId,
            UUID itemId,
            String externalId,
            String externalIdHash,
            String externalVersion,
            Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(itemId));
        params.put("connectorId", UuidBinary.toBytes(connectorId));
        params.put("externalId", externalId);
        params.put("hash", externalIdHash);
        params.put("version", externalVersion);
        params.put("now", toDatabase(now));
        tenantJdbc.insert(tenantId, """
                INSERT INTO kb_connector_item (
                    tenant_id, id, connector_id, external_id, external_id_hash, external_version,
                    generation, status, last_seen_at, created_at, updated_at
                ) VALUES (
                    :tenantId, :id, :connectorId, :externalId, :hash, :version,
                    1, 'PENDING', :now, :now, :now
                )
                """, params);
    }

    /**
     * 复用 tombstone：代数 +1、清删除时间并重置状态。
     *
     * @param tenantId 租户
     * @param itemId 条目 ID
     * @param externalVersion ETag
     * @param now 当前时间
     * @return 受影响行数
     */
    public int resetTombstone(TenantId tenantId, UUID itemId, String externalVersion, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("itemId", UuidBinary.toBytes(itemId));
        params.put("version", externalVersion);
        params.put("now", toDatabase(now));
        return tenantJdbc.update(tenantId, """
                UPDATE kb_connector_item
                   SET status = 'PENDING',
                       generation = generation + 1,
                       external_version = :version,
                       checksum = NULL,
                       document_id = NULL,
                       error_code = NULL,
                       deleted_at = NULL,
                       last_seen_at = :now,
                       updated_at = :now
                 WHERE tenant_id = :tenantId
                   AND id = :itemId
                """, params);
    }

    /**
     * 未变化：只更新 last_seen_at 与版本。
     *
     * @param tenantId 租户
     * @param itemId 条目 ID
     * @param externalVersion ETag
     * @param now 当前时间
     * @return 受影响行数
     */
    public int touchItem(TenantId tenantId, UUID itemId, String externalVersion, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("itemId", UuidBinary.toBytes(itemId));
        params.put("version", externalVersion);
        params.put("now", toDatabase(now));
        return tenantJdbc.update(tenantId, """
                UPDATE kb_connector_item
                   SET external_version = :version,
                       last_seen_at = :now,
                       updated_at = :now
                 WHERE tenant_id = :tenantId
                   AND id = :itemId
                """, params);
    }

    /**
     * 标记条目同步成功。
     *
     * @param tenantId 租户
     * @param itemId 条目 ID
     * @param documentId 文档 ID
     * @param checksum 内容哈希
     * @param externalVersion ETag
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markItemSynced(
            TenantId tenantId, UUID itemId, UUID documentId, String checksum, String externalVersion, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("itemId", UuidBinary.toBytes(itemId));
        params.put("documentId", UuidBinary.toBytes(documentId));
        params.put("checksum", checksum);
        params.put("version", externalVersion);
        params.put("now", toDatabase(now));
        return tenantJdbc.update(tenantId, """
                UPDATE kb_connector_item
                   SET status = 'SYNCED',
                       document_id = :documentId,
                       checksum = :checksum,
                       external_version = :version,
                       error_code = NULL,
                       deleted_at = NULL,
                       last_seen_at = :now,
                       updated_at = :now
                 WHERE tenant_id = :tenantId
                   AND id = :itemId
                """, params);
    }

    /**
     * 标记条目跳过（类型不支持/排除）。
     *
     * @param tenantId 租户
     * @param itemId 条目 ID
     * @param errorCode 原因码
     * @param externalVersion ETag
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markItemSkipped(TenantId tenantId, UUID itemId, String errorCode, String externalVersion, Instant now) {
        return updateItemStatus(tenantId, itemId, ConnectorItemStatus.SKIPPED, errorCode, externalVersion, now);
    }

    /**
     * 标记条目失败。
     *
     * @param tenantId 租户
     * @param itemId 条目 ID
     * @param errorCode 失败码
     * @param externalVersion ETag
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markItemFailed(TenantId tenantId, UUID itemId, String errorCode, String externalVersion, Instant now) {
        return updateItemStatus(tenantId, itemId, ConnectorItemStatus.FAILED, errorCode, externalVersion, now);
    }

    /**
     * 标记条目删除（tombstone）。
     *
     * @param tenantId 租户
     * @param itemId 条目 ID
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markItemDeleted(TenantId tenantId, UUID itemId, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("itemId", UuidBinary.toBytes(itemId));
        params.put("now", toDatabase(now));
        return tenantJdbc.update(tenantId, """
                UPDATE kb_connector_item
                   SET status = 'DELETED',
                       deleted_at = :now,
                       updated_at = :now
                 WHERE tenant_id = :tenantId
                   AND id = :itemId
                   AND status <> 'DELETED'
                """, params);
    }

    /**
     * 查询本轮未见、且上次出现早于本轮开始时间的条目。
     *
     * @param tenantId 租户
     * @param connectorId 连接器 ID
     * @param syncStartedAt 本轮开始时间
     * @return 未见条目
     */
    public List<ConnectorItemRecord> findUnseen(TenantId tenantId, UUID connectorId, Instant syncStartedAt) {
        return tenantJdbc.query(
                tenantId,
                "SELECT " + ITEM_COLUMNS + " FROM kb_connector_item"
                        + " WHERE tenant_id = :tenantId AND connector_id = :connectorId"
                        + " AND status <> 'DELETED'"
                        + " AND (last_seen_at IS NULL OR last_seen_at < :startedAt)"
                        + " ORDER BY id ASC",
                Map.of("connectorId", UuidBinary.toBytes(connectorId), "startedAt", toDatabase(syncStartedAt)),
                this::mapItem);
    }

    /**
     * 写入连接器文档。
     *
     * @param tenantId 租户
     * @param documentId 文档 ID
     * @param title 标题
     * @param connectorId 连接器 ID
     * @param externalId 源对象 key（仅库内）
     * @param checksum 内容哈希
     * @param mimeType MIME
     * @param sizeBytes 大小
     * @param visibility 可见范围
     * @param departmentId 部门
     * @param createdBy 创建人；可空
     * @param retentionExpiresAt 保留到期
     * @param now 创建时间
     */
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
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(documentId));
        params.put("title", title);
        params.put("connectorId", UuidBinary.toBytes(connectorId));
        params.put("externalId", externalId);
        params.put("checksum", checksum);
        params.put("mimeType", mimeType);
        params.put("sizeBytes", sizeBytes);
        params.put("visibility", visibility.name());
        params.put("departmentId", departmentId == null ? null : UuidBinary.toBytes(departmentId));
        params.put("createdBy", createdBy == null ? null : UuidBinary.toBytes(createdBy));
        params.put("retentionExpiresAt", toDatabase(retentionExpiresAt));
        params.put("now", toDatabase(now));
        tenantJdbc.insert(tenantId, """
                INSERT INTO knowledge_document (
                    tenant_id, id, title, source_type, file_id, connector_id, external_id, checksum,
                    mime_type, size_bytes, status, created_by, created_at, updated_at, visibility,
                    department_id, parse_engine, acl_version, retention_expires_at
                ) VALUES (
                    :tenantId, :id, :title, 'CONNECTOR', NULL, :connectorId, :externalId, :checksum,
                    :mimeType, :sizeBytes, 'PENDING', :createdBy, :now, :now, :visibility,
                    :departmentId, 'NONE', 1, :retentionExpiresAt
                )
                """, params);
    }

    /**
     * 读取未删除文档快照。
     *
     * @param tenantId 租户
     * @param documentId 文档 ID
     * @return 文档
     */
    public Optional<ConnectorDocumentRow> findActiveDocument(TenantId tenantId, UUID documentId) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT id, checksum, status, visibility, acl_version, created_at, deleted_at
                  FROM knowledge_document
                 WHERE tenant_id = :tenantId AND id = :documentId
                """,
                Map.of("documentId", UuidBinary.toBytes(documentId)),
                (rs, rowNum) -> new ConnectorDocumentRow(
                        UuidBinary.fromBytes(rs.getBytes("id")),
                        rs.getString("checksum"),
                        KnowledgeDocumentStatus.valueOf(rs.getString("status")),
                        KnowledgeVisibility.valueOf(rs.getString("visibility")),
                        rs.getInt("acl_version"),
                        toInstant(rs.getObject("created_at", LocalDateTime.class)),
                        toInstant(rs.getObject("deleted_at", LocalDateTime.class))));
    }

    /**
     * 内容变化时更新文档校验和/大小/MIME，并把状态重置为 PENDING 等待重索引；ACL/可见范围不变。
     *
     * @param tenantId 租户
     * @param documentId 文档 ID
     * @param checksum 新哈希
     * @param sizeBytes 新大小
     * @param mimeType 新 MIME
     * @param now 更新时间
     * @return 受影响行数
     */
    public int updateDocumentContent(
            TenantId tenantId, UUID documentId, String checksum, long sizeBytes, String mimeType, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("documentId", UuidBinary.toBytes(documentId));
        params.put("checksum", checksum);
        params.put("sizeBytes", sizeBytes);
        params.put("mimeType", mimeType);
        params.put("now", toDatabase(now));
        return tenantJdbc.update(tenantId, """
                UPDATE knowledge_document
                   SET checksum = :checksum,
                       size_bytes = :sizeBytes,
                       mime_type = :mimeType,
                       status = 'PENDING',
                       error_code = NULL,
                       error_message = NULL,
                       updated_at = :now
                 WHERE tenant_id = :tenantId
                   AND id = :documentId
                   AND deleted_at IS NULL
                """, params);
    }

    /**
     * 软删除文档。
     *
     * @param tenantId 租户
     * @param documentId 文档 ID
     * @param now 删除时间
     * @return 受影响行数
     */
    public int softDeleteDocument(TenantId tenantId, UUID documentId, Instant now) {
        return tenantJdbc.update(
                tenantId, """
                UPDATE knowledge_document
                   SET status = 'DELETED', deleted_at = :now, updated_at = :now
                 WHERE tenant_id = :tenantId
                   AND id = :documentId
                   AND deleted_at IS NULL
                """, Map.of("documentId", UuidBinary.toBytes(documentId), "now", toDatabase(now)));
    }

    /**
     * 计算文档下一次索引尝试号。
     *
     * @param tenantId 租户
     * @param documentId 文档 ID
     * @return 尝试号
     */
    public int nextIndexAttempt(TenantId tenantId, UUID documentId) {
        Long attempt = tenantJdbc
                .queryOne(
                        tenantId, """
                        SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(idempotency_key, ':', -1) AS UNSIGNED)), 0) + 1
                          FROM agent_run_outbox
                         WHERE tenant_id = :tenantId
                           AND idempotency_key LIKE CONCAT(:prefix, '%')
                        """, Map.of("prefix", "doc:" + documentId + ":index:"), (rs, rowNum) -> rs.getLong(1))
                .orElse(1L);
        return attempt.intValue();
    }

    /**
     * 查询文档是否已有排队/处理中的索引事件。
     *
     * @param tenantId 租户
     * @param documentId 文档 ID
     * @return 存在时返回 true
     */
    public boolean hasPendingIndexEvent(TenantId tenantId, UUID documentId) {
        Long count = tenantJdbc
                .queryOne(
                        tenantId, """
                        SELECT COUNT(*) FROM agent_run_outbox
                         WHERE tenant_id = :tenantId
                           AND idempotency_key LIKE CONCAT(:prefix, '%')
                           AND status IN ('PENDING', 'DISPATCHED')
                        """, Map.of("prefix", "doc:" + documentId + ":index:"), (rs, rowNum) -> rs.getLong(1))
                .orElse(0L);
        return count > 0;
    }

    /**
     * 写入 PENDING 运行（kind=KNOWLEDGE_INDEX）。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param userId 发起人
     * @param traceId 追踪 ID
     * @param now 创建时间
     */
    public void insertRun(TenantId tenantId, UUID runId, UUID userId, String traceId, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(runId));
        params.put("userId", UuidBinary.toBytes(userId));
        params.put("traceId", traceId);
        params.put("now", toDatabase(now));
        tenantJdbc.insert(tenantId, """
                INSERT INTO agent_run (tenant_id, id, kind, user_id, status, attempt, max_attempts, trace_id,
                                       created_at, updated_at)
                VALUES (:tenantId, :id, 'KNOWLEDGE_INDEX', :userId, 'PENDING', 1, 3, :traceId, :now, :now)
                """, params);
    }

    /**
     * 写入 outbox 事件。
     *
     * @param tenantId 租户
     * @param eventId 事件 ID
     * @param runId 运行 ID
     * @param eventType 事件类型
     * @param idempotencyKey 幂等键
     * @param payloadJson payload JSON
     * @param availableAt 最早可领取时间
     * @param now 创建时间
     */
    public void insertOutboxEvent(
            TenantId tenantId,
            UUID eventId,
            UUID runId,
            String eventType,
            String idempotencyKey,
            String payloadJson,
            Instant availableAt,
            Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(eventId));
        params.put("runId", UuidBinary.toBytes(runId));
        params.put("eventType", eventType);
        params.put("idempotencyKey", idempotencyKey);
        params.put("payload", payloadJson);
        params.put("availableAt", toDatabase(availableAt));
        params.put("now", toDatabase(now));
        tenantJdbc.insert(tenantId, """
                INSERT INTO agent_run_outbox (
                    tenant_id, id, run_id, event_type, event_version, idempotency_key, payload,
                    status, attempts, available_at, created_at, updated_at
                ) VALUES (
                    :tenantId, :id, :runId, :eventType, 1, :idempotencyKey, :payload,
                    'PENDING', 0, :availableAt, :now, :now
                )
                """, params);
    }

    /**
     * 更新条目状态。
     *
     * @param tenantId 租户
     * @param itemId 条目 ID
     * @param status 目标状态
     * @param errorCode 错误码
     * @param externalVersion ETag
     * @param now 当前时间
     * @return 受影响行数
     */
    private int updateItemStatus(
            TenantId tenantId,
            UUID itemId,
            ConnectorItemStatus status,
            String errorCode,
            String externalVersion,
            Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("itemId", UuidBinary.toBytes(itemId));
        params.put("status", status.name());
        params.put("errorCode", errorCode);
        params.put("version", externalVersion);
        params.put("now", toDatabase(now));
        return tenantJdbc.update(tenantId, """
                UPDATE kb_connector_item
                   SET status = :status,
                       error_code = :errorCode,
                       external_version = :version,
                       deleted_at = NULL,
                       last_seen_at = :now,
                       updated_at = :now
                 WHERE tenant_id = :tenantId
                   AND id = :itemId
                """, params);
    }

    /**
     * 映射连接器行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 连接器
     * @throws SQLException 读取失败
     */
    private ConnectorRecord mapConnector(ResultSet rs, int rowNum) throws SQLException {
        return new ConnectorRecord(
                UuidBinary.fromBytes(rs.getBytes("id")),
                rs.getString("name"),
                ConnectorType.valueOf(rs.getString("type")),
                ConnectorStatus.valueOf(rs.getString("status")),
                rs.getString("endpoint_id"),
                parseConfig(rs.getString("config")),
                KnowledgeVisibility.valueOf(rs.getString("default_visibility")),
                toUuid(rs.getBytes("default_department_id")),
                rs.getBytes("credential_ciphertext"),
                rs.getString("credential_key_id"),
                rs.getBoolean("schedule_enabled"),
                toInstant(rs.getObject("next_sync_at", LocalDateTime.class)),
                rs.getString("last_sync_status") == null
                        ? null
                        : ConnectorSyncStatus.valueOf(rs.getString("last_sync_status")),
                toUuid(rs.getBytes("created_by")),
                toInstant(rs.getObject("created_at", LocalDateTime.class)),
                toInstant(rs.getObject("deleted_at", LocalDateTime.class)));
    }

    /**
     * 映射条目行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 条目
     * @throws SQLException 读取失败
     */
    private ConnectorItemRecord mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new ConnectorItemRecord(
                UuidBinary.fromBytes(rs.getBytes("id")),
                rs.getString("external_id"),
                rs.getString("external_version"),
                rs.getString("checksum"),
                toUuid(rs.getBytes("document_id")),
                rs.getInt("generation"),
                ConnectorItemStatus.valueOf(rs.getString("status")),
                rs.getString("error_code"),
                toInstant(rs.getObject("last_seen_at", LocalDateTime.class)),
                toInstant(rs.getObject("deleted_at", LocalDateTime.class)));
    }

    /**
     * 解析配置 JSON。
     *
     * @param json JSON
     * @return 配置
     */
    private ConnectorConfig parseConfig(String json) {
        try {
            return objectMapper.readValue(json, ConnectorConfig.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("invalid kb_connector.config", exception);
        }
    }

    /**
     * 序列化 JSON。
     *
     * @param value 值
     * @return JSON
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("failed to serialize connector JSON column", exception);
        }
    }

    /**
     * 数据库 UTC 时间转 Instant。
     *
     * @param value 数据库时间
     * @return Instant
     */
    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    /**
     * Instant 转数据库 UTC 时间。
     *
     * @param value Instant
     * @return 数据库时间
     */
    private static LocalDateTime toDatabase(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    /**
     * BINARY(16) 转 UUID。
     *
     * @param value 字节
     * @return UUID
     */
    private static UUID toUuid(byte[] value) {
        return value == null ? null : UuidBinary.fromBytes(value);
    }
}
