package com.riccon.worker.index.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import com.riccon.worker.index.domain.KnowledgeChunkRecord;
import com.riccon.worker.index.domain.KnowledgeDocumentRecord;
import com.riccon.worker.parse.domain.ParsedChunk;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 知识库文档与分块仓库。
 *
 * <p>MySQL 是权威：分块先落库，再由索引服务写入 OpenSearch；重建时删除旧分块并重新生成，
 * OpenSearch 文档按 document_id 清理，避免重复。所有查询带 {@code tenant_id}。
 */
@Component
public class KnowledgeRepository {

    /** error_code/error_message 列长度限制。 */
    private static final int MAX_ERROR_LENGTH = 500;

    /** 租户数据访问层。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建知识库仓库。
     *
     * @param tenantJdbc 租户数据访问层
     */
    public KnowledgeRepository(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 读取文档快照。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 文档快照；不存在或属于其他租户时为空
     */
    public Optional<KnowledgeDocumentRecord> findDocument(TenantId tenantId, UUID documentId) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT title, file_id, status
                  FROM knowledge_document
                 WHERE tenant_id = :tenantId
                   AND id = :documentId
                """,
                Map.of("documentId", UuidBinary.toBytes(documentId)),
                (rs, rowNum) -> new KnowledgeDocumentRecord(
                        documentId,
                        rs.getString("title"),
                        rs.getBytes("file_id") == null ? null : UuidBinary.fromBytes(rs.getBytes("file_id")),
                        KnowledgeDocumentStatus.valueOf(rs.getString("status"))));
    }

    /**
     * 读取文档现有分块，供无源文件的演示语料重建。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 按 ordinal 排序的分块
     */
    public List<KnowledgeChunkRecord> findChunks(TenantId tenantId, UUID documentId) {
        return tenantJdbc.query(
                tenantId,
                """
                SELECT id, content, locator_type, page_no, sheet_name, cell_range, paragraph_no, token_count
                  FROM knowledge_chunk
                 WHERE tenant_id = :tenantId
                   AND document_id = :documentId
                 ORDER BY ordinal
                """,
                Map.of("documentId", UuidBinary.toBytes(documentId)),
                (rs, rowNum) -> new KnowledgeChunkRecord(
                        UuidBinary.fromBytes(rs.getBytes("id")),
                        rs.getString("content"),
                        LocatorType.valueOf(rs.getString("locator_type")),
                        (Integer) rs.getObject("page_no"),
                        rs.getString("sheet_name"),
                        rs.getString("cell_range"),
                        (Integer) rs.getObject("paragraph_no"),
                        rs.getInt("token_count")));
    }

    /**
     * 条件推进为 INDEXING。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 是否推进成功；已删除文档返回 false
     */
    public boolean markIndexing(TenantId tenantId, UUID documentId) {
        int updated = tenantJdbc.update(tenantId, """
                UPDATE knowledge_document
                   SET status = 'INDEXING',
                       error_code = NULL,
                       error_message = NULL
                 WHERE tenant_id = :tenantId
                   AND id = :documentId
                   AND status IN ('PENDING', 'INDEXING', 'INDEXED', 'FAILED')
                """, Map.of("documentId", UuidBinary.toBytes(documentId)));
        return updated > 0;
    }

    /**
     * 标记索引成功。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     */
    public void markIndexed(TenantId tenantId, UUID documentId) {
        tenantJdbc.update(tenantId, """
                UPDATE knowledge_document
                   SET status = 'INDEXED',
                       indexed_at = NOW(6),
                       error_code = NULL,
                       error_message = NULL
                 WHERE tenant_id = :tenantId
                   AND id = :documentId
                   AND status = 'INDEXING'
                """, Map.of("documentId", UuidBinary.toBytes(documentId)));
    }

    /**
     * 标记索引失败并保留稳定错误码。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param errorCode 稳定错误码
     * @param errorMessage 脱敏摘要
     */
    public void markFailed(TenantId tenantId, UUID documentId, String errorCode, String errorMessage) {
        Map<String, Object> params = new HashMap<>();
        params.put("documentId", UuidBinary.toBytes(documentId));
        params.put("errorCode", errorCode);
        params.put("errorMessage", truncate(errorMessage));
        tenantJdbc.update(tenantId, """
                UPDATE knowledge_document
                   SET status = 'FAILED',
                       error_code = :errorCode,
                       error_message = :errorMessage
                 WHERE tenant_id = :tenantId
                   AND id = :documentId
                   AND status IN ('PENDING', 'INDEXING', 'INDEXED', 'FAILED')
                """, params);
    }

    /**
     * 用新切分结果替换文档分块，并返回带 ID 的权威记录。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param chunks 新分块
     * @return 落库后的分块记录
     */
    public List<KnowledgeChunkRecord> replaceChunks(TenantId tenantId, UUID documentId, List<ParsedChunk> chunks) {
        tenantJdbc.update(
                tenantId,
                "DELETE FROM knowledge_chunk WHERE tenant_id = :tenantId AND document_id = :documentId",
                Map.of("documentId", UuidBinary.toBytes(documentId)));
        List<KnowledgeChunkRecord> records = new ArrayList<>(chunks.size());
        int ordinal = 1;
        for (ParsedChunk chunk : chunks) {
            UUID chunkId = UuidBinary.newV7();
            Map<String, Object> params = new HashMap<>();
            params.put("id", UuidBinary.toBytes(chunkId));
            params.put("documentId", UuidBinary.toBytes(documentId));
            params.put("ordinal", ordinal++);
            params.put("locatorType", chunk.locatorType().name());
            params.put("pageNo", chunk.pageNo());
            params.put("sheetName", chunk.sheetName());
            params.put("cellRange", chunk.cellRange());
            params.put("paragraphNo", chunk.paragraphNo());
            params.put("content", chunk.content());
            params.put("tokenCount", chunk.tokenCount());
            tenantJdbc.insert(tenantId, """
                    INSERT INTO knowledge_chunk
                        (tenant_id, id, document_id, ordinal, locator_type,
                         page_no, sheet_name, cell_range, paragraph_no, content, token_count)
                    VALUES
                        (:tenantId, :id, :documentId, :ordinal, :locatorType,
                         :pageNo, :sheetName, :cellRange, :paragraphNo, :content, :tokenCount)
                    """, params);
            records.add(new KnowledgeChunkRecord(
                    chunkId,
                    chunk.content(),
                    chunk.locatorType(),
                    chunk.pageNo(),
                    chunk.sheetName(),
                    chunk.cellRange(),
                    chunk.paragraphNo(),
                    chunk.tokenCount()));
        }
        return List.copyOf(records);
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
