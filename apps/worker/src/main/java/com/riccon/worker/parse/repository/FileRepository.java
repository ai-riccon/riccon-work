package com.riccon.worker.parse.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.platform.domain.file.ParseStatus;
import com.riccon.worker.parse.domain.FileParseRecord;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParsedChunk;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 文件与解析尝试仓库。
 *
 * <p>所有 SQL 带 {@code tenant_id}，由 {@link TenantJdbc} 强制；解析状态只允许
 * PENDING/RUNNING/FAILED → SUCCEEDED 或 FAILED 的向前迁移，重复投递不会覆盖成功结果。
 */
@Component
public class FileRepository {

    /** error_code/error_message 列长度限制。 */
    private static final int MAX_ERROR_LENGTH = 500;

    /** 租户数据访问层。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建文件仓库。
     *
     * @param tenantJdbc 租户数据访问层
     */
    public FileRepository(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 读取可用文件元数据。
     *
     * @param tenantId 当前租户
     * @param fileId 文件 ID
     * @return 文件元数据；不存在、已删除或属于其他租户时为空
     */
    public Optional<FileRecord> findFile(TenantId tenantId, UUID fileId) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT original_name, format, mime_type, size_bytes, storage_key
                  FROM file
                 WHERE tenant_id = :tenantId
                   AND id = :fileId
                   AND status = 'AVAILABLE'
                   AND deleted_at IS NULL
                """,
                Map.of("fileId", UuidBinary.toBytes(fileId)),
                (rs, rowNum) -> new FileRecord(
                        fileId,
                        rs.getString("original_name"),
                        FileFormat.valueOf(rs.getString("format")),
                        rs.getString("mime_type"),
                        rs.getLong("size_bytes"),
                        rs.getString("storage_key")));
    }

    /**
     * 读取解析尝试。
     *
     * @param tenantId 当前租户
     * @param parseId 解析尝试 ID
     * @param fileId 文件 ID
     * @return 解析尝试；不存在或属于其他租户时为空
     */
    public Optional<FileParseRecord> findParse(TenantId tenantId, UUID parseId, UUID fileId) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT attempt, status
                  FROM file_parse
                 WHERE tenant_id = :tenantId
                   AND id = :parseId
                   AND file_id = :fileId
                """,
                Map.of(
                        "parseId", UuidBinary.toBytes(parseId),
                        "fileId", UuidBinary.toBytes(fileId)),
                (rs, rowNum) -> new FileParseRecord(
                        parseId, fileId, rs.getInt("attempt"), ParseStatus.valueOf(rs.getString("status"))));
    }

    /**
     * 条件推进解析到 RUNNING。
     *
     * @param tenantId 当前租户
     * @param parseId 解析尝试 ID
     * @param fileId 文件 ID
     * @param parserVersion 解析器版本
     * @return 是否推进成功；已成功时为 false
     */
    public boolean markRunning(TenantId tenantId, UUID parseId, UUID fileId, String parserVersion) {
        int updated = tenantJdbc.update(
                tenantId,
                """
                UPDATE file_parse
                   SET status = 'RUNNING',
                       parser_version = :parserVersion,
                       started_at = COALESCE(started_at, NOW(6)),
                       finished_at = NULL,
                       error_code = NULL,
                       error_message = NULL
                 WHERE tenant_id = :tenantId
                   AND id = :parseId
                   AND file_id = :fileId
                   AND status IN ('PENDING', 'RUNNING', 'FAILED')
                """,
                Map.of(
                        "parseId", UuidBinary.toBytes(parseId),
                        "fileId", UuidBinary.toBytes(fileId),
                        "parserVersion", parserVersion));
        return updated > 0;
    }

    /**
     * 标记解析成功并写入页数/工作表/分块数。
     *
     * @param tenantId 当前租户
     * @param parseId 解析尝试 ID
     * @param fileId 文件 ID
     * @param pageCount 页数，可为空
     * @param sheetCount 工作表数，可为空
     * @param chunkCount 分块数
     */
    public void markSucceeded(
            TenantId tenantId, UUID parseId, UUID fileId, Integer pageCount, Integer sheetCount, int chunkCount) {
        Map<String, Object> params = new HashMap<>();
        params.put("parseId", UuidBinary.toBytes(parseId));
        params.put("fileId", UuidBinary.toBytes(fileId));
        params.put("pageCount", pageCount);
        params.put("sheetCount", sheetCount);
        params.put("chunkCount", chunkCount);
        tenantJdbc.update(tenantId, """
                UPDATE file_parse
                   SET status = 'SUCCEEDED',
                       page_count = :pageCount,
                       sheet_count = :sheetCount,
                       chunk_count = :chunkCount,
                       finished_at = NOW(6),
                       error_code = NULL,
                       error_message = NULL
                 WHERE tenant_id = :tenantId
                   AND id = :parseId
                   AND file_id = :fileId
                   AND status = 'RUNNING'
                """, params);
    }

    /**
     * 标记解析失败并保留稳定错误码。
     *
     * @param tenantId 当前租户
     * @param parseId 解析尝试 ID
     * @param fileId 文件 ID
     * @param errorCode 稳定错误码
     * @param errorMessage 脱敏摘要
     */
    public void markFailed(TenantId tenantId, UUID parseId, UUID fileId, String errorCode, String errorMessage) {
        Map<String, Object> params = new HashMap<>();
        params.put("parseId", UuidBinary.toBytes(parseId));
        params.put("fileId", UuidBinary.toBytes(fileId));
        params.put("errorCode", errorCode);
        params.put("errorMessage", truncate(errorMessage));
        tenantJdbc.update(tenantId, """
                UPDATE file_parse
                   SET status = 'FAILED',
                       finished_at = NOW(6),
                       error_code = :errorCode,
                       error_message = :errorMessage
                 WHERE tenant_id = :tenantId
                   AND id = :parseId
                   AND file_id = :fileId
                   AND status IN ('PENDING', 'RUNNING', 'FAILED')
                """, params);
    }

    /**
     * 用本次解析结果替换该 parse_id 的分块，保证重复投递不产生重复分块。
     *
     * <p>先删后插均在同一租户作用域内执行；文件分块 ID 使用 UUIDv7。若进程在删除后崩溃，
     * 下一次投递会重新生成完整分块。
     *
     * @param tenantId 当前租户
     * @param fileId 文件 ID
     * @param parseId 解析尝试 ID
     * @param chunks 新分块
     */
    public void replaceChunks(TenantId tenantId, UUID fileId, UUID parseId, List<ParsedChunk> chunks) {
        tenantJdbc.update(
                tenantId,
                "DELETE FROM file_chunk WHERE tenant_id = :tenantId AND parse_id = :parseId",
                Map.of("parseId", UuidBinary.toBytes(parseId)));
        int ordinal = 1;
        for (ParsedChunk chunk : chunks) {
            Map<String, Object> params = new HashMap<>();
            params.put("id", UuidBinary.toBytes(UuidBinary.newV7()));
            params.put("fileId", UuidBinary.toBytes(fileId));
            params.put("parseId", UuidBinary.toBytes(parseId));
            params.put("ordinal", ordinal++);
            params.put("locatorType", chunk.locatorType().name());
            params.put("pageNo", chunk.pageNo());
            params.put("sheetName", chunk.sheetName());
            params.put("cellRange", chunk.cellRange());
            params.put("paragraphNo", chunk.paragraphNo());
            params.put("content", chunk.content());
            params.put("tokenCount", chunk.tokenCount());
            tenantJdbc.insert(tenantId, """
                    INSERT INTO file_chunk
                        (tenant_id, id, file_id, parse_id, ordinal, locator_type,
                         page_no, sheet_name, cell_range, paragraph_no, content, token_count)
                    VALUES
                        (:tenantId, :id, :fileId, :parseId, :ordinal, :locatorType,
                         :pageNo, :sheetName, :cellRange, :paragraphNo, :content, :tokenCount)
                    """, params);
        }
    }

    /**
     * 截断错误摘要，避免超过数据库列长度。
     *
     * @param message 错误摘要
     * @return 最长 500 字符
     */
    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
