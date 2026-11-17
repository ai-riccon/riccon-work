package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.Sha256;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.FileApiTestSupport;
import com.riccon.api.support.TestFiles;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 文件上传集成测试。
 *
 * <p>覆盖真实 S3 写入、checksum、元数据、解析 outbox、审计、格式/MIME/magic bytes 校验与租户隔离。
 */
class FileUploadApiTest extends FileApiTestSupport {

    /** JSON 解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** PDF 上传生成完整元数据、对象、运行、outbox 与审计。 */
    @Test
    @DisplayName("上传 PDF 写入 S3、元数据、outbox 与审计")
    void uploadPdf_writesObjectMetadataOutboxAndAudit() throws Exception {
        TenantFixture fixture = tenantFixture();
        byte[] content = TestFiles.pdf();
        JsonNode file =
                upload(fixture.member(), fixture.memberWorkspace(), "report.pdf", "application/pdf", content, null);

        String fileId = file.path("id").asText();
        assertThat(file.path("workspaceId").asText()).isEqualTo(fixture.memberWorkspace());
        assertThat(file.path("conversationId").isNull()).isTrue();
        assertThat(file.path("originalName").asText()).isEqualTo("report.pdf");
        assertThat(file.path("format").asText()).isEqualTo("PDF");
        assertThat(file.path("mimeType").asText()).isEqualTo("application/pdf");
        assertThat(file.path("sizeBytes").asLong()).isEqualTo(content.length);
        assertThat(file.path("sha256").asText()).isEqualTo(Sha256.hex(content));
        assertThat(file.path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(file.path("parse").path("attempt").asInt()).isEqualTo(1);
        assertThat(file.path("parse").path("status").asText()).isEqualTo("PENDING");
        assertThat(file.path("deletedAt").isNull()).isTrue();

        Map<String, Object> row = fileRow(fixture.tenant(), fileId);
        String storageKey = (String) row.get("storage_key");
        assertThat(storageKey).matches("tenants/" + fixture.tenant() + "/file/\\d{4}/" + fileId);
        assertThat(row.get("status")).isEqualTo("AVAILABLE");
        assertThat(row.get("sha256")).isEqualTo(Sha256.hex(content));
        assertThat(row.get("uploaded_by")).isEqualTo(UUID.fromString(userId(fixture.member())));
        assertThat(row.get("workspace_id")).isEqualTo(UUID.fromString(fixture.memberWorkspace()));
        assertThat(objectBytes(storageKey)).isEqualTo(content);

        Map<String, Object> outbox = outboxForFile(fixture.tenant(), fileId);
        assertThat(outbox.get("event_type")).isEqualTo("riccon.file.parse.requested.v1");
        assertThat(outbox.get("event_version")).isEqualTo(1);
        assertThat(outbox.get("outbox_status")).isEqualTo("PENDING");
        assertThat(outbox.get("attempts")).isEqualTo(0);
        assertThat(outbox.get("kind")).isEqualTo("FILE_PARSE");
        assertThat(outbox.get("run_status")).isEqualTo("PENDING");
        assertThat(outbox.get("file_id")).isEqualTo(UUID.fromString(fileId));
        assertThat(outbox.get("user_id")).isEqualTo(UUID.fromString(userId(fixture.member())));
        assertThat(outbox.get("trace_id")).isNotNull();

        JsonNode payload = JSON.readTree((String) outbox.get("payload"));
        assertThat(payload.path("fileId").asText()).isEqualTo(fileId);
        assertThat(payload.path("parseId").asText())
                .isEqualTo(file.path("parse").path("id").asText());
        assertThat(payload.path("attempt").asInt()).isEqualTo(1);
        assertThat(outbox.get("idempotency_key"))
                .isEqualTo("file:" + fileId + ":parse:"
                        + file.path("parse").path("id").asText());

        assertThat(auditRows("file.uploaded"))
                .filteredOn(audit -> fileId.equals(audit.targetId()))
                .singleElement()
                .satisfies(audit -> {
                    assertThat(audit.tenantId()).isEqualTo(fixture.tenant());
                    assertThat(audit.targetType()).isEqualTo("FILE");
                    assertThat(audit.outcome()).isEqualTo("SUCCESS");
                    assertThat(audit.details())
                            .contains(fileId)
                            .contains(Sha256.hex(content))
                            .contains(String.valueOf(content.length));
                });
    }

    /** 五种合同格式在扩展名、MIME 与 magic bytes 一致时全部接受。 */
    @Test
    @DisplayName("PDF/DOCX/XLSX/PNG/JPEG 均可上传")
    void uploadSupportedFormats_acceptsAllContractFormats() {
        TenantFixture fixture = tenantFixture();
        assertFormat(fixture, "a.pdf", "application/pdf", TestFiles.pdf(), "PDF");
        assertFormat(
                fixture,
                "a.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                TestFiles.docx(),
                "DOCX");
        assertFormat(
                fixture,
                "a.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                TestFiles.xlsx(),
                "XLSX");
        assertFormat(fixture, "a.png", "image/png", TestFiles.png(), "PNG");
        assertFormat(fixture, "a.jpeg", "image/jpeg", TestFiles.jpeg(), "JPEG");
    }

    /** 扩展名、声明 MIME 与内容不一致时返回 415，且不写业务数据。 */
    @Test
    @DisplayName("不支持或伪装的格式返回 415")
    void uploadInvalidFormats_returnsUnsupported() {
        TenantFixture fixture = tenantFixture();
        long before = fileCount(fixture.tenant());

        ApiTestClient.ApiResponse unsupported = uploadRaw(
                fixture.member(), fixture.memberWorkspace(), "notes.txt", "text/plain", "hello".getBytes(), null);
        assertThat(unsupported.status()).isEqualTo(415);
        assertThat(client.json(unsupported).path("code").asText()).isEqualTo("RICCON-FILE-415");

        ApiTestClient.ApiResponse wrongMime = uploadRaw(
                fixture.member(), fixture.memberWorkspace(), "report.pdf", "image/png", TestFiles.pdf(), null);
        assertThat(wrongMime.status()).isEqualTo(415);

        ApiTestClient.ApiResponse wrongMagic = uploadRaw(
                fixture.member(), fixture.memberWorkspace(), "report.pdf", "application/pdf", TestFiles.png(), null);
        assertThat(wrongMagic.status()).isEqualTo(415);

        ApiTestClient.ApiResponse zipAsDocx = uploadRaw(
                fixture.member(),
                fixture.memberWorkspace(),
                "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                TestFiles.zip(Map.of("[Content_Types].xml", "<Types/>")),
                null);
        assertThat(zipAsDocx.status()).isEqualTo(415);

        assertThat(fileCount(fixture.tenant())).isEqualTo(before);
    }

    /** 空文件与非法文件名返回 400。 */
    @Test
    @DisplayName("空文件返回 400")
    void uploadEmptyFile_returnsBadRequest() {
        TenantFixture fixture = tenantFixture();
        ApiTestClient.ApiResponse empty = uploadRaw(
                fixture.member(), fixture.memberWorkspace(), "empty.pdf", "application/pdf", new byte[0], null);
        assertThat(empty.status()).isEqualTo(400);
        assertThat(client.json(empty).path("code").asText()).isEqualTo("RICCON-FILE-400");
    }

    /** 原始文件名只保留 basename，不进入对象 key。 */
    @Test
    @DisplayName("文件名路径被剥离且不进入对象 key")
    void uploadPathLikeFilename_stripsPath() {
        TenantFixture fixture = tenantFixture();
        JsonNode file = upload(
                fixture.member(),
                fixture.memberWorkspace(),
                "../../evil.pdf",
                "application/pdf",
                TestFiles.pdf(),
                null);
        assertThat(file.path("originalName").asText()).isEqualTo("evil.pdf");
        String storageKey =
                (String) fileRow(fixture.tenant(), file.path("id").asText()).get("storage_key");
        assertThat(storageKey).doesNotContain("evil.pdf").doesNotContain("..");
    }

    /** 上传到其他租户工作空间返回 404，不泄露存在性。 */
    @Test
    @DisplayName("跨租户上传返回 404")
    void upload_crossTenantWorkspace_returnsNotFound() {
        TenantFixture fixtureA = tenantFixture();
        TenantFixture fixtureB = tenantFixture();
        ApiTestClient.ApiResponse response = uploadRaw(
                fixtureB.member(), fixtureA.memberWorkspace(), "report.pdf", "application/pdf", TestFiles.pdf(), null);
        assertThat(response.status()).isEqualTo(404);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-TENANT-404");
    }

    /** 上传到其他用户的个人空间返回 404，即使同租户同角色。 */
    @Test
    @DisplayName("同租户他人个人空间不可上传")
    void upload_otherUsersPersonalWorkspace_returnsNotFound() {
        TenantFixture fixture = tenantFixture();
        ApiTestClient.ApiResponse response = uploadRaw(
                fixture.member(), fixture.adminWorkspace(), "report.pdf", "application/pdf", TestFiles.pdf(), null);
        assertThat(response.status()).isEqualTo(404);
    }

    /**
     * 断言单格式上传成功。
     *
     * @param fixture 租户
     * @param filename 文件名
     * @param contentType MIME
     * @param content 内容
     * @param format 期望格式
     */
    private void assertFormat(
            TenantFixture fixture, String filename, String contentType, byte[] content, String format) {
        JsonNode file = upload(fixture.member(), fixture.memberWorkspace(), filename, contentType, content, null);
        assertThat(file.path("format").asText()).isEqualTo(format);
        assertThat(file.path("mimeType").asText()).isEqualTo(contentType);
        assertThat(file.path("sizeBytes").asLong()).isEqualTo(content.length);
    }

    /**
     * 统计租户文件数。
     *
     * @param tenant 租户
     * @return 文件数
     */
    private long fileCount(String tenant) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM file WHERE tenant_id = :tenantId")
                .param("tenantId", tenant)
                .query(Long.class)
                .single();
    }
}
