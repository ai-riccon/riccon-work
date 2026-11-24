package com.riccon.api.knowledge.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.file.storage.FileStorage;
import com.riccon.api.knowledge.connector.ConnectorApiTestSupport.ConnectorFixture;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.TestFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 连接器文档原文下载：按文档 ACL 从平台桶流式返回，不退回连接器源桶。
 *
 * <p>B6 把连接器对象写入平台桶 {@code tenants/{tenantId}/connector/{yyyy}/{documentId}}，
 * {@code knowledge_document.file_id} 为空、{@code connector_id} 非空。本套件用真实 MySQL、HTTP 与
 * RustFS 验证：UPLOAD 路径不受影响；连接器路径只读平台桶；跨租户/ACL 收窄/软删除统一 404；
 * 响应不泄露对象 key、桶名或预签名 URL。连接器源桶与平台桶分离，避免“读错桶也通过”。
 */
@TestPropertySource(properties = "riccon.kb.acl.enforcement-enabled=true")
class ConnectorDownloadApiTest extends ConnectorApiTestSupport {

    /** 测试内容 MIME。 */
    private static final String CONTENT_TYPE = "application/pdf";

    /** 测试内容，与 TestFiles.pdf 一致。 */
    private static final byte[] CONTENT = TestFiles.pdf();

    /** 平台对象存储端口；写入与下载都走同一实现，校验租户前缀。 */
    @Autowired
    private FileStorage fileStorage;

