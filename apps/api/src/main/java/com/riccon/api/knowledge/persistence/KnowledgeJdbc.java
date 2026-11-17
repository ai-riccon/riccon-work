package com.riccon.api.knowledge.persistence;

import com.riccon.api.knowledge.domain.KnowledgeDocumentRecord;
import com.riccon.api.knowledge.domain.KnowledgeSourceType;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
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

/**
 * 知识库文档 SQL。
 *
 * <p>所有查询带 {@code tenant_id} 且默认排除软删除；M02 无文档级 ACL，同租户员工可读取全部未删除文档。
 */
@Component
public class KnowledgeJdbc {

    /** 响应与索引共用的列。 */
    private static final String COLUMNS = """
            id, title, source_type, file_id, checksum, mime_type, size_bytes, status,
            error_code, error_message, indexed_at, created_by, created_at
            """;

    /** 按 ID 查询。 */
    private static final String FIND_BY_ID = "SELECT " + COLUMNS
            + " FROM knowledge_document"
            + " WHERE tenant_id = :tenantId AND id = :documentId AND deleted_at IS NULL";

    /** 按来源文件查询，用于重复登记收敛到同一文档。 */
    private static final String FIND_BY_FILE = "SELECT " + COLUMNS
            + " FROM knowledge_document"
            + " WHERE tenant_id = :tenantId AND file_id = :fileId AND deleted_at IS NULL"
            + " ORDER BY created_at ASC LIMIT 1";

    /** 插入文档。 */
    private static final String INSERT = """
            INSERT INTO knowledge_document (
                tenant_id, id, title, source_type, file_id, checksum, mime_type, size_bytes,
                status, created_by, created_at, updated_at
            ) VALUES (
                :tenantId, :id, :title, :sourceType, :fileId, :checksum, :mimeType, :sizeBytes,
                :status, :createdBy, :createdAt, :createdAt
            )
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建知识库 SQL 组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public KnowledgeJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 插入文档。
     *
     * @param tenantId 当前租户
     * @param document 文档快照
     */
    public void insert(TenantId tenantId, KnowledgeDocumentRecord document) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(document.id()));
        params.put("title", document.title());
        params.put("sourceType", document.sourceType().name());
        params.put("fileId", document.fileId() == null ? null : BinaryUuids.toBytes(document.fileId()));
        params.put("checksum", document.checksum());
        params.put("mimeType", document.mimeType());
        params.put("sizeBytes", document.sizeBytes());
        params.put("status", document.status().name());
        params.put("createdBy", document.createdBy() == null ? null : BinaryUuids.toBytes(document.createdBy()));
        params.put("createdAt", SqlTimestamps.toDatabase(document.createdAt()));
        tenantJdbc.insert(tenantId, INSERT, params);
    }

    /**
     * 按 ID 查询未删除文档。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 文档
     */
    public Optional<KnowledgeDocumentRecord> findById(TenantId tenantId, UUID documentId) {
        return tenantJdbc.queryOne(
                tenantId, FIND_BY_ID, Map.of("documentId", BinaryUuids.toBytes(documentId)), KnowledgeJdbc::mapRow);
    }

    /**
     * 按来源文件查询未删除文档。
     *
     * @param tenantId 当前租户
     * @param fileId 文件 ID
     * @return 文档
     */
    public Optional<KnowledgeDocumentRecord> findByFileId(TenantId tenantId, UUID fileId) {
        return tenantJdbc.queryOne(
                tenantId, FIND_BY_FILE, Map.of("fileId", BinaryUuids.toBytes(fileId)), KnowledgeJdbc::mapRow);
    }

    /**
     * 统计未删除文档。
     *
     * @param tenantId 当前租户
     * @param status 可选状态过滤
     * @return 总数
     */
    public long count(TenantId tenantId, KnowledgeDocumentStatus status) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM knowledge_document WHERE tenant_id = :tenantId AND deleted_at IS NULL");
        Map<String, Object> params = new LinkedHashMap<>();
        appendStatusFilter(sql, params, status);
        return tenantJdbc.count(tenantId, sql.toString(), params);
    }

    /**
     * 分页查询未删除文档。
     *
     * @param tenantId 当前租户
     * @param status 可选状态过滤
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 文档列表
     */
    public List<KnowledgeDocumentRecord> list(
            TenantId tenantId, KnowledgeDocumentStatus status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(COLUMNS)
                .append(" FROM knowledge_document WHERE tenant_id = :tenantId AND deleted_at IS NULL");
        Map<String, Object> params = new LinkedHashMap<>();
        appendStatusFilter(sql, params, status);
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset");
        params.put("limit", limit);
        params.put("offset", offset);
        return tenantJdbc.query(tenantId, sql.toString(), params, KnowledgeJdbc::mapRow);
    }

    /**
     * 追加可选状态过滤。
     *
     * @param sql SQL 构造器
     * @param params 参数
     * @param status 状态
     */
    private static void appendStatusFilter(
            StringBuilder sql, Map<String, Object> params, KnowledgeDocumentStatus status) {
        if (status != null) {
            sql.append(" AND status = :status");
            params.put("status", status.name());
        }
    }

    /**
     * 映射文档行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 文档快照
     * @throws SQLException 列读取失败
     */
    private static KnowledgeDocumentRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        byte[] fileId = rs.getBytes("file_id");
        byte[] createdBy = rs.getBytes("created_by");
        return new KnowledgeDocumentRecord(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                rs.getString("title"),
                KnowledgeSourceType.valueOf(rs.getString("source_type")),
                fileId == null ? null : BinaryUuids.fromBytes(fileId),
                rs.getString("checksum"),
                rs.getString("mime_type"),
                rs.getLong("size_bytes"),
                KnowledgeDocumentStatus.valueOf(rs.getString("status")),
                rs.getString("error_code"),
                rs.getString("error_message"),
                toInstant(rs.getObject("indexed_at", LocalDateTime.class)),
                createdBy == null ? null : BinaryUuids.fromBytes(createdBy),
                toInstant(rs.getObject("created_at", LocalDateTime.class)));
    }

    /**
     * 将数据库 UTC 时间转为 Instant。
     *
     * @param value 数据库时间
     * @return UTC 时间；null 原样返回
     */
    private static Instant toInstant(LocalDateTime value) {
        return SqlTimestamps.toInstant(value);
    }
}
