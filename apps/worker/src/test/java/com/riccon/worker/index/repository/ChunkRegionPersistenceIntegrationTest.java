package com.riccon.worker.index.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.index.domain.KnowledgeChunkRecord;
import com.riccon.worker.parse.domain.BoundingBox;
import com.riccon.worker.parse.domain.ParsedChunk;
import com.riccon.worker.parse.repository.FileRepository;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.support.TestDocuments;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 分块 OCR 区域/置信度落库（REV-TASK-0168-001 F-004）。
 *
 * <p>覆盖 {@code knowledge_chunk}（索引链路）与 {@code file_chunk}（文件解析链路）两条写入：
 * V12 已建 bbox_x/y/w/h 与 ocr_confidence，本任务必须让 B5 透传到 {@link ParsedChunk} 的值真正
 * 持久化，供引用详情按页/区域定位。文本型分块保持 NULL。
 */
class ChunkRegionPersistenceIntegrationTest extends WorkerIntegrationTestBase {

    /** 知识库仓库。 */
    @Autowired
    private KnowledgeRepository knowledgeRepository;

    /** 文件解析仓库。 */
    @Autowired
    private FileRepository fileRepository;

    /** 文件分块区域仓库。 */
    @Autowired
    private FileChunkRegionRepository fileChunkRegionRepository;

    /** knowledge_chunk 写入 bbox/ocr_confidence，并可读回。 */
    @Test
    void replaceChunks_writesBboxAndOcrConfidence() {
        String tenant = uniqueTenant("tenant-a");
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        UUID documentId = data.createKnowledgeDocument(tenant, "ocr-doc", null, userId);
        ParsedChunk ocrChunk = new ParsedChunk(
                "ocr line",
                LocatorType.PAGE,
                1,
                null,
                null,
                null,
                2,
                new BoundingBox(11, 22, 33, 44),
                new BigDecimal("0.780"));
        ParsedChunk plainChunk = new ParsedChunk("plain paragraph", LocatorType.PARAGRAPH, null, null, null, 1, 4);

        List<KnowledgeChunkRecord> stored;
        List<KnowledgeChunkRecord> loaded;
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(tenant))) {
            stored = knowledgeRepository.replaceChunks(TenantId.of(tenant), documentId, List.of(ocrChunk, plainChunk));
            loaded = knowledgeRepository.findChunks(TenantId.of(tenant), documentId);
        }

        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).bbox()).isEqualTo(new BoundingBox(11, 22, 33, 44));
        assertThat(stored.get(0).ocrConfidence()).isEqualByComparingTo("0.780");
        assertThat(stored.get(1).bbox()).isNull();
        assertThat(stored.get(1).ocrConfidence()).isNull();
        assertThat(loaded.get(0).bbox()).isEqualTo(new BoundingBox(11, 22, 33, 44));
        assertThat(loaded.get(0).ocrConfidence()).isEqualByComparingTo("0.780");

        Map<String, Object> row = jdbcClient
                .sql("""
                        SELECT bbox_x, bbox_y, bbox_w, bbox_h, ocr_confidence
                          FROM knowledge_chunk
                         WHERE tenant_id = :tenantId AND document_id = :documentId AND ordinal = 1
                        """)
                .param("tenantId", tenant)
                .param("documentId", UuidBinary.toBytes(documentId))
                .query((rs, rowNum) -> Map.of(
                        "bbox_x",
                        rs.getObject("bbox_x"),
                        "bbox_y",
                        rs.getObject("bbox_y"),
                        "bbox_w",
                        rs.getObject("bbox_w"),
                        "bbox_h",
                        rs.getObject("bbox_h"),
                        "ocr_confidence",
                        rs.getBigDecimal("ocr_confidence")))
                .single();
        assertThat(row).containsEntry("bbox_x", 11).containsEntry("bbox_y", 22);
        assertThat(row).containsEntry("bbox_w", 33).containsEntry("bbox_h", 44);
        assertThat((BigDecimal) row.get("ocr_confidence")).isEqualByComparingTo("0.780");
    }

    /** file_chunk 的区域在解析落库后按 ordinal 补齐；文本分块不产生更新。 */
    @Test
    void fileChunkRegions_writesBboxByOrdinal() {
        String tenant = uniqueTenant("tenant-a");
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        UUID workspaceId = data.createWorkspace(tenant, userId);
        UUID fileId = data.createFile(tenant, workspaceId, userId, FileFormat.PDF, TestDocuments.pdf(1));
        UUID parseId = data.createParse(tenant, fileId, 1);
        ParsedChunk ocrChunk = new ParsedChunk(
                "ocr line",
                LocatorType.PAGE,
                1,
                null,
                null,
                null,
                2,
                new BoundingBox(5, 6, 7, 8),
                new BigDecimal("0.650"));
        ParsedChunk plainChunk = new ParsedChunk("plain", LocatorType.PAGE, 2, null, null, null, 1);

        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(tenant))) {
            fileRepository.replaceChunks(TenantId.of(tenant), fileId, parseId, List.of(ocrChunk, plainChunk));
            assertThat(fileChunkRegionRepository.updateRegions(
                            TenantId.of(tenant), parseId, List.of(ocrChunk, plainChunk)))
                    .isEqualTo(1);
        }

        Map<String, Object> row = jdbcClient
                .sql("""
                        SELECT bbox_x, bbox_y, bbox_w, bbox_h, ocr_confidence
                          FROM file_chunk
                         WHERE tenant_id = :tenantId AND parse_id = :parseId AND ordinal = 1
                        """)
                .param("tenantId", tenant)
                .param("parseId", UuidBinary.toBytes(parseId))
                .query((rs, rowNum) -> Map.of(
                        "bbox_x",
                        rs.getObject("bbox_x"),
                        "bbox_y",
                        rs.getObject("bbox_y"),
                        "bbox_w",
                        rs.getObject("bbox_w"),
                        "bbox_h",
                        rs.getObject("bbox_h"),
                        "ocr_confidence",
                        rs.getBigDecimal("ocr_confidence")))
                .single();
        assertThat(row).containsEntry("bbox_x", 5).containsEntry("bbox_y", 6);
        assertThat(row).containsEntry("bbox_w", 7).containsEntry("bbox_h", 8);
        assertThat((BigDecimal) row.get("ocr_confidence")).isEqualByComparingTo("0.650");
        Map<String, Object> plainRow = jdbcClient
                .sql("""
                        SELECT bbox_x, ocr_confidence FROM file_chunk
                         WHERE tenant_id = :tenantId AND parse_id = :parseId AND ordinal = 2
                        """)
                .param("tenantId", tenant)
                .param("parseId", UuidBinary.toBytes(parseId))
                .query((rs, rowNum) -> {
                    Map<String, Object> values = new java.util.HashMap<>();
                    values.put("bbox_x", rs.getObject("bbox_x"));
                    values.put("ocr_confidence", rs.getBigDecimal("ocr_confidence"));
                    return values;
                })
                .single();
        assertThat(plainRow.get("bbox_x")).isNull();
        assertThat(plainRow.get("ocr_confidence")).isNull();
    }
}
