package com.riccon.api.file.persistence;

import com.riccon.api.file.domain.FileParseRecord;
import com.riccon.api.file.domain.FileRecord;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.platform.domain.file.FileStatus;
import com.riccon.platform.domain.file.ParseStatus;
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
 * 文件、解析尝试与会话归属 SQL。
 *
 * <p>所有查询经 {@link TenantJdbc} 强制 {@code tenant_id}。M03 起文件可见性由所属工作空间的
 * {@code WorkspaceService.requireAccessible} 决定：本仓储只按 {@code (tenant_id, id)} 与工作空间过滤，
 * 不再复制 {@code kind = 'TEAM'} 或 owner-only 的可见性条件。读取时关联最近一次 {@code file_parse}，
 * 列表不使用 N+1 查询。
 */
@Component
public class FileJdbc {

    /** 文件列。 */
    private static final String FILE_COLUMNS = """
            f.id, f.workspace_id, f.conversation_id, f.uploaded_by, f.original_name, f.format, f.mime_type,
            f.size_bytes, f.sha256, f.storage_key, f.status, f.created_at, f.deleted_at
            """;

    /** 解析尝试列；左连接时可能全部为空。 */
    private static final String PARSE_COLUMNS = """
            fp.id AS parse_id, fp.attempt AS parse_attempt, fp.status AS parse_status,
            fp.parser_version AS parse_parser_version, fp.page_count AS parse_page_count,
            fp.sheet_count AS parse_sheet_count, fp.chunk_count AS parse_chunk_count,
            fp.error_code AS parse_error_code, fp.error_message AS parse_error_message,
            fp.started_at AS parse_started_at, fp.finished_at AS parse_finished_at
            """;

    /** 最近一次解析尝试的左连接。 */
    private static final String LATEST_PARSE_JOIN = """
             LEFT JOIN file_parse fp
                    ON fp.tenant_id = f.tenant_id
                   AND fp.file_id = f.id
                   AND fp.attempt = (
                       SELECT MAX(fp2.attempt)
                         FROM file_parse fp2
                        WHERE fp2.tenant_id = f.tenant_id
                          AND fp2.file_id = f.id
                   )
            """;

    /** 插入文件元数据。 */
    private static final String INSERT_FILE = """
            INSERT INTO file (
                tenant_id, id, workspace_id, conversation_id, uploaded_by, original_name,
                format, mime_type, size_bytes, sha256, storage_key, status, created_at, updated_at
            ) VALUES (
                :tenantId, :id, :workspaceId, :conversationId, :uploadedBy, :originalName,
                :format, :mimeType, :sizeBytes, :sha256, :storageKey, :status, :createdAt, :createdAt
            )
            """;

    /** 插入解析尝试。 */
    private static final String INSERT_PARSE = """
            INSERT INTO file_parse (
                tenant_id, id, file_id, attempt, status, parser_version, page_count, sheet_count,
                chunk_count, error_code, error_message, started_at, finished_at, created_at, updated_at
            ) VALUES (
                :tenantId, :id, :fileId, :attempt, :status, :parserVersion, :pageCount, :sheetCount,
                :chunkCount, :errorCode, :errorMessage, :startedAt, :finishedAt, :createdAt, :createdAt
            )
            """;

    /** 读取最近一次解析尝试。 */
    private static final String LATEST_PARSE = "SELECT " + PARSE_COLUMNS + """
                      FROM file_parse fp
                     WHERE fp.tenant_id = :tenantId
                       AND fp.file_id = :fileId
                     ORDER BY fp.attempt DESC
                     LIMIT 1
                    """;

    /** 软删除文件。 */
    private static final String SOFT_DELETE = """
            UPDATE file
               SET status = 'DELETED',
                   deleted_at = :deletedAt,
                   updated_at = :deletedAt
             WHERE tenant_id = :tenantId
               AND id = :fileId
               AND status = 'AVAILABLE'
               AND deleted_at IS NULL
            """;

    /** 锁定会话，串行化单会话文件计数。 */
    private static final String LOCK_CONVERSATION = """
            SELECT id
              FROM conversation
             WHERE tenant_id = :tenantId
               AND workspace_id = :workspaceId
               AND id = :conversationId
               AND deleted_at IS NULL
             FOR UPDATE
            """;

