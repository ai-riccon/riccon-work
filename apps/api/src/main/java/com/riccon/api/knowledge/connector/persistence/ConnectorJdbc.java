package com.riccon.api.knowledge.connector.persistence;

import com.riccon.api.knowledge.connector.domain.ConnectorConfig;
import com.riccon.api.knowledge.connector.domain.ConnectorDataKeyRow;
import com.riccon.api.knowledge.connector.domain.ConnectorDocumentRow;
import com.riccon.api.knowledge.connector.domain.ConnectorItemRecord;
import com.riccon.api.knowledge.connector.domain.ConnectorRecord;
import com.riccon.api.knowledge.connector.domain.ConnectorSyncSummary;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.knowledge.ConnectorItemStatus;
import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorSyncStatus;
import com.riccon.platform.domain.knowledge.ConnectorType;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 连接器、条目与凭据数据密钥 SQL。
 *
 * <p>所有业务查询带 {@code tenant_id}；连接器与条目都只做软删除。凭据密文只在此层以字节写入/读出，
 * 不进入日志与响应；数据密钥按 {@code connector-credential} 用途懒生成并包裹存储（ADR-0006）。
 *
 * <p>配置与同步摘要按 JSON 列存储；解析失败视为数据损坏，直接抛错而不是返回空配置。
 */
@Component
public class ConnectorJdbc {

    /** 连接器查询列。 */
    private static final String CONNECTOR_COLUMNS = """
            id, name, type, status, endpoint_id, config, default_visibility, default_department_id,
            credential_ciphertext, credential_key_id, credential_masked_hint, schedule_enabled,
            next_sync_at, last_sync_started_at, last_sync_finished_at, last_sync_status, last_sync_complete,
            last_success_at, last_error_code, last_sync_summary, created_by, created_at, updated_at, deleted_at
            """;

