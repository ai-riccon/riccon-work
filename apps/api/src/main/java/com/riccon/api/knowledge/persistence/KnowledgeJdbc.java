package com.riccon.api.knowledge.persistence;

import com.riccon.api.knowledge.domain.KnowledgeDocumentRecord;
import com.riccon.api.knowledge.domain.KnowledgeParseSummary;
import com.riccon.api.knowledge.domain.KnowledgeSourceType;
import com.riccon.api.knowledge.domain.KnowledgeVisibilityScope;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import com.riccon.platform.domain.knowledge.ParseEngine;
import com.riccon.platform.domain.workspace.WorkspaceVisibility;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 知识库文档 SQL。
 *
 * <p>所有查询带 {@code tenant_id} 且默认排除软删除；可见性谓词由 {@link KnowledgeAccessJdbc} 统一追加，
 * 本类不自行实现第二套 ACL。文档响应所需的源文件空间可见性在同一查询内 LEFT JOIN 取得，避免列表 N+1。
 */
@Component
public class KnowledgeJdbc {

    /** 文档行与源文件空间可见性共用的列。 */
    private static final String SELECT_COLUMNS = """
            d.id, d.title, d.source_type, d.file_id, d.checksum, d.mime_type, d.size_bytes, d.status,
            d.error_code, d.error_message, d.indexed_at, d.created_by, d.created_at,
            d.updated_at,
            (SELECT MAX(fp.finished_at) FROM file_parse fp
              WHERE fp.tenant_id = d.tenant_id
                AND fp.file_id = d.file_id
                AND fp.status = 'FAILED') AS failed_at,
            d.visibility, d.department_id, d.acl_version, d.parse_engine, d.parse_warnings, d.parse_summary,
            d.retention_expires_at, d.deleted_at,
            (SELECT COUNT(*) FROM knowledge_document_acl a
              WHERE a.tenant_id = d.tenant_id AND a.document_id = d.id) AS acl_user_count,
            w.visibility AS source_visibility, w.department_id AS source_department_id
            """;

    /** 文档表与源文件空间关联；源文件软删除后 {@code w.*} 为空，下载返回 KB-404。 */
    private static final String FROM_JOINS = """
            FROM knowledge_document d
            LEFT JOIN file f
              ON f.tenant_id = d.tenant_id
             AND f.id = d.file_id
             AND f.deleted_at IS NULL
            LEFT JOIN workspace w
              ON w.tenant_id = f.tenant_id
             AND w.id = f.workspace_id
            """;