    /** 统计会话内可用文件。 */
    private static final String COUNT_CONVERSATION_FILES = """
            SELECT COUNT(*)
              FROM file
             WHERE tenant_id = :tenantId
               AND conversation_id = :conversationId
               AND status = 'AVAILABLE'
               AND deleted_at IS NULL
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建文件 SQL 组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public FileJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 插入文件元数据。
     *
     * @param tenantId 当前租户
     * @param file 文件快照
     * @param uploadedBy 上传人
     */
    public void insertFile(TenantId tenantId, FileRecord file, UUID uploadedBy) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(file.id()));
        params.put("workspaceId", BinaryUuids.toBytes(file.workspaceId()));
        params.put("conversationId", file.conversationId() == null ? null : BinaryUuids.toBytes(file.conversationId()));
        params.put("uploadedBy", BinaryUuids.toBytes(uploadedBy));
        params.put("originalName", file.originalName());
        params.put("format", file.format().name());
        params.put("mimeType", file.mimeType());
        params.put("sizeBytes", file.sizeBytes());
        params.put("sha256", file.sha256());
        params.put("storageKey", file.storageKey());
        params.put("status", file.status().name());
        params.put("createdAt", SqlTimestamps.toDatabase(file.createdAt()));
        tenantJdbc.insert(tenantId, INSERT_FILE, params);
    }

    /**
     * 插入解析尝试。
     *
     * @param tenantId 当前租户
     * @param fileId 文件 ID
     * @param parse 解析尝试
     * @param createdAt 创建时间
     */
    public void insertParse(TenantId tenantId, UUID fileId, FileParseRecord parse, Instant createdAt) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(parse.id()));
        params.put("fileId", BinaryUuids.toBytes(fileId));
        params.put("attempt", parse.attempt());
        params.put("status", parse.status().name());
        params.put("parserVersion", parse.parserVersion());
        params.put("pageCount", parse.pageCount());
        params.put("sheetCount", parse.sheetCount());
        params.put("chunkCount", parse.chunkCount());
        params.put("errorCode", parse.errorCode());
        params.put("errorMessage", parse.errorMessage());
        params.put("startedAt", SqlTimestamps.toDatabase(parse.startedAt()));
        params.put("finishedAt", SqlTimestamps.toDatabase(parse.finishedAt()));
        params.put("createdAt", SqlTimestamps.toDatabase(createdAt));
        tenantJdbc.insert(tenantId, INSERT_PARSE, params);
    }

    /**
     * 按租户读取可用文件。
     *
     * <p>不做可见性判断；调用方必须先按文件的 {@code workspace_id} 通过
     * {@code WorkspaceService.requireAccessible}，再使用本方法的结果。
     *
     * @param tenantId 当前租户
     * @param fileId 文件 ID
     * @return 文件快照；不存在、已删除或属于其他租户时为空
     */
    public Optional<FileRecord> findById(TenantId tenantId, UUID fileId) {
        return tenantJdbc.queryOne(
                tenantId, byIdSql(false), Map.of("fileId", BinaryUuids.toBytes(fileId)), FileJdbc::mapRow);
    }

    /**
     * 锁定并读取可用文件。
     *
     * @param tenantId 当前租户
     * @param fileId 文件 ID
     * @return 文件快照；不可见时为空
     */
    public Optional<FileRecord> findByIdForUpdate(TenantId tenantId, UUID fileId) {
        return tenantJdbc.queryOne(
                tenantId, byIdSql(true), Map.of("fileId", BinaryUuids.toBytes(fileId)), FileJdbc::mapRow);
    }

    /**
     * 统计工作空间内可用文件。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param conversationId 可选会话过滤
     * @return 总数
     */
    public long countByWorkspace(TenantId tenantId, UUID workspaceId, UUID conversationId) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                  FROM file f
                 WHERE f.tenant_id = :tenantId
                   AND f.workspace_id = :workspaceId
                   AND f.status = 'AVAILABLE'
                   AND f.deleted_at IS NULL
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workspaceId", BinaryUuids.toBytes(workspaceId));
        if (conversationId != null) {
            sql.append(" AND f.conversation_id = :conversationId");
            params.put("conversationId", BinaryUuids.toBytes(conversationId));
        }
        return tenantJdbc.count(tenantId, sql.toString(), params);
    }

    /**
     * 分页查询工作空间可用文件，按创建时间倒序。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param conversationId 可选会话过滤
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 文件列表
     */
    public List<FileRecord> listByWorkspace(
            TenantId tenantId, UUID workspaceId, UUID conversationId, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(FILE_COLUMNS)
                .append(", ")
                .append(PARSE_COLUMNS)
                .append("""
                          FROM file f
                        """)
                .append(LATEST_PARSE_JOIN)
                .append("""
                         WHERE f.tenant_id = :tenantId
                           AND f.workspace_id = :workspaceId
                           AND f.status = 'AVAILABLE'
                           AND f.deleted_at IS NULL
                        """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workspaceId", BinaryUuids.toBytes(workspaceId));
        if (conversationId != null) {
            sql.append(" AND f.conversation_id = :conversationId");
            params.put("conversationId", BinaryUuids.toBytes(conversationId));
        }
        sql.append(" ORDER BY f.created_at DESC, f.id DESC LIMIT :limit OFFSET :offset");
        params.put("limit", limit);
        params.put("offset", offset);
        return tenantJdbc.query(tenantId, sql.toString(), params, FileJdbc::mapRow);
    }

    /**
     * 查询文件最近一次解析尝试。
     *
     * @param tenantId 当前租户
     * @param fileId 文件 ID
     * @return 最近一次解析尝试
     */
    public Optional<FileParseRecord> findLatestParse(TenantId tenantId, UUID fileId) {
        return tenantJdbc.queryOne(
                tenantId, LATEST_PARSE, Map.of("fileId", BinaryUuids.toBytes(fileId)), FileJdbc::mapParseRow);
    }

    /**
     * 软删除文件。
     *
     * @param tenantId 当前租户
     * @param fileId 文件 ID
     * @param deletedAt 删除时间
     * @return 受影响行数
     */
    public int softDelete(TenantId tenantId, UUID fileId, Instant deletedAt) {
        return tenantJdbc.update(
                tenantId,
                SOFT_DELETE,
                Map.of("fileId", BinaryUuids.toBytes(fileId), "deletedAt", SqlTimestamps.toDatabase(deletedAt)));
    }

    /**
     * 锁定工作空间内会话，保证单会话文件计数与插入串行。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param conversationId 会话 ID
     * @return 会话 ID；不存在时为空
     */
    public Optional<UUID> lockConversation(TenantId tenantId, UUID workspaceId, UUID conversationId) {
        return tenantJdbc.queryOne(
                tenantId,
                LOCK_CONVERSATION,
                Map.of(
                        "workspaceId", BinaryUuids.toBytes(workspaceId),
                        "conversationId", BinaryUuids.toBytes(conversationId)),
                (rs, rowNum) -> BinaryUuids.fromBytes(rs.getBytes("id")));
    }

    /**
     * 统计会话内可用文件数量。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @return 文件数
     */
    public long countConversationFiles(TenantId tenantId, UUID conversationId) {
        return tenantJdbc.count(
                tenantId, COUNT_CONVERSATION_FILES, Map.of("conversationId", BinaryUuids.toBytes(conversationId)));
    }

    /**
     * 构造按 ID 查询文件 SQL。
     *
     * @param forUpdate 是否加行锁
     * @return SQL
     */
    private static String byIdSql(boolean forUpdate) {
        return "SELECT " + FILE_COLUMNS + ", " + PARSE_COLUMNS + """
                  FROM file f
                """ + LATEST_PARSE_JOIN + """
                 WHERE f.tenant_id = :tenantId
                   AND f.id = :fileId
                   AND f.status = 'AVAILABLE'
                   AND f.deleted_at IS NULL
                """
                + (forUpdate ? " FOR UPDATE" : "");
    }

    /**
     * 映射文件与最近解析行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 文件快照
     * @throws SQLException 列读取失败
     */
    private static FileRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        FileParseRecord parse = rs.getBytes("parse_id") == null ? null : mapParseRow(rs, rowNum);
        return new FileRecord(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                BinaryUuids.fromBytes(rs.getBytes("workspace_id")),
                rs.getBytes("conversation_id") == null ? null : BinaryUuids.fromBytes(rs.getBytes("conversation_id")),
                BinaryUuids.fromBytes(rs.getBytes("uploaded_by")),
                rs.getString("original_name"),
                FileFormat.valueOf(rs.getString("format")),
                rs.getString("mime_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getString("storage_key"),
                FileStatus.valueOf(rs.getString("status")),
                parse,
                toInstant(rs.getObject("created_at", LocalDateTime.class)),
                toInstant(rs.getObject("deleted_at", LocalDateTime.class)));
    }

    /**
     * 映射解析尝试行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 解析尝试
     * @throws SQLException 列读取失败
     */
    private static FileParseRecord mapParseRow(ResultSet rs, int rowNum) throws SQLException {
        return new FileParseRecord(
                BinaryUuids.fromBytes(rs.getBytes("parse_id")),
                rs.getInt("parse_attempt"),
                ParseStatus.valueOf(rs.getString("parse_status")),
                rs.getString("parse_parser_version"),
                toInteger(rs.getObject("parse_page_count")),
                toInteger(rs.getObject("parse_sheet_count")),
                toInteger(rs.getObject("parse_chunk_count")),
                rs.getString("parse_error_code"),
                rs.getString("parse_error_message"),
                toInstant(rs.getObject("parse_started_at", LocalDateTime.class)),
                toInstant(rs.getObject("parse_finished_at", LocalDateTime.class)));
    }

    /**
     * 将 JDBC 数值列转为 Integer。
     *
     * @param value 列值
     * @return Integer 或 {@code null}
     */
    private static Integer toInteger(Object value) {
        return value == null ? null : ((Number) value).intValue();
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
