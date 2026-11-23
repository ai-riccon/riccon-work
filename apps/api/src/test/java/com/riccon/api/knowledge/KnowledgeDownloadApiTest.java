package com.riccon.api.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.TestFiles;
import com.riccon.platform.domain.audit.AuditActions;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M05 知识库流式下载：每请求 ACL、响应头、无对象 key、KB-404 与管理性读取审计。
 */
@TestPropertySource(properties = "riccon.kb.acl.enforcement-enabled=true")
class KnowledgeDownloadApiTest extends KnowledgeAclTestSupport {

    /** JSON 解析。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 可见文档返回原始字节与安全响应头，不泄露对象 key/预签名 URL。 */
    @Test
    @DisplayName("下载：流式字节、响应头与无 key")
    void download_streamsSourceWithoutExposingKey() {
        TenantFixture fixture = tenantFixture();
        JsonNode document = createDocument(fixture, "下载文档", null, null, null);
        String documentId = document.path("id").asText();

        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/knowledge/documents/" + documentId + "/download",
                fixture.member().sessionCookie(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.header("Content-Type")).isEqualTo("application/pdf");
        assertThat(response.header("Content-Disposition")).contains("attachment;");
        assertThat(response.header("Content-Disposition")).contains("filename*=UTF-8''");
        assertThat(response.header("Cache-Control")).isEqualTo("private, no-store");
        assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.body()).isEqualTo(new String(TestFiles.pdf(), StandardCharsets.US_ASCII));
        assertThat(response.body()).doesNotContain("tenants/");
        assertThat(response.body()).doesNotContain("X-Amz-Signature");
    }

    /** USERS 文档每次请求重判 ACL；未授权 404，跨租户 404。 */
    @Test
    @DisplayName("下载：每请求 ACL 与跨租户")
    void download_enforcesAclPerRequest() {
        TenantFixture fixture = tenantFixture();
        TenantFixture foreign = tenantFixture();
        LoginSession outsider = seedLogin(fixture.platform(), fixture.tenant(), "out", List.of("MEMBER"));
        JsonNode document = createDocument(fixture, "用户下载", "USERS", null, List.of(actorId(fixture.member())));
        String documentId = document.path("id").asText();

        assertThat(download(fixture.member(), documentId).status()).isEqualTo(200);
        ApiTestClient.ApiResponse denied = download(outsider, documentId);
        assertThat(denied.status()).isEqualTo(404);
        assertThat(client.json(denied).path("code").asText()).isEqualTo("RICCON-TENANT-404");
        assertThat(download(foreign.admin(), documentId).status()).isEqualTo(404);
    }

    /** 软删除文档与平台管理员下载统一 404（不泄露存在性）。 */
    @Test
    @DisplayName("下载：软删除与平台管理员统一 404")
    void download_softDeletedAndPlatformAdmin_returnTenant404() {
        TenantFixture fixture = tenantFixture();
        JsonNode document = createDocument(fixture, "已删除下载", null, null, null);
        String documentId = document.path("id").asText();
        assertThat(client.delete(
                                "/api/v1/knowledge/documents/" + documentId,
                                fixture.admin().sessionCookie(),
                                fixture.admin().csrfToken())
                        .status())
                .isEqualTo(204);
        assertThat(download(fixture.admin(), documentId).status()).isEqualTo(404);
        assertThat(client.json(download(fixture.admin(), documentId))
                        .path("code")
                        .asText())
                .isEqualTo("RICCON-TENANT-404");

        JsonNode live = createDocument(fixture, "平台管理员不可见", null, null, null);
        assertThat(download(fixture.platform(), live.path("id").asText()).status())
                .isEqualTo(404);
    }

    /** ACL 通过但无源文件返回 RICCON-KB-404；源文件软删除同样。 */
    @Test
    @DisplayName("下载：无源文件与已删除源文件返回 RICCON-KB-404")
    void download_missingSourceReturnsKb404() {
        TenantFixture fixture = tenantFixture();
        String seedId = insertSeedDocument(fixture.tenant(), actorId(fixture.admin()));
        ApiTestClient.ApiResponse seed = download(fixture.admin(), seedId);
        assertThat(seed.status()).isEqualTo(404);
        assertThat(client.json(seed).path("code").asText()).isEqualTo("RICCON-KB-404");
        assertThat(seed.body()).doesNotContain("RICCON-TENANT-404");

        JsonNode document = createDocument(fixture, "源文件删除", null, null, null);
        String documentId = document.path("id").asText();
        jdbcClient
                .sql("""
                        UPDATE file SET status = 'DELETED', deleted_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :fileId
                        """)
                .param("tenantId", fixture.tenant())
                .param(
                        "fileId",
                        BinaryUuids.toBytes(
                                UUID.fromString(document.path("fileId").asText())))
                .update();
        ApiTestClient.ApiResponse deleted = download(fixture.admin(), documentId);
        assertThat(deleted.status()).isEqualTo(404);
        assertThat(client.json(deleted).path("code").asText()).isEqualTo("RICCON-KB-404");
    }

    /** 管理员绕过 ACL 的下载写 admin_read，按 ACL 可见的下载不写。 */
    @Test
    @DisplayName("下载：管理员绕过 ACL 写 admin_read")
    void download_managementReadWritesAudit() throws Exception {
        TenantFixture fixture = tenantFixture();
        UUID dept = createDepartment(fixture.platform(), fixture.tenant(), "DOWN-DEPT");
        LoginSession creator = seedLogin(fixture.platform(), fixture.tenant(), "creator", List.of("TENANT_ADMIN"));
        JsonNode deptDoc = createDocumentAs(creator, personalWorkspace(creator), "部门下载", "DEPT", dept, null);
        String deptId = deptDoc.path("id").asText();

        ApiTestClient.ApiResponse response = download(fixture.admin(), deptId);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        List<AuditRow> audits = documentAudit(AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ, fixture.tenant(), deptId);
        assertThat(audits).hasSize(1);
        JsonNode details = JSON.readTree(audits.get(0).details());
        assertThat(details.path("scope").asText()).isEqualTo("DOWNLOAD");
        assertThat(details.path("visibility").asText()).isEqualTo("DEPT");
        assertThat(details.path("reason").asText()).isEqualTo("MANAGEMENT_READ");
        assertThat(details.path("aclUserCount").asLong()).isZero();

        // 按 ACL 本来可见的 TENANT 文档不写 admin_read，避免读放大。
        JsonNode tenantDoc = createDocument(fixture, "租户下载", null, null, null);
        assertThat(download(fixture.admin(), tenantDoc.path("id").asText()).status())
                .isEqualTo(200);
        assertThat(documentAudit(
                        AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ,
                        fixture.tenant(),
                        tenantDoc.path("id").asText()))
                .isEmpty();
    }

    /** 对象存储读取失败返回 502 且不泄露对象 key。 */
    @Test
    @DisplayName("下载：存储读取失败返回 RICCON-KB-502")
    void download_storageFailureReturnsKb502() {
        TenantFixture fixture = tenantFixture();
        JsonNode document = createDocument(fixture, "对象缺失", null, null, null);
        String documentId = document.path("id").asText();
        String storageKey = jdbcClient
                .sql("SELECT storage_key FROM file WHERE tenant_id = :tenantId AND id = :fileId")
                .param("tenantId", fixture.tenant())
                .param(
                        "fileId",
                        BinaryUuids.toBytes(
                                UUID.fromString(document.path("fileId").asText())))
                .query(String.class)
                .single();
        s3Client.deleteObject(builder -> builder.bucket(BUCKET).key(storageKey));

        ApiTestClient.ApiResponse response = download(fixture.admin(), documentId);
        assertThat(response.status()).isEqualTo(502);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-KB-502");
        assertThat(response.body()).doesNotContain(storageKey);
        assertThat(response.body()).doesNotContain("tenants/");
    }

    /**
     * 下载请求。
     *
     * @param session 会话
     * @param documentId 文档 ID
     * @return 响应
     */
    private ApiTestClient.ApiResponse download(LoginSession session, String documentId) {
        return client.get("/api/v1/knowledge/documents/" + documentId + "/download", session.sessionCookie(), Map.of());
    }

    /**
     * 直接写入一个无源文件的 SEED 文档。
     *
     * @param tenant 租户
     * @param creator 创建人
     * @return 文档 ID
     */
    private String insertSeedDocument(String tenant, UUID creator) {
        UUID documentId = UuidV7.generate(Instant.now());
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_document (
                            tenant_id, id, title, source_type, file_id, checksum, mime_type, size_bytes,
                            status, created_by, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, 'SEED 语料', 'SEED', NULL, :checksum, 'text/plain', 4,
                            'INDEXED', :createdBy, NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(documentId))
                .param("checksum", "0".repeat(64))
                .param("createdBy", BinaryUuids.toBytes(creator))
                .update();
        return documentId.toString();
    }
}