    /** 插入文档。 */
    private static final String INSERT = """
            INSERT INTO knowledge_document (
                tenant_id, id, title, source_type, file_id, checksum, mime_type, size_bytes,
                status, created_by, created_at, updated_at, visibility, department_id, parse_engine,
                acl_version, retention_expires_at
            ) VALUES (
                :tenantId, :id, :title, :sourceType, :fileId, :checksum, :mimeType, :sizeBytes,
                :status, :createdBy, :createdAt, :createdAt, :visibility, :departmentId, :parseEngine,
                1, :retentionExpiresAt
            )
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /** 可见性谓词唯一实现。 */
    private final KnowledgeAccessJdbc accessJdbc;

    /** JSON 列解析。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建知识库 SQL 组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     * @param accessJdbc 可见性谓词
     * @param objectMapper JSON 解析器
     */
    public KnowledgeJdbc(TenantJdbc tenantJdbc, KnowledgeAccessJdbc accessJdbc, ObjectMapper objectMapper) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
        this.accessJdbc = Objects.requireNonNull(accessJdbc, "accessJdbc must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
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
        params.put("fileId", toBytes(document.fileId()));
        params.put("checksum", document.checksum());
        params.put("mimeType", document.mimeType());
        params.put("sizeBytes", document.sizeBytes());
        params.put("status", document.status().name());
        params.put("createdBy", toBytes(document.createdBy()));
        params.put("createdAt", SqlTimestamps.toDatabase(document.createdAt()));
        params.put("visibility", document.visibility().name());
        params.put("departmentId", toBytes(document.departmentId()));
        params.put(
                "parseEngine",
                document.parseEngine() == null ? null : document.parseEngine().name());
        params.put(
                "retentionExpiresAt",
                document.retentionExpiresAt() == null ? null : SqlTimestamps.toDatabase(document.retentionExpiresAt()));
        tenantJdbc.insert(tenantId, INSERT, params);
    }

    /**
     * 按 ID 查询未删除文档（系统内部使用，不判定 ACL）。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 文档
     */
    public Optional<KnowledgeDocumentRecord> findActiveById(TenantId tenantId, UUID documentId) {
        return tenantJdbc.queryOne(
                tenantId,
                "SELECT " + SELECT_COLUMNS + FROM_JOINS
                        + " WHERE d.tenant_id = :tenantId AND d.id = :documentId AND d.deleted_at IS NULL",
                Map.of("documentId", BinaryUuids.toBytes(documentId)),
                this::mapRow);
    }

    /**
     * 按来源文件查询未删除文档，用于重复登记收敛。
     *
     * @param tenantId 当前租户
     * @param fileId 文件 ID
     * @return 文档
     */
    public Optional<KnowledgeDocumentRecord> findActiveByFileId(TenantId tenantId, UUID fileId) {
        return tenantJdbc.queryOne(
                tenantId,
                "SELECT " + SELECT_COLUMNS + FROM_JOINS
                        + " WHERE d.tenant_id = :tenantId AND d.file_id = :fileId AND d.deleted_at IS NULL"
                        + " ORDER BY d.created_at ASC LIMIT 1",
                Map.of("fileId", BinaryUuids.toBytes(fileId)),
                this::mapRow);
    }

    /**
     * 按 ACL 谓词读取未删除文档。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param scope 可见性上下文
     * @return 文档；不可见时为空
     */
    public Optional<KnowledgeDocumentRecord> findVisibleById(
            TenantId tenantId, UUID documentId, KnowledgeVisibilityScope scope) {
        return findVisible(tenantId, documentId, scope, true);
    }

    /**
     * 按 ACL 谓词读取文档，包含已软删除行。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param scope 可见性上下文
     * @return 文档；不可见时为空
     */
    public Optional<KnowledgeDocumentRecord> findVisibleByIdIncludingDeleted(
            TenantId tenantId, UUID documentId, KnowledgeVisibilityScope scope) {
        return findVisible(tenantId, documentId, scope, false);
    }

    /**
     * 管理性读取：不追加 ACL 谓词，仅排除软删除文档。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 文档
     */
    public Optional<KnowledgeDocumentRecord> findActiveByIdIgnoringAcl(TenantId tenantId, UUID documentId) {
        return findActiveById(tenantId, documentId);
    }

    /**
     * 统计未删除文档。
     *
     * @param tenantId 当前租户
     * @param status 可选状态过滤
     * @param scope 可见性上下文
     * @return 总数
     */
    public long count(TenantId tenantId, KnowledgeDocumentStatus status, KnowledgeVisibilityScope scope) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM knowledge_document d WHERE d.tenant_id = :tenantId AND d.deleted_at IS NULL");
        Map<String, Object> params = new LinkedHashMap<>();
        accessJdbc.appendVisibilityPredicate(sql, params, scope, "d");
        appendStatusFilter(sql, params, status);
        return tenantJdbc.count(tenantId, sql.toString(), params);
    }

    /**
     * 分页查询未删除文档。
     *
     * @param tenantId 当前租户
     * @param status 可选状态过滤
     * @param scope 可见性上下文
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 文档列表
     */
    public List<KnowledgeDocumentRecord> list(
            TenantId tenantId, KnowledgeDocumentStatus status, KnowledgeVisibilityScope scope, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(SELECT_COLUMNS)
                .append(FROM_JOINS)
                .append(" WHERE d.tenant_id = :tenantId AND d.deleted_at IS NULL");
        Map<String, Object> params = new LinkedHashMap<>();
        accessJdbc.appendVisibilityPredicate(sql, params, scope, "d");
        appendStatusFilter(sql, params, status);
        sql.append(" ORDER BY d.created_at DESC, d.id DESC LIMIT :limit OFFSET :offset");
        params.put("limit", limit);
        params.put("offset", offset);
        return tenantJdbc.query(tenantId, sql.toString(), params, this::mapRow);
    }

    /**
     * 锁定可见且未删除的文档行，串行化 ACL/删除/重索引。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param scope 可见性上下文
     * @return 命中时返回 {@code true}
     */
    public boolean lockVisibleId(TenantId tenantId, UUID documentId, KnowledgeVisibilityScope scope) {
        return lockVisibleId(tenantId, documentId, scope, true);
    }

    /**
     * 锁定可见文档行，包含已软删除行（用于幂等删除）。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param scope 可见性上下文
     * @return 命中时返回 {@code true}
     */
    public boolean lockVisibleIdIncludingDeleted(TenantId tenantId, UUID documentId, KnowledgeVisibilityScope scope) {
        return lockVisibleId(tenantId, documentId, scope, false);
    }

    /**
     * 软删除未删除文档；重复删除返回 0。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param now 删除时间
     * @return 受影响行数
     */
    public int softDelete(TenantId tenantId, UUID documentId, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("documentId", BinaryUuids.toBytes(documentId));
        params.put("now", SqlTimestamps.toDatabase(now));
        params.put("deletedStatus", KnowledgeDocumentStatus.DELETED.name());
        return tenantJdbc.update(tenantId, """
                UPDATE knowledge_document
                   SET status = :deletedStatus, deleted_at = :now, updated_at = :now
                 WHERE tenant_id = :tenantId
                   AND id = :documentId
                   AND deleted_at IS NULL
                """, params);
    }

    /**
     * 整体替换可见范围并递增 ACL 版本。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param visibility 新可见范围
     * @param departmentId 新部门；非 {@code DEPT} 为空
     * @param now 更新时间
     * @return 受影响行数
     */
    public int updateVisibility(
            TenantId tenantId, UUID documentId, KnowledgeVisibility visibility, UUID departmentId, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("documentId", BinaryUuids.toBytes(documentId));
        params.put("visibility", visibility.name());
        params.put("departmentId", toBytes(departmentId));
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, """
                UPDATE knowledge_document
                   SET visibility = :visibility,
                       department_id = :departmentId,
                       acl_version = acl_version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId
                   AND id = :documentId
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
                """
                SELECT id FROM department
                 WHERE tenant_id = :tenantId AND id = :departmentId AND status = 'ACTIVE'
                """,
                Map.of("departmentId", BinaryUuids.toBytes(departmentId)),
                (rs, rowNum) -> BinaryUuids.fromBytes(rs.getBytes("id")));
    }

    /**
     * 查询租户全部启用账号。
     *
     * @param tenantId 当前租户
     * @return 账号 ID 集合
     */
    public Set<UUID> findActiveUserIds(TenantId tenantId) {
        return tenantJdbc
                .query(
                        tenantId,
                        "SELECT id FROM app_user WHERE tenant_id = :tenantId AND status = 'ACTIVE'",
                        Map.of(),
                        (rs, rowNum) -> BinaryUuids.fromBytes(rs.getBytes("id")))
                .stream()
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 查询账号展示名。
     *
     * @param tenantId 当前租户
     * @param userId 账号 ID
     * @return 展示名
     */
    public Optional<String> findDisplayName(TenantId tenantId, UUID userId) {
        return tenantJdbc.queryOne(
                tenantId,
                "SELECT display_name FROM app_user WHERE tenant_id = :tenantId AND id = :userId",
                Map.of("userId", BinaryUuids.toBytes(userId)),
                (rs, rowNum) -> rs.getString("display_name"));
    }

    /**
     * 查询租户第一个启用账号，作为保留清理索引事件的技术发起人。
     *
     * @param tenantId 当前租户
     * @return 账号 ID
     */
    public Optional<UUID> findFirstActiveUserId(TenantId tenantId) {
        return tenantJdbc.queryOne(tenantId, """
                SELECT id FROM app_user
                 WHERE tenant_id = :tenantId AND status = 'ACTIVE'
                 ORDER BY created_at ASC, id ASC LIMIT 1
                """, Map.of(), (rs, rowNum) -> BinaryUuids.fromBytes(rs.getBytes("id")));
    }

    /**
     * 判断文档是否已有 MySQL 分块或曾完成索引。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 有分块时返回 {@code true}
     */
    public boolean hasChunks(TenantId tenantId, UUID documentId) {
        return tenantJdbc
                .queryOne(
                        tenantId,
                        """
                        SELECT 1 AS present FROM knowledge_chunk
                         WHERE tenant_id = :tenantId AND document_id = :documentId LIMIT 1
                        """,
                        Map.of("documentId", BinaryUuids.toBytes(documentId)),
                        (rs, rowNum) -> rs.getInt("present"))
                .isPresent();
    }

    /**
     * 计算文档下一次索引尝试号。
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
     * 判断文档是否已有排队或处理中的索引事件。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 存在未完成事件时返回 {@code true}
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
     * 查询保留到期文档（系统任务，不判定 ACL）。
     *
     * @param tenantId 当前租户
     * @param createdCutoff 无显式到期时 {@code created_at} 的截止时间
     * @param now 当前时间
     * @param limit 批量上限
     * @return 到期文档
     */
    public List<KnowledgeDocumentRecord> findExpired(TenantId tenantId, Instant createdCutoff, Instant now, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("createdCutoff", SqlTimestamps.toDatabase(createdCutoff));
        params.put("now", SqlTimestamps.toDatabase(now));
        params.put("limit", limit);
        return tenantJdbc.query(tenantId, "SELECT " + SELECT_COLUMNS + FROM_JOINS + """
                         WHERE d.tenant_id = :tenantId
                           AND d.deleted_at IS NULL
                           AND d.status IN ('INDEXED', 'INDEXING', 'FAILED', 'PENDING')
                           AND (
                               (d.retention_expires_at IS NOT NULL AND d.retention_expires_at <= :now)
                               OR (d.retention_expires_at IS NULL AND d.created_at <= :createdCutoff)
                           )
                         ORDER BY d.created_at ASC, d.id ASC
                         LIMIT :limit
                        """, params, this::mapRow);
    }

    /**
     * 按 ID 与 ACL 谓词查询文档。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param scope 可见性上下文
     * @param excludeDeleted 是否排除软删除
     * @return 文档
     */
    private Optional<KnowledgeDocumentRecord> findVisible(
            TenantId tenantId, UUID documentId, KnowledgeVisibilityScope scope, boolean excludeDeleted) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(SELECT_COLUMNS)
                .append(FROM_JOINS)
                .append(" WHERE d.tenant_id = :tenantId AND d.id = :documentId");
        if (excludeDeleted) {
            sql.append(" AND d.deleted_at IS NULL");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("documentId", BinaryUuids.toBytes(documentId));
        accessJdbc.appendVisibilityPredicate(sql, params, scope, "d");
        return tenantJdbc.queryOne(tenantId, sql.toString(), params, this::mapRow);
    }

    /**
     * 锁定可见文档行。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param scope 可见性上下文
     * @param excludeDeleted 是否排除软删除
     * @return 命中时返回 {@code true}
     */
    private boolean lockVisibleId(
            TenantId tenantId, UUID documentId, KnowledgeVisibilityScope scope, boolean excludeDeleted) {
        StringBuilder sql = new StringBuilder("SELECT d.id, d.tenant_id FROM knowledge_document d")
                .append(" WHERE d.tenant_id = :tenantId AND d.id = :documentId");
        if (excludeDeleted) {
            sql.append(" AND d.deleted_at IS NULL");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("documentId", BinaryUuids.toBytes(documentId));
        accessJdbc.appendVisibilityPredicate(sql, params, scope, "d");
        sql.append(" FOR UPDATE");
        return tenantJdbc
                .queryOne(tenantId, sql.toString(), params, (rs, rowNum) -> rs.getBytes("id"))
                .isPresent();
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
            sql.append(" AND d.status = :status");
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
    private KnowledgeDocumentRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        KnowledgeVisibility visibility = KnowledgeVisibility.valueOf(rs.getString("visibility"));
        UUID departmentId = toUuid(rs.getBytes("department_id"));
        String sourceVisibility = rs.getString("source_visibility");
        UUID sourceDepartmentId = toUuid(rs.getBytes("source_department_id"));
        KnowledgeDocumentStatus status = KnowledgeDocumentStatus.valueOf(rs.getString("status"));
        Instant updatedAt = toInstant(rs.getObject("updated_at", LocalDateTime.class));
        Instant parseFailedAt = toInstant(rs.getObject("failed_at", LocalDateTime.class));
        Instant failedAt =
                status == KnowledgeDocumentStatus.FAILED ? (parseFailedAt != null ? parseFailedAt : updatedAt) : null;
        return new KnowledgeDocumentRecord(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                rs.getString("title"),
                KnowledgeSourceType.valueOf(rs.getString("source_type")),
                toUuid(rs.getBytes("file_id")),
                rs.getString("checksum"),
                rs.getString("mime_type"),
                rs.getLong("size_bytes"),
                status,
                rs.getString("error_code"),
                rs.getString("error_message"),
                toInstant(rs.getObject("indexed_at", LocalDateTime.class)),
                failedAt,
                toUuid(rs.getBytes("created_by")),
                toInstant(rs.getObject("created_at", LocalDateTime.class)),
                visibility,
                departmentId,
                rs.getInt("acl_version"),
                toParseEngine(rs.getString("parse_engine")),
                parseWarnings(rs.getString("parse_warnings")),
                parseSummary(rs.getString("parse_summary")),
                toInstant(rs.getObject("retention_expires_at", LocalDateTime.class)),
                sourceFileAccessLimited(visibility, departmentId, sourceVisibility, sourceDepartmentId),
                rs.getLong("acl_user_count"),
                toInstant(rs.getObject("deleted_at", LocalDateTime.class)));
    }

    /**
     * 判断文档可见集合是否宽于源文件空间可见集合。
     *
     * @param visibility 文档可见范围
     * @param departmentId 文档部门
     * @param sourceVisibility 源文件空间可见性；无源文件为空
     * @param sourceDepartmentId 源文件空间部门
     * @return 宽于源空间时返回 {@code true}
     */
    private static boolean sourceFileAccessLimited(
            KnowledgeVisibility visibility, UUID departmentId, String sourceVisibility, UUID sourceDepartmentId) {
        if (sourceVisibility == null) {
            // 无源文件（SEED）或源文件已删除：知识库下载会返回 KB-404，不产生“可下载但空间受限”的提示。
            return false;
        }
        WorkspaceVisibility workspaceVisibility;
        try {
            workspaceVisibility = WorkspaceVisibility.valueOf(sourceVisibility);
        } catch (IllegalArgumentException exception) {
            // 历史行 visibility 可能为空（V9 前写入）；按最保守的个人空间解释，提示用户可用知识库下载。
            return true;
        }
        return switch (visibility) {
            case TENANT -> workspaceVisibility != WorkspaceVisibility.TENANT;
            case DEPT ->
                !(workspaceVisibility == WorkspaceVisibility.TENANT
                        || (workspaceVisibility == WorkspaceVisibility.DEPT
                                && Objects.equals(departmentId, sourceDepartmentId)));
            case USERS -> workspaceVisibility != WorkspaceVisibility.TENANT;
        };
    }

    /**
     * 解析解析警告 JSON。
     *
     * @param json JSON 文本
     * @return 警告列表
     */
    private List<String> parseWarnings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> warnings = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            return warnings == null ? List.of() : warnings;
        } catch (JacksonException exception) {
            throw new IllegalStateException("invalid knowledge_document.parse_warnings", exception);
        }
    }

    /**
     * 解析结构摘要 JSON。
     *
     * @param json JSON 文本
     * @return 结构摘要；空值返回 {@code null}
     */
    private KnowledgeParseSummary parseSummary(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, KnowledgeParseSummary.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("invalid knowledge_document.parse_summary", exception);
        }
    }

    /**
     * 解析解析引擎。
     *
     * @param value 数据库值
     * @return 引擎；空值返回 {@code null}
     */
    private static ParseEngine toParseEngine(String value) {
        return value == null ? null : ParseEngine.valueOf(value);
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

    /**
     * 将 BINARY(16) 转为 UUID。
     *
     * @param value 字节
     * @return UUID；null 原样返回
     */
    private static UUID toUuid(byte[] value) {
        return value == null ? null : BinaryUuids.fromBytes(value);
    }

    /**
     * 将 UUID 转为 BINARY(16)。
     *
     * @param value UUID
     * @return 字节；null 原样返回
     */
    private static byte[] toBytes(UUID value) {
        return value == null ? null : BinaryUuids.toBytes(value);
    }
}
