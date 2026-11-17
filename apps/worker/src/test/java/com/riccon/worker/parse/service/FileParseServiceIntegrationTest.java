package com.riccon.worker.parse.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.platform.domain.research.RunKind;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.service.OutboxPoller;
import com.riccon.worker.support.TestDataFactory;
import com.riccon.worker.support.TestDocuments;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/** 文件解析端到端（真实 MySQL + S3 + 解析器）测试。 */
class FileParseServiceIntegrationTest extends WorkerIntegrationTestBase {

    /** 轮询器。 */
    @Autowired
    private OutboxPoller poller;

    /** 文件解析处理器，用于重复投递测试。 */
    @Autowired
    private FileParseService fileParseService;

    /** PDF 两页解析成功并写页码定位。 */
    @Test
    void pollOnce_pdf_updatesParseAndChunks() {
        Seed seed = seed("tenant-a", FileFormat.PDF, TestDocuments.pdf(2), "report.pdf");

        assertThat(poller.pollOnce()).isEqualTo(1);

        Map<String, Object> parse = parseRow(seed.tenant(), seed.parseId());
        assertThat(parse).containsEntry("status", "SUCCEEDED");
        assertThat(parse.get("page_count")).isEqualTo(2);
        assertThat(parse.get("sheet_count")).isNull();
        assertThat(parse.get("chunk_count")).isEqualTo(2);
        assertThat(parse.get("parser_version")).isEqualTo("m02.1");
        assertThat(parse.get("error_code")).isNull();
        assertThat(outboxStatus(seed.eventId())).isEqualTo("DONE");
        assertThat(runStatus(seed.runId())).isEqualTo("SUCCEEDED");

        List<ChunkRow> chunks = chunks(seed.tenant(), seed.parseId());
        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(ChunkRow::pageNo).containsExactly(1, 2);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.locatorType()).isEqualTo("PAGE"));
        assertThat(chunks.get(0).content()).contains("revenue analysis");
    }

    /** DOCX 段落序号写入定位。 */
    @Test
    void pollOnce_docx_updatesParagraphLocators() {
        Seed seed = seed("tenant-a", FileFormat.DOCX, TestDocuments.docx(), "report.docx");

        assertThat(poller.pollOnce()).isEqualTo(1);

        Map<String, Object> parse = parseRow(seed.tenant(), seed.parseId());
        assertThat(parse).containsEntry("status", "SUCCEEDED");
        assertThat(parse.get("page_count")).isNull();
        assertThat(parse.get("chunk_count")).isEqualTo(3);
        List<ChunkRow> chunks = chunks(seed.tenant(), seed.parseId());
        assertThat(chunks).extracting(ChunkRow::paragraphNo).containsExactly(1, 2, 3);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.locatorType()).isEqualTo("PARAGRAPH"));
        assertThat(chunks.get(2).content()).contains("APAC");
    }

    /** XLSX 工作表与单元格范围写入定位。 */
    @Test
    void pollOnce_xlsx_updatesSheetLocators() {
        Seed seed = seed("tenant-a", FileFormat.XLSX, TestDocuments.xlsx(), "budget.xlsx");

        assertThat(poller.pollOnce()).isEqualTo(1);

        Map<String, Object> parse = parseRow(seed.tenant(), seed.parseId());
        assertThat(parse).containsEntry("status", "SUCCEEDED");
        assertThat(parse.get("sheet_count")).isEqualTo(2);
        assertThat(parse.get("chunk_count")).isEqualTo(2);
        List<ChunkRow> chunks = chunks(seed.tenant(), seed.parseId());
        assertThat(chunks).extracting(ChunkRow::sheetName).containsExactly("Budget", "Summary");
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.locatorType()).isEqualTo("SHEET"));
        assertThat(chunks.get(0).cellRange()).isEqualTo("A1:B2");
    }

    /** PNG 只产出元数据块，不执行 OCR。 */
    @Test
    void pollOnce_png_storesMetadataOnly() {
        Seed seed = seed("tenant-a", FileFormat.PNG, TestDocuments.png(), "photo.png");

        assertThat(poller.pollOnce()).isEqualTo(1);

        Map<String, Object> parse = parseRow(seed.tenant(), seed.parseId());
        assertThat(parse).containsEntry("status", "SUCCEEDED");
        assertThat(parse.get("page_count")).isEqualTo(1);
        assertThat(parse.get("chunk_count")).isEqualTo(1);
        assertThat(chunks(seed.tenant(), seed.parseId()).get(0).content())
                .contains("图片元数据")
                .contains("M02 未执行 OCR");
    }

    /** JPEG 与 PNG 使用同一元数据策略。 */
    @Test
    void pollOnce_jpeg_storesMetadataOnly() {
        Seed seed = seed("tenant-a", FileFormat.JPEG, TestDocuments.jpeg(), "photo.jpg");

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(parseRow(seed.tenant(), seed.parseId())).containsEntry("status", "SUCCEEDED");
        assertThat(chunks(seed.tenant(), seed.parseId()).get(0).content()).contains("M02 未执行 OCR");
    }

    /** 损坏 PDF 属于不可重试错误：保留原因，直接终止，不无限重试。 */
    @Test
    void pollOnce_corruptPdf_marksFailedAndDead() {
        Seed seed = seed("tenant-a", FileFormat.PDF, TestDocuments.corruptPdf(), "broken.pdf");

        assertThat(poller.pollOnce()).isEqualTo(1);

        Map<String, Object> parse = parseRow(seed.tenant(), seed.parseId());
        assertThat(parse).containsEntry("status", "FAILED");
        assertThat(parse.get("error_code")).isEqualTo("RICCON-FILE-422");
        assertThat((String) parse.get("error_message")).contains("pdf document is not readable");
        assertThat(outboxStatus(seed.eventId())).isEqualTo("DEAD");
        assertThat(runStatus(seed.runId())).isEqualTo("FAILED");
    }

    /** 重复投递已成功的解析不产生重复分块。 */
    @Test
    void handle_duplicateSuccessfulEvent_doesNotDuplicateChunks() {
        Seed seed = seed("tenant-a", FileFormat.PDF, TestDocuments.pdf(1), "report.pdf");
        assertThat(poller.pollOnce()).isEqualTo(1);
        int before = chunks(seed.tenant(), seed.parseId()).size();

        ClaimedOutboxEvent duplicate = new ClaimedOutboxEvent(
                seed.eventId(),
                seed.tenant(),
                seed.runId(),
                EventTypes.FILE_PARSE_REQUESTED,
                1,
                "file:" + seed.fileId() + ":parse:1",
                seed.payload(),
                2,
                Instant.now(),
                Instant.now().plusSeconds(30));
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(seed.tenant()))) {
            fileParseService.handle(duplicate);
        }

        assertThat(chunks(seed.tenant(), seed.parseId())).hasSize(before);
        assertThat(parseRow(seed.tenant(), seed.parseId())).containsEntry("status", "SUCCEEDED");
    }

    /** 跨租户 fileId 不得读取或修改其他租户的解析记录。 */
    @Test
    void pollOnce_crossTenantFile_doesNotLeak() {
        Seed tenantB = seed("tenant-b", FileFormat.PDF, TestDocuments.pdf(1), "other.pdf", false);
        String tenantA = "tenant-a";
        data.createTenant(tenantA);
        UUID userA = data.createUser(tenantA);
        UUID workspaceA = data.createWorkspace(tenantA, userA);
        UUID fileA = data.createFile(tenantA, workspaceA, userA, FileFormat.PDF, TestDocuments.pdf(1));
        UUID parseA = data.createParse(tenantA, fileA, 1);
        UUID runA = data.createRun(tenantA, RunKind.FILE_PARSE, fileA, userA, 3);
        UUID eventA = data.createOutbox(
                tenantA,
                runA,
                EventTypes.FILE_PARSE_REQUESTED,
                data.fileParsePayload(tenantB.fileId(), tenantB.parseId(), 1),
                "cross-tenant-1");

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(outboxStatus(eventA)).isEqualTo("DEAD");
        assertThat(runStatus(runA)).isEqualTo("FAILED");
        assertThat(parseRow(tenantA, parseA)).containsEntry("status", "PENDING");
        assertThat(parseRow(tenantB.tenant(), tenantB.parseId())).containsEntry("status", "PENDING");
    }

    /** 对象缺失按不存在处理并保留失败原因。 */
    @Test
    void pollOnce_missingObject_marksFailed() {
        Seed seed = seed("tenant-a", FileFormat.PDF, TestDocuments.pdf(1), "missing.pdf");
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(BUCKET)
                .key("tenants/tenant-a/file/2026/" + seed.fileId())
                .build());

        assertThat(poller.pollOnce()).isEqualTo(1);

        Map<String, Object> parse = parseRow(seed.tenant(), seed.parseId());
        assertThat(parse).containsEntry("status", "FAILED");
        assertThat(parse.get("error_code")).isEqualTo("RICCON-TENANT-404");
        assertThat(outboxStatus(seed.eventId())).isEqualTo("DEAD");
    }

    /**
     * 创建文件、解析尝试、运行与 outbox。
     *
     * @param tenant 租户
     * @param format 格式
     * @param content 内容
     * @param name 文件名（仅用于测试可读性）
     * @return 种子
     */
    private Seed seed(String tenant, FileFormat format, byte[] content, String name) {
        return seed(tenant, format, content, name, true);
    }

    /**
     * 创建文件、解析尝试、运行与可选 outbox。
     *
     * @param tenant 租户
     * @param format 格式
     * @param content 内容
     * @param name 文件名
     * @param publish 是否发布解析事件
     * @return 种子
     */
    private Seed seed(String tenant, FileFormat format, byte[] content, String name, boolean publish) {
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        UUID workspaceId = data.createWorkspace(tenant, userId);
        UUID fileId = data.createFile(tenant, workspaceId, userId, format, content);
        UUID parseId = data.createParse(tenant, fileId, 1);
        if (!publish) {
            return new Seed(tenant, fileId, parseId, null, null, null, name);
        }
        TestDataFactory.PublishedEvent event = data.publishFileParse(tenant, fileId, parseId, userId, 1);
        return new Seed(tenant, fileId, parseId, event.runId(), event.eventId(), event.payload(), name);
    }

    /**
     * 查询解析尝试字段。
     *
     * @param tenant 租户
     * @param parseId 解析 ID
     * @return 字段映射
     */
    private Map<String, Object> parseRow(String tenant, UUID parseId) {
        return jdbcClient
                .sql("""
                        SELECT status, page_count, sheet_count, chunk_count, parser_version, error_code, error_message
                          FROM file_parse
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(parseId))
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new java.util.HashMap<>();
                    row.put("status", rs.getString("status"));
                    row.put("page_count", rs.getObject("page_count"));
                    row.put("sheet_count", rs.getObject("sheet_count"));
                    row.put("chunk_count", rs.getObject("chunk_count"));
                    row.put("parser_version", rs.getString("parser_version"));
                    row.put("error_code", rs.getString("error_code"));
                    row.put("error_message", rs.getString("error_message"));
                    return row;
                })
                .single();
    }

    /**
     * 查询解析分块。
     *
     * @param tenant 租户
     * @param parseId 解析 ID
     * @return 分块
     */
    private List<ChunkRow> chunks(String tenant, UUID parseId) {
        return jdbcClient
                .sql("""
                        SELECT locator_type, page_no, sheet_name, cell_range, paragraph_no, content
                          FROM file_chunk
                         WHERE tenant_id = :tenantId AND parse_id = :parseId
                         ORDER BY ordinal
                        """)
                .param("tenantId", tenant)
                .param("parseId", UuidBinary.toBytes(parseId))
                .query((rs, rowNum) -> new ChunkRow(
                        rs.getString("locator_type"),
                        (Integer) rs.getObject("page_no"),
                        rs.getString("sheet_name"),
                        rs.getString("cell_range"),
                        (Integer) rs.getObject("paragraph_no"),
                        rs.getString("content")))
                .list();
    }

    /**
     * 查询 outbox 状态。
     *
     * @param eventId 事件 ID
     * @return 状态
     */
    private String outboxStatus(UUID eventId) {
        return jdbcClient
                .sql("SELECT status FROM agent_run_outbox WHERE id = :id")
                .param("id", UuidBinary.toBytes(eventId))
                .query(String.class)
                .single();
    }

    /**
     * 查询 outbox 下次可用时间。
     *
     * @param eventId 事件 ID
     * @return 时间
     */
    private Instant availableAt(UUID eventId) {
        return jdbcClient
                .sql("SELECT available_at FROM agent_run_outbox WHERE id = :id")
                .param("id", UuidBinary.toBytes(eventId))
                .query((rs, rowNum) -> rs.getObject("available_at", java.time.LocalDateTime.class)
                        .toInstant(java.time.ZoneOffset.UTC))
                .single();
    }

    /**
     * 查询运行状态。
     *
     * @param runId 运行 ID
     * @return 状态
     */
    private String runStatus(UUID runId) {
        return jdbcClient
                .sql("SELECT status FROM agent_run WHERE id = :id")
                .param("id", UuidBinary.toBytes(runId))
                .query(String.class)
                .single();
    }

    /**
     * 让重试事件立即可领取。
     *
     * @param eventId 事件 ID
     */
    private void makeAvailableNow(UUID eventId) {
        jdbcClient
                .sql("UPDATE agent_run_outbox SET available_at = NOW(6) WHERE id = :id")
                .param("id", UuidBinary.toBytes(eventId))
                .update();
    }

    /**
     * 测试种子。
     *
     * @param tenant 租户
     * @param fileId 文件 ID
     * @param parseId 解析 ID
     * @param runId 运行 ID
     * @param eventId 事件 ID
     * @param payload 事件 payload
     * @param name 文件名
     */
    private record Seed(
            String tenant, UUID fileId, UUID parseId, UUID runId, UUID eventId, String payload, String name) {}

    /**
     * 分块行。
     *
     * @param locatorType 定位类型
     * @param pageNo 页码
     * @param sheetName 工作表
     * @param cellRange 范围
     * @param paragraphNo 段落
     * @param content 内容
     */
    private record ChunkRow(
            String locatorType,
            Integer pageNo,
            String sheetName,
            String cellRange,
            Integer paragraphNo,
            String content) {}
}