    /** 条目查询列。 */
    private static final String ITEM_COLUMNS = """
            id, external_id, external_version, checksum, document_id, generation, status, error_code,
            last_seen_at, created_at, updated_at, deleted_at
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /** JSON 编解码。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建持久化组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     * @param objectMapper JSON 编解码
     */
    public ConnectorJdbc(TenantJdbc tenantJdbc, ObjectMapper objectMapper) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 插入连接器。
     *
     * @param tenantId 当前租户
     * @param connector 连接器快照
     */
    public void insertConnector(TenantId tenantId, ConnectorRecord connector) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(connector.id()));
        params.put("name", connector.name());
        params.put("type", connector.type().name());
        params.put("status", connector.status().name());
        params.put("endpointId", connector.endpointId());
        params.put("config", toJson(connector.config()));
        params.put("visibility", connector.defaultVisibility().name());
        params.put("departmentId", toBytes(connector.defaultDepartmentId()));
        params.put("credentialCiphertext", connector.credentialCiphertext());
        params.put("credentialKeyId", connector.credentialKeyId());
        params.put("credentialMaskedHint", connector.credentialMaskedHint());
        params.put("scheduleEnabled", connector.scheduleEnabled());
        params.put("nextSyncAt", toDatabase(connector.nextSyncAt()));
        params.put("createdBy", toBytes(connector.createdBy()));
        params.put("now", toDatabase(connector.createdAt()));
        tenantJdbc.insert(tenantId, """
                INSERT INTO kb_connector (
                    tenant_id, id, name, type, status, endpoint_id, config, default_visibility,
                    default_department_id, credential_ciphertext, credential_key_id, credential_masked_hint,
                    schedule_enabled, next_sync_at, created_by, created_at, updated_at
                ) VALUES (
                    :tenantId, :id, :name, :type, :status, :endpointId, :config, :visibility,
                    :departmentId, :credentialCiphertext, :credentialKeyId, :credentialMaskedHint,
                    :scheduleEnabled, :nextSyncAt, :createdBy, :now, :now
                )
                """, params);
    }

    /**
     * 按 ID 读取未删除连接器（系统内部使用）。
     *
     * @param tenantId 当前租户
     * @param connectorId 连接器 ID
     * @return 连接器
     */
    public Optional<ConnectorRecord> findActiveById(TenantId tenantId, UUID connectorId) {
        return tenantJdbc.queryOne(
                tenantId,
                "SELECT " + CONNECTOR_COLUMNS + " FROM kb_connector"
                        + " WHERE tenant_id = :tenantId AND id = :connectorId AND deleted_at IS NULL",
                Map.of("connectorId", BinaryUuids.toBytes(connectorId)),
                this::mapConnector);
    }

    /**
     * 按 ID 读取连接器（包含软删除行），用于幂等删除。
     *
     * @param tenantId 当前租户
     * @param connectorId 连接器 ID
     * @return 连接器
     */
    public Optional<ConnectorRecord> findByIdIncludingDeleted(TenantId tenantId, UUID connectorId) {
        return tenantJdbc.queryOne(
                tenantId,
                "SELECT " + CONNECTOR_COLUMNS + " FROM kb_connector"
                        + " WHERE tenant_id = :tenantId AND id = :connectorId",
                Map.of("connectorId", BinaryUuids.toBytes(connectorId)),
                this::mapConnector);
    }

    /**
     * 锁定未删除连接器行，串行化更新/删除/同步受理。
     *
     * @param tenantId 当前租户
     * @param connectorId 连接器 ID
     * @return 连接器
     */
    public Optional<ConnectorRecord> lockActive(TenantId tenantId, UUID connectorId) {
        return lock(tenantId, connectorId, true);
    }

    /**
     * 锁定连接器行（包含软删除），用于幂等删除。
     *
     * @param tenantId 当前租户
     * @param connectorId 连接器 ID
     * @return 连接器
     */
    public Optional<ConnectorRecord> lockIncludingDeleted(TenantId tenantId, UUID connectorId) {
        return lock(tenantId, connectorId, false);
    }

    /**
     * 统计连接器。
     *
     * @param tenantId 当前租户
     * @param status 可选状态
     * @param type 可选类型
     * @return 总数
     */
    public long countConnectors(TenantId tenantId, ConnectorStatus status, ConnectorType type) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM kb_connector WHERE tenant_id = :tenantId AND deleted_at IS NULL");
        Map<String, Object> params = new LinkedHashMap<>();
        appendFilters(sql, params, status, type);
        return tenantJdbc.count(tenantId, sql.toString(), params);
    }

    /**
     * 分页查询连接器。
     *
     * @param tenantId 当前租户
     * @param status 可选状态
     * @param type 可选类型
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 连接器列表
     */
    public List<ConnectorRecord> listConnectors(
            TenantId tenantId, ConnectorStatus status, ConnectorType type, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(CONNECTOR_COLUMNS)
                .append(" FROM kb_connector WHERE tenant_id = :tenantId AND deleted_at IS NULL");
        Map<String, Object> params = new LinkedHashMap<>();
        appendFilters(sql, params, status, type);
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset");
        params.put("limit", limit);
        params.put("offset", offset);
        return tenantJdbc.query(tenantId, sql.toString(), params, this::mapConnector);
    }

    /**
     * 更新连接器的可编辑字段。
     *
     * @param tenantId 当前租户
     * @param connectorId 连接器 ID
     * @param name 名称
     * @param endpointId 端点 ID
     * @param config 配置
     * @param visibility 默认可见范围
     * @param departmentId 默认部门
     * @param scheduleEnabled 是否参与定时同步
     * @param nextSyncAt 下一次定时同步
     * @param status 目标状态
     * @param now 更新时间
     * @return 受影响行数
     */
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
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("connectorId", BinaryUuids.toBytes(connectorId));
        params.put("name", name);
        params.put("endpointId", endpointId);
        params.put("config", toJson(config));
        params.put("visibility", visibility.name());
        params.put("departmentId", toBytes(departmentId));
        params.put("scheduleEnabled", scheduleEnabled);
        params.put("nextSyncAt", toDatabase(nextSyncAt));
        params.put("status", status.name());
        params.put("now", toDatabase(now));
        return tenantJdbc.update(tenantId, """
                UPDATE kb_connector
                   SET name = :name,
                       endpoint_id = :endpointId,
                       config = :config,
                       default_visibility = :visibility,
                       default_department_id = :departmentId,
                       schedule_enabled = :scheduleEnabled,
                       next_sync_at = :nextSyncAt,
                       status = :status,
                       updated_at = :now
                 WHERE tenant_id = :tenantId
                   AND id = :connectorId
                   AND deleted_at IS NULL
                """, params);
    }

    /**
     * 覆盖写入凭据密文、keyId 与掩码；旧密文不保留。
     *
     * @param tenantId 当前租户
     * @param connectorId 连接器 ID
     * @param ciphertext 密文字节
     * @param keyId DEK 标识
     * @param maskedHint 掩码
     * @param now 更新时间
     * @return 受影响行数
     */
    public int updateCredential(
            TenantId tenantId, UUID connectorId, byte[] ciphertext, String keyId, String maskedHint, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("connectorId", BinaryUuids.toBytes(connectorId));
        params.put("ciphertext", ciphertext);
        params.put("keyId", keyId);
        params.put("maskedHint", maskedHint);
        params.put("now", toDatabase(now));
        return tenantJdbc.update(tenantId, """
                UPDATE kb_connector
                   SET credential_ciphertext = :ciphertext,
                       credential_key_id = :keyId,
                       credential_masked_hint = :maskedHint,
                       updated_at = :now
                 WHERE tenant_id = :tenantId
                   AND id = :connectorId
                   AND deleted_at IS NULL
                """, params);
    }

    /**
     * 受理同步时刷新开始时间；保留上一次结果直到 Worker 完成或失败。
     *
     * @param tenantId 当前租户
     * @param connectorId 连接器 ID
     * @param now 开始时间
     * @return 受影响行数
     */
    public int markSyncAccepted(TenantId tenantId, UUID connectorId, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("connectorId", BinaryUuids.toBytes(connectorId));
        params.put("now", toDatabase(now));
        return tenantJdbc.update(tenantId, """
                UPDATE kb_connector
                   SET last_sync_started_at = :now,
                       last_sync_finished_at = NULL,
                       updated_at = :now
                 WHERE tenant_id = :tenantId
                   AND id = :connectorId
                   AND deleted_at IS NULL
                """, params);
    }

    /**
     * 软删除连接器并停止定时同步；凭据密文保留 30 天供误删恢复。
     *
     * @param tenantId 当前租户
     * @param connectorId 连接器 ID
     * @param now 删除时间
     * @return 受影响行数
     */
    public int softDeleteConnector(TenantId tenantId, UUID connectorId, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("connectorId", BinaryUuids.toBytes(connectorId));
        params.put("now", toDatabase(now));
        return tenantJdbc.update(tenantId, """
                UPDATE kb_connector
                   SET deleted_at = :now,
                       schedule_enabled = FALSE,
                       next_sync_at = NULL,
                       updated_at = :now
                 WHERE tenant_id = :tenantId
                   AND id = :connectorId
                   AND deleted_at IS NULL
                """, params);
    }

    /**
     * 查询启用部门。
     *
     * @param tenantId 当前租户
     * @param departmentId 部门 ID
     * @return 命中时返回部门 ID
     */
    public Optional<UUID> findActiveDepartment(TenantId tenantId, UUID departmentId) {
        return tenantJdbc.queryOne(
                tenantId,
                "SELECT id FROM department WHERE tenant_id = :tenantId AND id = :departmentId AND status = 'ACTIVE'",
                Map.of("departmentId", BinaryUuids.toBytes(departmentId)),
                (rs, rowNum) -> BinaryUuids.fromBytes(rs.getBytes("id")));
    }

    /**
     * 查询连接器已导入的未删除文档。
     *
     * @param tenantId 当前租户
     * @param connectorId 连接器 ID
     * @return 文档 ID、可见范围与 ACL 版本
     */
    public List<ConnectorDocumentRow> findActiveDocuments(TenantId tenantId, UUID connectorId) {
        return tenantJdbc.query(
                tenantId,
                """
                SELECT id, visibility, department_id, acl_version, source_type
                  FROM knowledge_document
                 WHERE tenant_id = :tenantId
                   AND connector_id = :connectorId
                   AND deleted_at IS NULL
                 ORDER BY created_at ASC, id ASC
                """,
                Map.of("connectorId", BinaryUuids.toBytes(connectorId)),
                (rs, rowNum) -> new ConnectorDocumentRow(
                        BinaryUuids.fromBytes(rs.getBytes("id")),
                        KnowledgeVisibility.valueOf(rs.getString("visibility")),
                        rs.getBytes("department_id") == null
                                ? null
                                : BinaryUuids.fromBytes(rs.getBytes("department_id")),
                        rs.getInt("acl_version"),
                        rs.getString("source_type")));
    }

    /**
     * 软删除连接器导入的文档。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param now 删除时间
     * @return 受影响行数
     */
    public int softDeleteDocument(TenantId tenantId, UUID documentId, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("documentId", BinaryUuids.toBytes(documentId));
        params.put("now", toDatabase(now));
        return tenantJdbc.update(tenantId, """
                UPDATE knowledge_document
                   SET status = 'DELETED', deleted_at = :now, updated_at = :now
                 WHERE tenant_id = :tenantId
                   AND id = :documentId
                   AND deleted_at IS NULL
                """, params);
    }

    /**
     * 查询连接器同步事件是否仍在排队或处理中；并发同步判定以此为准（不依赖内存锁）。
     *
     * @param tenantId 当前租户
     * @param connectorId 连接器 ID
     * @return 存在未完成事件时返回 true
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
     * 计算文档下一次索引尝试号，保证重复删除事件幂等键不冲突。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 下一次尝试号（从 1 开始）
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
     * 读取连接器凭据用途的活跃 DEK。
     *
     * @param tenantId 当前租户
     * @param purpose DEK 用途
     * @return 数据密钥记录
     */
    public Optional<ConnectorDataKeyRow> findActiveDataKey(TenantId tenantId, String purpose) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT key_id, wrapped_dek, wrap_nonce, algorithm, master_key_id
                  FROM tenant_data_key
                 WHERE tenant_id = :tenantId
                   AND purpose = :purpose
                   AND retired_at IS NULL
                 ORDER BY created_at ASC
                 LIMIT 1
                """,
                Map.of("purpose", purpose),
                (rs, rowNum) -> new ConnectorDataKeyRow(
                        rs.getString("key_id"),
                        rs.getBytes("wrapped_dek"),
                        rs.getBytes("wrap_nonce"),
                        rs.getString("algorithm"),
                        rs.getString("master_key_id")));
    }

    /**
     * 按 keyId 读取连接器凭据用途的 DEK（Worker 解密侧同构查询；API 测试也使用）。
     *
     * @param tenantId 当前租户
     * @param purpose DEK 用途
     * @param keyId DEK 标识
     * @return 数据密钥记录
     */
    public Optional<ConnectorDataKeyRow> findDataKey(TenantId tenantId, String purpose, String keyId) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT key_id, wrapped_dek, wrap_nonce, algorithm, master_key_id
                  FROM tenant_data_key
                 WHERE tenant_id = :tenantId
                   AND purpose = :purpose
                   AND key_id = :keyId
                   AND retired_at IS NULL
                """,
                Map.of("purpose", purpose, "keyId", keyId),
                (rs, rowNum) -> new ConnectorDataKeyRow(
                        rs.getString("key_id"),
                        rs.getBytes("wrapped_dek"),
                        rs.getBytes("wrap_nonce"),
                        rs.getString("algorithm"),
                        rs.getString("master_key_id")));
    }

    /**
     * 插入连接器凭据 DEK。
     *
     * @param tenantId 当前租户
     * @param keyId DEK 标识
     * @param purpose 用途
     * @param wrappedDek 被主密钥包裹的 DEK
     * @param wrapNonce 包裹 nonce
     * @param algorithm 算法
     * @param masterKeyId 主密钥标识
     */
    public void insertDataKey(
            TenantId tenantId,
            String keyId,
            String purpose,
            byte[] wrappedDek,
            byte[] wrapNonce,
            String algorithm,
            String masterKeyId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("keyId", keyId);
        params.put("purpose", purpose);
        params.put("wrappedDek", wrappedDek);
        params.put("wrapNonce", wrapNonce);
        params.put("algorithm", algorithm);
        params.put("masterKeyId", masterKeyId);
        tenantJdbc.insert(tenantId, """
                INSERT INTO tenant_data_key (
                    tenant_id, key_id, purpose, wrapped_dek, wrap_nonce, algorithm, master_key_id
                ) VALUES (
                    :tenantId, :keyId, :purpose, :wrappedDek, :wrapNonce, :algorithm, :masterKeyId
                )
                """, params);
    }

    /**
     * 统计条目。
     *
     * @param tenantId 当前租户
     * @param connectorId 连接器 ID
     * @param status 可选状态
     * @return 总数
     */
    public long countItems(TenantId tenantId, UUID connectorId, ConnectorItemStatus status) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM kb_connector_item WHERE tenant_id = :tenantId AND connector_id = :connectorId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("connectorId", BinaryUuids.toBytes(connectorId));
        if (status != null) {
            sql.append(" AND status = :status");
            params.put("status", status.name());
        }
        return tenantJdbc.count(tenantId, sql.toString(), params);
    }

    /**
     * 分页查询条目。
     *
     * @param tenantId 当前租户
     * @param connectorId 连接器 ID
     * @param status 可选状态
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 条目列表
     */
    public List<ConnectorItemRecord> listItems(
            TenantId tenantId, UUID connectorId, ConnectorItemStatus status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(ITEM_COLUMNS)
                .append(" FROM kb_connector_item WHERE tenant_id = :tenantId AND connector_id = :connectorId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("connectorId", BinaryUuids.toBytes(connectorId));
        if (status != null) {
            sql.append(" AND status = :status");
            params.put("status", status.name());
        }
        sql.append(" ORDER BY updated_at DESC, id DESC LIMIT :limit OFFSET :offset");
        params.put("limit", limit);
        params.put("offset", offset);
        return tenantJdbc.query(tenantId, sql.toString(), params, this::mapItem);
    }

    /**
     * 锁定连接器行。
     *
     * @param tenantId 当前租户
     * @param connectorId 连接器 ID
     * @param excludeDeleted 是否排除软删除
     * @return 连接器
     */
    private Optional<ConnectorRecord> lock(TenantId tenantId, UUID connectorId, boolean excludeDeleted) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(CONNECTOR_COLUMNS)
                .append(" FROM kb_connector WHERE tenant_id = :tenantId AND id = :connectorId");
        if (excludeDeleted) {
            sql.append(" AND deleted_at IS NULL");
        }
        sql.append(" FOR UPDATE");
        return tenantJdbc.queryOne(
                tenantId, sql.toString(), Map.of("connectorId", BinaryUuids.toBytes(connectorId)), this::mapConnector);
    }

    /**
     * 追加连接器过滤条件。
     *
     * @param sql SQL 构造器
     * @param params 参数
     * @param status 状态
     * @param type 类型
     */
    private static void appendFilters(
            StringBuilder sql, Map<String, Object> params, ConnectorStatus status, ConnectorType type) {
        if (status != null) {
            sql.append(" AND status = :status");
            params.put("status", status.name());
        }
        if (type != null) {
            sql.append(" AND type = :type");
            params.put("type", type.name());
        }
    }

    /**
     * 映射连接器行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 连接器快照
     * @throws SQLException 列读取失败
     */
    private ConnectorRecord mapConnector(ResultSet rs, int rowNum) throws SQLException {
        return new ConnectorRecord(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                rs.getString("name"),
                ConnectorType.valueOf(rs.getString("type")),
                ConnectorStatus.valueOf(rs.getString("status")),
                rs.getString("endpoint_id"),
                parseConfig(rs.getString("config")),
                KnowledgeVisibility.valueOf(rs.getString("default_visibility")),
                toUuid(rs.getBytes("default_department_id")),
                rs.getBytes("credential_ciphertext"),
                rs.getString("credential_key_id"),
                rs.getString("credential_masked_hint"),
                rs.getBoolean("schedule_enabled"),
                toInstant(rs.getObject("next_sync_at", LocalDateTime.class)),
                toInstant(rs.getObject("last_sync_started_at", LocalDateTime.class)),
                toInstant(rs.getObject("last_sync_finished_at", LocalDateTime.class)),
                toSyncStatus(rs.getString("last_sync_status")),
                rs.getBoolean("last_sync_complete"),
                toInstant(rs.getObject("last_success_at", LocalDateTime.class)),
                rs.getString("last_error_code"),
                parseSummary(rs.getString("last_sync_summary")),
                toUuid(rs.getBytes("created_by")),
                toInstant(rs.getObject("created_at", LocalDateTime.class)),
                toInstant(rs.getObject("updated_at", LocalDateTime.class)),
                toInstant(rs.getObject("deleted_at", LocalDateTime.class)));
    }

    /**
     * 映射条目行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 条目快照
     * @throws SQLException 列读取失败
     */
    private ConnectorItemRecord mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new ConnectorItemRecord(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                rs.getString("external_id"),
                rs.getString("external_version"),
                rs.getString("checksum"),
                toUuid(rs.getBytes("document_id")),
                rs.getInt("generation"),
                ConnectorItemStatus.valueOf(rs.getString("status")),
                rs.getString("error_code"),
                toInstant(rs.getObject("last_seen_at", LocalDateTime.class)),
                toInstant(rs.getObject("created_at", LocalDateTime.class)),
                toInstant(rs.getObject("updated_at", LocalDateTime.class)),
                toInstant(rs.getObject("deleted_at", LocalDateTime.class)));
    }

    /**
     * 解析配置 JSON。
     *
     * @param json JSON 文本
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
     * 解析同步摘要 JSON；空值返回 {@code null}。
     *
     * @param json JSON 文本
     * @return 摘要
     */
    private ConnectorSyncSummary parseSummary(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ConnectorSyncSummary.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("invalid kb_connector.last_sync_summary", exception);
        }
    }

    /**
     * 序列化 JSON 列；失败属于实现错误。
     *
     * @param value 值
     * @return JSON 文本
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("failed to serialize connector JSON column", exception);
        }
    }

    /**
     * 解析同步状态；空值返回 {@code null}。
     *
     * @param value 数据库值
     * @return 状态
     */
    private static ConnectorSyncStatus toSyncStatus(String value) {
        return value == null ? null : ConnectorSyncStatus.valueOf(value);
    }

    /**
     * 数据库 UTC 时间转 Instant。
     *
     * @param value 数据库时间
     * @return Instant
     */
    private static Instant toInstant(LocalDateTime value) {
        return SqlTimestamps.toInstant(value);
    }

    /**
     * Instant 转数据库 UTC 时间。
     *
     * @param value Instant
     * @return 数据库时间
     */
    private static LocalDateTime toDatabase(Instant value) {
        return SqlTimestamps.toDatabase(value);
    }

    /**
     * UUID 转 BINARY(16)。
     *
     * @param value UUID
     * @return 字节
     */
    private static byte[] toBytes(UUID value) {
        return value == null ? null : BinaryUuids.toBytes(value);
    }

    /**
     * BINARY(16) 转 UUID。
     *
     * @param value 字节
     * @return UUID
     */
    private static UUID toUuid(byte[] value) {
        return value == null ? null : BinaryUuids.fromBytes(value);
    }
}