    /** 可见文档返回平台对象字节、大小与 MIME，且不泄露 key/预签名 URL。 */
    @Test
    @DisplayName("连接器下载：平台对象流式返回且无 key")
    void download_connectorDocumentStreamsPlatformObject() throws IOException {
        ConnectorFixture fixture = connectorFixture();
        String connectorId = createConnectorId(fixture);
        // 跨年用例：UTC 2025-12-31 23:30 在北京时间已是 2026 年；key 必须按 UTC 年派生。
        Instant createdAt = Instant.parse("2025-12-31T23:30:00Z");
        UUID documentId =
                insertConnectorDocument(fixture.tenant(), connectorId, actorId(fixture.admin()), createdAt, "TENANT");
        String key = platformKey(fixture.tenant(), documentId, createdAt);
        assertThat(key).contains("/connector/2025/").endsWith(documentId.toString());
        putPlatformObject(fixture.tenant(), key, CONTENT);

        ApiTestClient.ApiResponse response = download(fixture.member(), documentId.toString());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.header("Content-Type")).isEqualTo(CONTENT_TYPE);
        assertThat(response.header("Content-Length")).isEqualTo(String.valueOf(CONTENT.length));
        assertThat(response.header("Content-Disposition")).contains("attachment;");
        assertThat(response.header("Content-Disposition")).contains("filename*=UTF-8''");
        assertThat(response.header("Cache-Control")).isEqualTo("private, no-store");
        assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.body()).isEqualTo(new String(CONTENT, StandardCharsets.US_ASCII));
        assertThat(response.body()).doesNotContain("tenants/");
        assertThat(response.body()).doesNotContain("X-Amz-Signature");
        assertThat(response.headers().map().keySet())
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).startsWith("x-amz"));
        assertThat(response.header("Location")).isNull();
    }

    /** 下载每次请求重判 ACL：TENANT 收窄为 USERS 后，未授权成员立即 404；跨租户同样 404。 */
    @Test
    @DisplayName("连接器下载：ACL 收窄与跨租户")
    void download_connectorDocumentEnforcesAclPerRequest() throws IOException {
        ConnectorFixture fixture = connectorFixture();
        ConnectorFixture foreign = connectorFixture();
        LoginSession outsider = seedAndLoginLocalUser(
                fixture.platform(),
                fixture.tenant(),
                "out" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                "member-password-12",
                List.of("MEMBER"));
        String connectorId = createConnectorId(fixture);
        Instant createdAt = Instant.now();
        UUID documentId =
                insertConnectorDocument(fixture.tenant(), connectorId, actorId(fixture.admin()), createdAt, "TENANT");
        putPlatformObject(fixture.tenant(), platformKey(fixture.tenant(), documentId, createdAt), CONTENT);

        assertThat(download(fixture.member(), documentId.toString()).status()).isEqualTo(200);

        // 收窄为仅创建人可读：成员与同租户其他成员下一次请求立即 404，管理员仍按 U8 管理性读取可见。
        jdbcClient
                .sql("""
                        UPDATE knowledge_document
                           SET visibility = 'USERS', acl_version = acl_version + 1
                         WHERE tenant_id = :tenant AND id = :id
                        """)
                .param("tenant", fixture.tenant())
                .param("id", BinaryUuids.toBytes(documentId))
                .update();
        ApiTestClient.ApiResponse denied = download(fixture.member(), documentId.toString());
        assertThat(denied.status()).isEqualTo(404);
        assertThat(client.json(denied).path("code").asText()).isEqualTo("RICCON-TENANT-404");
        assertThat(denied.body()).doesNotContain("tenants/");
        assertThat(download(outsider, documentId.toString()).status()).isEqualTo(404);
        assertThat(download(fixture.admin(), documentId.toString()).status()).isEqualTo(200);

        // 跨租户管理员持文档 ID 直接下载：统一 404，不泄露文档或对象存在性。
        ApiTestClient.ApiResponse crossTenant = download(foreign.admin(), documentId.toString());
        assertThat(crossTenant.status()).isEqualTo(404);
        assertThat(client.json(crossTenant).path("code").asText()).isEqualTo("RICCON-TENANT-404");
    }

    /** 软删除后连接器文档立即 404，不再读取平台对象。 */
    @Test
    @DisplayName("连接器下载：软删除 404")
    void download_connectorDocumentSoftDeletedReturns404() throws IOException {
        ConnectorFixture fixture = connectorFixture();
        String connectorId = createConnectorId(fixture);
        Instant createdAt = Instant.now();
        UUID documentId =
                insertConnectorDocument(fixture.tenant(), connectorId, actorId(fixture.admin()), createdAt, "TENANT");
        putPlatformObject(fixture.tenant(), platformKey(fixture.tenant(), documentId, createdAt), CONTENT);

        jdbcClient
                .sql("""
                        UPDATE knowledge_document
                           SET status = 'DELETED', deleted_at = NOW(6)
                         WHERE tenant_id = :tenant AND id = :id
                        """)
                .param("tenant", fixture.tenant())
                .param("id", BinaryUuids.toBytes(documentId))
                .update();

        ApiTestClient.ApiResponse member = download(fixture.member(), documentId.toString());
        assertThat(member.status()).isEqualTo(404);
        assertThat(client.json(member).path("code").asText()).isEqualTo("RICCON-TENANT-404");
        assertThat(download(fixture.admin(), documentId.toString()).status()).isEqualTo(404);
    }

    /** 平台对象缺失沿用 UPLOAD 路径的 KB-502；连接器源桶里的同名对象不会被误读。 */
    @Test
    @DisplayName("连接器下载：平台对象缺失返回 KB-502")
    void download_missingPlatformObjectReturnsKb502() {
        ConnectorFixture fixture = connectorFixture();
        String connectorId = createConnectorId(fixture);
        Instant createdAt = Instant.now();
        UUID documentId =
                insertConnectorDocument(fixture.tenant(), connectorId, actorId(fixture.admin()), createdAt, "TENANT");
        String key = platformKey(fixture.tenant(), documentId, createdAt);
        assertThat(BUCKET).isNotEqualTo(PLATFORM_BUCKET);
        // 在连接器源桶写入同名 key：下载必须只读平台桶，不能因此变成 200。
        putObject(key, CONTENT);

        ApiTestClient.ApiResponse response = download(fixture.member(), documentId.toString());
        assertThat(response.status()).isEqualTo(502);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-KB-502");
        assertThat(response.body()).doesNotContain(key);
        assertThat(response.body()).doesNotContain("tenants/");
    }

    /** fileId 与 connectorId 都为空（SEED/已清理）仍返回 KB-404，不因新分支变成 500。 */
    @Test
    @DisplayName("连接器下载：无来源文档保持 KB-404")
    void download_documentWithoutSourceKeepsKb404() {
        ConnectorFixture fixture = connectorFixture();
        UUID documentId = insertUploadDocument(fixture.tenant());

        ApiTestClient.ApiResponse response = download(fixture.admin(), documentId.toString());
        assertThat(response.status()).isEqualTo(404);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-KB-404");
    }

    /**
     * 通过真实 HTTP 创建连接器并返回 ID。
     *
     * @param fixture 租户夹具
     * @return 连接器 ID
     */
    private String createConnectorId(ConnectorFixture fixture) {
        return json(
                        createConnector(
                                fixture.admin(),
                                "dl-" + UUID.randomUUID().toString().substring(0, 8),
                                "TENANT",
                                null,
                                false,
                                null),
                        201)
                .path("id")
                .asText();
    }

    /**
     * 插入一个 CONNECTOR 文档（file_id 为空、connector_id 非空）。
     *
     * @param tenant 租户
     * @param connectorId 连接器 ID
     * @param createdBy 添加人
     * @param createdAt 创建时间（UTC）
     * @param visibility 可见范围
     * @return 文档 ID
     */
    private UUID insertConnectorDocument(
            String tenant, String connectorId, UUID createdBy, Instant createdAt, String visibility) {
        UUID documentId = UuidV7.generate(Instant.now());
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_document (
                            tenant_id, id, title, source_type, file_id, connector_id, external_id, checksum,
                            mime_type, size_bytes, status, created_by, created_at, updated_at, visibility,
                            department_id, parse_engine, acl_version
                        ) VALUES (
                            :tenant, :id, '连接器下载文档', 'CONNECTOR', NULL, :connectorId, :externalId,
                            :checksum, :mimeType, :sizeBytes, 'PENDING', :createdBy, :createdAt, :createdAt,
                            :visibility, NULL, 'NONE', 1
                        )
                        """)
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(documentId))
                .param("connectorId", BinaryUuids.toBytes(UUID.fromString(connectorId)))
                .param("externalId", "docs/" + documentId + ".pdf")
                .param("checksum", "d".repeat(64))
                .param("mimeType", CONTENT_TYPE)
                .param("sizeBytes", (long) CONTENT.length)
                .param("createdBy", BinaryUuids.toBytes(createdBy))
                .param("createdAt", Timestamp.from(createdAt))
                .param("visibility", visibility)
                .update();
        return documentId;
    }

    /**
     * 按平台 key 写入平台桶对象；与 worker 使用同一存储端口。
     *
     * @param tenant 租户
     * @param key 平台对象 key
     * @param content 内容
     * @throws IOException 临时文件创建或删除失败
     */
    private void putPlatformObject(String tenant, String key, byte[] content) throws IOException {
        Path temp = Files.createTempFile("connector-download-", ".pdf");
        try {
            Files.write(temp, content);
            fileStorage.putObject(tenant, key, temp, CONTENT_TYPE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 派生平台对象 key，与 worker {@code ConnectorContentTypes.objectKey} 一致（UTC 年）。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @param createdAt 创建时间
     * @return 平台对象 key
     */
    private static String platformKey(String tenant, UUID documentId, Instant createdAt) {
        return "tenants/" + tenant + "/connector/"
                + createdAt.atZone(ZoneOffset.UTC).getYear() + "/" + documentId;
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
}
