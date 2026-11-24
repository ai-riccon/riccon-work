package com.riccon.worker.index.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import com.riccon.platform.domain.knowledge.ParseEngine;
import com.riccon.worker.index.domain.KnowledgeChunkRecord;
import com.riccon.worker.index.domain.KnowledgeDocumentRecord;
import com.riccon.worker.index.domain.ParseSummaryJson;
import com.riccon.worker.parse.domain.BoundingBox;
import com.riccon.worker.parse.domain.ParseSummary;
import com.riccon.worker.parse.domain.ParsedChunk;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 知识库文档与分块仓库。
 *
 * <p>MySQL 是权威：分块先落库，再由索引服务写入 OpenSearch；重建时删除旧分块并重新生成，
 * OpenSearch 文档按 document_id 清理，避免重复。所有查询带 {@code tenant_id}。
 *
 * <p>M05 起同时负责两类元数据：分块的 OCR 区域/置信度（bbox、ocr_confidence）与文档的
 * parse_engine/parse_warnings/parse_summary。摘要按 OpenAPI 契约视图
 * {@link ParseSummaryJson} 序列化，绝不直接序列化内部 {@link ParseSummary}。
 */
@Component
public class KnowledgeRepository {

    /** error_code/error_message 列长度限制。 */
    private static final int MAX_ERROR_LENGTH = 500;

    /** 租户数据访问层。 */
    private final TenantJdbc tenantJdbc;

