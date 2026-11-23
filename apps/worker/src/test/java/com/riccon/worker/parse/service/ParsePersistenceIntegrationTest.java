package com.riccon.worker.parse.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.run.service.OutboxPoller;
import com.riccon.worker.support.TestDataFactory;
import com.riccon.worker.support.TestDocuments;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 文件解析链路写 {@code knowledge_document.parse_*}（REV-TASK-0167-001 F-001）。
 *
 * <p>文件解析先于知识库索引完成时，必须把 parse_engine/parse_warnings/parse_summary 补充到
 * 引用该文件、尚未索引完成的文档；摘要必须是 OpenAPI 契约视图（{@code name/cellRange/empty/
 * indexed/indexedRange}），不能直接序列化 worker 内部记录。
 */
class ParsePersistenceIntegrationTest extends WorkerIntegrationTestBase {

    /** 轮询器。 */
    @Autowired
    private OutboxPoller poller;

    /** pollOnce 跨租户领取事件；每个测试先清空残留 outbox。 */
    @BeforeEach
    void clearStaleOutbox() {
        clearOutbox();
    }

    /** XLSX 解析成功写入 POI 引擎与工作表契约摘要。 */
    @Test
    void fileParse_xlsx_writesParseMetadataAndContractShape() throws Exception {
        Seed seed = seed(FileFormat.XLSX, TestDocuments.xlsx(), "budget.xlsx");

        assertThat(poller.pollOnce()).isEqualTo(1);

        Map<String, Object> document = documentParseRow(seed.tenant(), seed.documentId());
        assertThat(document).containsEntry("parse_engine", "POI");
        assertThat(document.get("parse_warnings")).isNotNull();
        assertThat(document.get("parse_summary")).isNotNull();
        JsonNode warnings = objectMapper.readTree((String) document.get("parse_warnings"));
        assertThat(warnings.isArray()).isTrue();
        JsonNode summary = objectMapper.readTree((String) document.get("parse_summary"));
        assertThat(summary.path("hiddenSheetCount").asInt()).isZero();
        assertThat(summary.path("sheets")).hasSize(2);
        JsonNode first = summary.path("sheets").get(0);
        assertThat(first.path("name").asText()).isEqualTo("Budget");
        assertThat(first.path("cellRange").asText()).isEqualTo("A1:B2");
        assertThat(first.path("empty").asBoolean()).isFalse();
        assertThat(first.path("indexed").asBoolean()).isTrue();
        assertThat(first.path("indexedRange").asText()).isEqualTo("A1:B2");
        assertThat(first.has("sheetName")).isFalse();
        assertThat(first.has("rowCount")).isFalse();
        assertThat(first.has("skippedRange")).isFalse();

        // 文本型 XLSX 分块没有 OCR 区域/置信度。
        List<Map<String, Object>> regions = fileChunkRegions(seed.tenant(), seed.parseId());
        assertThat(regions).isNotEmpty();
        assertThat(regions).allSatisfy(row -> {
            assertThat(row.get("bbox_x")).isNull();
            assertThat(row.get("ocr_confidence")).isNull();
        });
    }

    /** PDF 解析写入 PDFBOX 引擎、空警告数组，非 Excel 不产生摘要。 */
    @Test
    void fileParse_pdf_writesEngineAndEmptyWarnings() throws Exception {
        Seed seed = seed(FileFormat.PDF, TestDocuments.pdf(1), "report.pdf");

        assertThat(poller.pollOnce()).isEqualTo(1);

        Map<String, Object> document = documentParseRow(seed.tenant(), seed.documentId());
        assertThat(document).containsEntry("parse_engine", "PDFBOX");
        JsonNode warnings = objectMapper.readTree((String) document.get("parse_warnings"));
        assertThat(warnings.isArray()).isTrue();
        assertThat(warnings).isEmpty();
        assertThat(document.get("parse_summary")).isNull();
    }

    /**
     * 创建文件、解析尝试、知识库文档与解析事件。
     *
     * @param format 文件格式
     * @param content 文件字节
     * @param name 文件名（可读性）
     * @return 种子
     */
    private Seed seed(FileFormat format, byte[] content, String name) {
        String tenant = uniqueTenant("tenant-a");
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        UUID workspaceId = data.createWorkspace(tenant, userId);
        UUID fileId = data.createFile(tenant, workspaceId, userId, format, content);
        UUID parseId = data.createParse(tenant, fileId, 1);
        UUID documentId = data.createKnowledgeDocument(tenant, name, fileId, userId);
        TestDataFactory.PublishedEvent event = data.publishFileParse(tenant, fileId, parseId, userId, 1);
        assertThat(event.eventId()).isNotNull();
        return new Seed(tenant, fileId, parseId, documentId);
    }

    /**
     * 查询文档解析元数据列。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 列值
     */
    private Map<String, Object> documentParseRow(String tenant, UUID documentId) {
        return jdbcClient
                .sql("""
                        SELECT parse_engine, parse_warnings, parse_summary
                          FROM knowledge_document
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(documentId))
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new java.util.HashMap<>();
                    row.put("parse_engine", rs.getString("parse_engine"));
                    row.put("parse_warnings", rs.getString("parse_warnings"));
                    row.put("parse_summary", rs.getString("parse_summary"));
                    return row;
                })
                .single();
    }

    /**
     * 查询文件分块区域列。
     *
     * @param tenant 租户
     * @param parseId 解析 ID
     * @return 区域列
     */
    private List<Map<String, Object>> fileChunkRegions(String tenant, UUID parseId) {
        return jdbcClient
                .sql("""
                        SELECT bbox_x, ocr_confidence
                          FROM file_chunk
                         WHERE tenant_id = :tenantId AND parse_id = :parseId
                         ORDER BY ordinal
                        """)
                .param("tenantId", tenant)
                .param("parseId", UuidBinary.toBytes(parseId))
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new java.util.HashMap<>();
                    row.put("bbox_x", rs.getObject("bbox_x"));
                    row.put("ocr_confidence", rs.getBigDecimal("ocr_confidence"));
                    return row;
                })
                .list();
    }

    /**
     * 测试种子。
     *
     * @param tenant 租户
     * @param fileId 文件 ID
     * @param parseId 解析 ID
     * @param documentId 知识库文档 ID
     */
    private record Seed(String tenant, UUID fileId, UUID parseId, UUID documentId) {}
}