    /** JSON 序列化器，用于 parse_warnings/parse_summary 列。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建知识库仓库。
     *
     * @param tenantJdbc 租户数据访问层
     * @param objectMapper JSON 序列化器
     */
    public KnowledgeRepository(TenantJdbc tenantJdbc, ObjectMapper objectMapper) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
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
                SELECT title, file_id, created_at, status, visibility, department_id, acl_version,
                       source_type, connector_id, parse_engine
                  FROM knowledge_document
                 WHERE tenant_id = :tenantId
                   AND id = :documentId
                """,
                Map.of("documentId", UuidBinary.toBytes(documentId)),
                (rs, rowNum) -> new KnowledgeDocumentRecord(
                        documentId,
                        rs.getString("title"),
                        rs.getBytes("file_id") == null ? null : UuidBinary.fromBytes(rs.getBytes("file_id")),
                        toInstant(rs.getObject("created_at", LocalDateTime.class)),
                        KnowledgeDocumentStatus.valueOf(rs.getString("status")),
                        KnowledgeVisibility.valueOf(rs.getString("visibility")),
                        rs.getBytes("department_id") == null
                                ? null
                                : UuidBinary.fromBytes(rs.getBytes("department_id")),
                        rs.getInt("acl_version"),
                        rs.getString("source_type"),
                        rs.getBytes("connector_id") == null ? null : UuidBinary.fromBytes(rs.getBytes("connector_id")),
                        rs.getString("parse_engine") == null
                                ? null
                                : ParseEngine.valueOf(rs.getString("parse_engine"))));
    }

    /**
     * 读取文档现有分块，供 ACL_ONLY 刷新与无源文件的演示语料重建。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 按 ordinal 排序的分块
     */
    public List<KnowledgeChunkRecord> findChunks(TenantId tenantId, UUID documentId) {
        return tenantJdbc.query(
                tenantId,
                """
                SELECT id, content, locator_type, page_no, sheet_name, cell_range, paragraph_no,
                       token_count, bbox_x, bbox_y, bbox_w, bbox_h, ocr_confidence
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
                        rs.getInt("token_count"),
                        boundedBox(
                                rs.getObject("bbox_x"),
                                rs.getObject("bbox_y"),
                                rs.getObject("bbox_w"),
                                rs.getObject("bbox_h")),
                        rs.getBigDecimal("ocr_confidence")));
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
     * <p>bbox/ocr_confidence 只在 OCR/扫描件分块上非空；文本型 PDF/DOCX/Excel 写 NULL。
     * 先删后插在同一租户作用域内完成，重复投递不会产生重复分块。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param chunks 新分块
     * @return 落库后的分块记录
     */
    public List<KnowledgeChunkRecord> replaceChunks(TenantId tenantId, UUID documentId, List<ParsedChunk> chunks) {
        deleteChunks(tenantId, documentId);
        List<KnowledgeChunkRecord> records = new ArrayList<>(chunks.size());
        int ordinal = 1;
        for (ParsedChunk chunk : chunks) {
            UUID chunkId = UuidBinary.newV7();
            BoundingBox bbox = chunk.bbox();
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
            params.put("bboxX", bbox == null ? null : bbox.x());
            params.put("bboxY", bbox == null ? null : bbox.y());
            params.put("bboxW", bbox == null ? null : bbox.width());
            params.put("bboxH", bbox == null ? null : bbox.height());
            params.put("ocrConfidence", chunk.ocrConfidence());
            tenantJdbc.insert(tenantId, """
                    INSERT INTO knowledge_chunk
                        (tenant_id, id, document_id, ordinal, locator_type,
                         page_no, sheet_name, cell_range, paragraph_no, content, token_count,
                         bbox_x, bbox_y, bbox_w, bbox_h, ocr_confidence)
                    VALUES
                        (:tenantId, :id, :documentId, :ordinal, :locatorType,
                         :pageNo, :sheetName, :cellRange, :paragraphNo, :content, :tokenCount,
                         :bboxX, :bboxY, :bboxW, :bboxH, :ocrConfidence)
                    """, params);
            records.add(new KnowledgeChunkRecord(
                    chunkId,
                    chunk.content(),
                    chunk.locatorType(),
                    chunk.pageNo(),
                    chunk.sheetName(),
                    chunk.cellRange(),
                    chunk.paragraphNo(),
                    chunk.tokenCount(),
                    bbox,
                    chunk.ocrConfidence()));
        }
        return List.copyOf(records);
    }

    /**
     * 删除文档的分块；{@code DELETE} 索引模式与 FULL 重建前的清理共用。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @return 删除行数
     */
    public int deleteChunks(TenantId tenantId, UUID documentId) {
        return tenantJdbc.update(
                tenantId,
                "DELETE FROM knowledge_chunk WHERE tenant_id = :tenantId AND document_id = :documentId",
                Map.of("documentId", UuidBinary.toBytes(documentId)));
    }

    /**
     * 写入文档解析元数据（parse_engine/parse_warnings/parse_summary）。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param engine 解析引擎
     * @param warnings 白名单警告
     * @param summary 结构摘要；不适用时为空
     * @return 更新行数
     */
    public int updateParseMetadata(
            TenantId tenantId, UUID documentId, ParseEngine engine, List<String> warnings, ParseSummary summary) {
        Map<String, Object> params = parseMetadataParams(engine, warnings, summary);
        params.put("documentId", UuidBinary.toBytes(documentId));
        return tenantJdbc.update(tenantId, """
                UPDATE knowledge_document
                   SET parse_engine = :parseEngine,
                       parse_warnings = :parseWarnings,
                       parse_summary = :parseSummary
                 WHERE tenant_id = :tenantId
                   AND id = :documentId
                """, params);
    }

    /**
     * 文件解析成功后，把解析元数据写到引用该文件的未完成文档。
     *
     * <p>只更新 {@code PENDING/INDEXING/FAILED}：已 INDEXED 文档的元数据必须由索引事件在
     * 同一次解析结果中原子刷新，避免“元数据来自新文件、分块仍来自旧文件”的不一致。
     *
     * @param tenantId 当前租户
     * @param fileId 文件 ID
     * @param engine 解析引擎
     * @param warnings 白名单警告
     * @param summary 结构摘要
     * @return 更新行数
     */
    public int updateParseMetadataForFile(
            TenantId tenantId, UUID fileId, ParseEngine engine, List<String> warnings, ParseSummary summary) {
        Map<String, Object> params = parseMetadataParams(engine, warnings, summary);
        params.put("fileId", UuidBinary.toBytes(fileId));
        return tenantJdbc.update(tenantId, """
                UPDATE knowledge_document
                   SET parse_engine = :parseEngine,
                       parse_warnings = :parseWarnings,
                       parse_summary = :parseSummary
                 WHERE tenant_id = :tenantId
                   AND file_id = :fileId
                   AND status IN ('PENDING', 'INDEXING', 'FAILED')
                """, params);
    }

    /**
     * 构造 parse_* 三列的命名参数；警告与摘要按契约视图序列化。
     *
     * @param engine 解析引擎
     * @param warnings 白名单警告
     * @param summary 结构摘要
     * @return 命名参数
     */
    private Map<String, Object> parseMetadataParams(ParseEngine engine, List<String> warnings, ParseSummary summary) {
        Map<String, Object> params = new HashMap<>();
        params.put("parseEngine", engine == null ? null : engine.name());
        params.put("parseWarnings", objectMapper.writeValueAsString(warnings == null ? List.of() : warnings));
        ParseSummaryJson view = ParseSummaryJson.from(summary);
        params.put("parseSummary", view == null ? null : objectMapper.writeValueAsString(view));
        return params;
    }

    /**
     * 从四列 bbox 值还原区域；任一列缺失视为非 OCR 分块。
     *
     * @param x 左上角 X
     * @param y 左上角 Y
     * @param width 宽度
     * @param height 高度
     * @return 区域；不完整时为空
     */
    private static BoundingBox boundedBox(Object x, Object y, Object width, Object height) {
        if (!(x instanceof Integer xValue)
                || !(y instanceof Integer yValue)
                || !(width instanceof Integer widthValue)
                || !(height instanceof Integer heightValue)) {
            return null;
        }
        return new BoundingBox(xValue, yValue, widthValue, heightValue);
    }

    /**
     * 把数据库 UTC 时间列转换为 {@link Instant}；{@code DATETIME(6)} 不带时区，按 UTC 解释。
     *
     * @param value 数据库时间
     * @return UTC 时刻；空值返回空
     */
    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
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
