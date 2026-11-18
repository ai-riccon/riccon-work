package com.riccon.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.FileApiTestSupport;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 产物正文同源代理集成测试。
 *
 * <p>使用真实 MySQL 与 RustFS（Testcontainers）验证对象读取；产物的可见性、跨租户与软删除语义
 * 与下载地址一致。超限用例分别覆盖数据库元数据与对象实际大小两条路径，读取失败覆盖对象缺失。
 */
class ArtifactContentApiTest extends FileApiTestSupport {

    /** 与 ArtifactService 一致的正文上限。 */
    private static final int MAX_CONTENT_BYTES = 2 * 1024 * 1024;

    /** JSON 解析器，用于断言审计 details。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Markdown 正文样例。 */
    private static final String MARKDOWN = "# 季度市场报告\n\n- 数据来源：知识库\n- 结论：平稳增长\n";

    /** 成功读取 Markdown 正文，响应头固定且写 artifact.downloaded 审计。 */
    @Test
    @DisplayName("同源读取 Markdown 正文与审计")
    void content_returnsMarkdownBodyAndAudits() throws Exception {
        TenantFixture fixture = tenantFixture();
        UUID artifactId = seedArtifact(fixture.tenant(), fixture.adminWorkspace(), userId(fixture.admin()), "SAVED", 1);
        byte[] body = MARKDOWN.getBytes(UTF_8);
        String key = storageKey(fixture.tenant(), artifactId, 1, "md");
        seedVersion(fixture.tenant(), artifactId, 1, key, "text/markdown", body.length, userId(fixture.admin()));
        putObject(key, "text/markdown", body);

        ApiTestClient.ApiResponse response =
                client.get(contentPath(artifactId, 1), fixture.admin().sessionCookie(), Map.of());

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(MARKDOWN);
        assertThat(response.header("Content-Type")).startsWith("text/markdown");
        assertThat(response.header("Content-Length")).isEqualTo(Integer.toString(body.length));
        assertThat(response.header("Cache-Control")).isEqualTo("no-store");
        assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff");
        // 响应只包含正文，不出现对象 key 或对象存储地址。
        assertThat(response.body()).doesNotContain(key).doesNotContain("http");

        List<AuditRow> rows = auditRows("artifact.downloaded").stream()
                .filter(row -> fixture.tenant().equals(row.tenantId()))
                .filter(row -> artifactId.toString().equals(row.targetId()))
                .toList();
        assertThat(rows).hasSize(1);
        AuditRow row = rows.get(0);
        assertThat(row.targetType()).isEqualTo("ARTIFACT");
        assertThat(row.actorType()).isEqualTo("USER");
        assertThat(row.actorId()).isEqualTo(userId(fixture.admin()));
        JsonNode details = JSON.readTree(row.details());
        assertThat(details.path("artifactId").asText()).isEqualTo(artifactId.toString());
        assertThat(details.path("version").asInt()).isEqualTo(1);
    }

    /** 存储为 text/plain 的版本按纯文本返回。 */
    @Test
    @DisplayName("纯文本正文保持 text/plain")
    void content_returnsPlainTextContentType() {
        TenantFixture fixture = tenantFixture();
        UUID artifactId = seedArtifact(fixture.tenant(), fixture.adminWorkspace(), userId(fixture.admin()), "SAVED", 1);
        byte[] body = "纯文本正文\n".getBytes(UTF_8);
        String key = storageKey(fixture.tenant(), artifactId, 1, "txt");
        seedVersion(fixture.tenant(), artifactId, 1, key, "text/plain", body.length, userId(fixture.admin()));
        putObject(key, "text/plain", body);

        ApiTestClient.ApiResponse response =
                client.get(contentPath(artifactId, 1), fixture.admin().sessionCookie(), Map.of());

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("text/plain");
        assertThat(response.body()).isEqualTo("纯文本正文\n");
    }

    /** 跨租户、同租户非工作空间成员、软删除与未知产物统一 404。 */
    @Test
    @DisplayName("产物可见性与下载地址一致")
    void content_hidesArtifactFromOtherTenantAndWorkspace() {
        TenantFixture fixture = tenantFixture();
        TenantFixture other = tenantFixture();
        UUID artifactId = seedArtifact(fixture.tenant(), fixture.adminWorkspace(), userId(fixture.admin()), "SAVED", 1);
        seedVersion(
                fixture.tenant(),
                artifactId,
                1,
                storageKey(fixture.tenant(), artifactId, 1, "md"),
                "text/markdown",
                16,
                userId(fixture.admin()));
        UUID deleted = seedArtifact(fixture.tenant(), fixture.adminWorkspace(), userId(fixture.admin()), "DELETED", 1);

        ApiTestClient.ApiResponse crossTenant =
                client.get(contentPath(artifactId, 1), other.admin().sessionCookie(), Map.of());
        assertThat(crossTenant.status()).isEqualTo(404);
        assertThat(client.json(crossTenant).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        ApiTestClient.ApiResponse otherWorkspace =
                client.get(contentPath(artifactId, 1), fixture.member().sessionCookie(), Map.of());
        assertThat(otherWorkspace.status()).isEqualTo(404);
        assertThat(client.json(otherWorkspace).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        assertThat(client.get(contentPath(deleted, 1), fixture.admin().sessionCookie(), Map.of())
                        .status())
                .isEqualTo(404);
        assertThat(client.get(contentPath(UUID.randomUUID(), 1), fixture.admin().sessionCookie(), Map.of())
                        .status())
                .isEqualTo(404);
    }

    /** 未认证请求返回 401，不泄露产物是否存在。 */
    @Test
    @DisplayName("未认证读取正文返回 401")
    void content_requiresAuthentication() {
        ApiTestClient.ApiResponse response = client.get(contentPath(UUID.randomUUID(), 1), null, Map.of());
        assertThat(response.status()).isEqualTo(401);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-AUTH-401");
    }

    /** 非法版本号 400，不存在版本 404，语义与版本详情一致。 */
    @Test
    @DisplayName("非法或不存在的版本")
    void content_validatesVersion() {
        TenantFixture fixture = tenantFixture();
        UUID artifactId = seedArtifact(fixture.tenant(), fixture.adminWorkspace(), userId(fixture.admin()), "SAVED", 1);
        seedVersion(
                fixture.tenant(),
                artifactId,
                1,
                storageKey(fixture.tenant(), artifactId, 1, "md"),
                "text/markdown",
                16,
                userId(fixture.admin()));

        ApiTestClient.ApiResponse zero =
                client.get(contentPath(artifactId, 0), fixture.admin().sessionCookie(), Map.of());
        assertThat(zero.status()).isEqualTo(400);
        assertThat(client.json(zero).path("code").asText()).isEqualTo("RICCON-REQUEST-400");

        ApiTestClient.ApiResponse missing =
                client.get(contentPath(artifactId, 9), fixture.admin().sessionCookie(), Map.of());
        assertThat(missing.status()).isEqualTo(404);
        assertThat(client.json(missing).path("code").asText()).isEqualTo("RICCON-TENANT-404");
    }

    /** 数据库元数据已超限时不发起对象读取，直接 413。 */
    @Test
    @DisplayName("元数据超过 2 MiB 返回 413")
    void content_rejectsOversizedMetadata() {
        TenantFixture fixture = tenantFixture();
        UUID artifactId = seedArtifact(fixture.tenant(), fixture.adminWorkspace(), userId(fixture.admin()), "SAVED", 1);
        seedVersion(
                fixture.tenant(),
                artifactId,
                1,
                storageKey(fixture.tenant(), artifactId, 1, "md"),
                "text/markdown",
                MAX_CONTENT_BYTES + 1L,
                userId(fixture.admin()));

        ApiTestClient.ApiResponse response =
                client.get(contentPath(artifactId, 1), fixture.admin().sessionCookie(), Map.of());

        assertThat(response.status()).isEqualTo(413);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-ARTIFACT-413");
        // 超限失败不写成功下载审计。
        assertThat(auditRows("artifact.downloaded").stream()
                        .filter(row -> artifactId.toString().equals(row.targetId()))
                        .toList())
                .isEmpty();
    }

    /** 元数据未超限但对象实际超过 2 MiB 时，由存储读取兜底返回 413。 */
    @Test
    @DisplayName("对象实际超过 2 MiB 返回 413")
    void content_rejectsOversizedObject() {
        TenantFixture fixture = tenantFixture();
        UUID artifactId = seedArtifact(fixture.tenant(), fixture.adminWorkspace(), userId(fixture.admin()), "SAVED", 1);
        byte[] oversized = new byte[MAX_CONTENT_BYTES + 1];
        Arrays.fill(oversized, (byte) 'a');
        String key = storageKey(fixture.tenant(), artifactId, 1, "md");
        seedVersion(fixture.tenant(), artifactId, 1, key, "text/markdown", 128, userId(fixture.admin()));
        putObject(key, "text/markdown", oversized);

        ApiTestClient.ApiResponse response =
                client.get(contentPath(artifactId, 1), fixture.admin().sessionCookie(), Map.of());

        assertThat(response.status()).isEqualTo(413);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-ARTIFACT-413");
        assertThat(response.body()).doesNotContain(key);
    }

    /** 2 MiB 边界允许读取。 */
    @Test
    @DisplayName("恰好 2 MiB 的正文可读取")
    void content_allowsExactlyTwoMiB() {
        TenantFixture fixture = tenantFixture();
        UUID artifactId = seedArtifact(fixture.tenant(), fixture.adminWorkspace(), userId(fixture.admin()), "SAVED", 1);
        byte[] body = new byte[MAX_CONTENT_BYTES];
        Arrays.fill(body, (byte) 'a');
        String key = storageKey(fixture.tenant(), artifactId, 1, "md");
        seedVersion(fixture.tenant(), artifactId, 1, key, "text/markdown", body.length, userId(fixture.admin()));
        putObject(key, "text/markdown", body);

        ApiTestClient.ApiResponse response =
                client.get(contentPath(artifactId, 1), fixture.admin().sessionCookie(), Map.of());

        assertThat(response.status()).as("status").isEqualTo(200);
        assertThat(response.header("Content-Length")).isEqualTo(Integer.toString(MAX_CONTENT_BYTES));
        assertThat(response.body()).hasSize(MAX_CONTENT_BYTES);
    }

    /** 对象缺失映射为稳定的 502，不泄露对象 key 或存储地址。 */
    @Test
    @DisplayName("对象缺失映射为 502")
    void content_mapsMissingObjectToStableError() {
        TenantFixture fixture = tenantFixture();
        UUID artifactId = seedArtifact(fixture.tenant(), fixture.adminWorkspace(), userId(fixture.admin()), "SAVED", 1);
        String key = storageKey(fixture.tenant(), artifactId, 1, "md");
        seedVersion(fixture.tenant(), artifactId, 1, key, "text/markdown", 16, userId(fixture.admin()));

        ApiTestClient.ApiResponse response =
                client.get(contentPath(artifactId, 1), fixture.admin().sessionCookie(), Map.of());

        assertThat(response.status()).isEqualTo(502);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-ARTIFACT-502");
        assertThat(response.body())
                .doesNotContain(key)
                .doesNotContain("tenants/")
                .doesNotContain("http");
    }

    /**
     * 生成产物对象 key，与服务端 Worker 的格式保持一致。
     *
     * @param tenant 租户代码
     * @param artifactId 产物 ID
     * @param version 版本号
     * @param extension 文件扩展名
     * @return 对象 key
     */
    private static String storageKey(String tenant, UUID artifactId, int version, String extension) {
        return "tenants/" + tenant + "/artifacts/" + artifactId + "/v" + version + "." + extension;
    }

    /**
     * 构造正文端点路径。
     *
     * @param artifactId 产物 ID
     * @param version 版本号
     * @return 请求路径
     */
    private static String contentPath(UUID artifactId, int version) {
        return "/api/v1/artifacts/" + artifactId + "/versions/" + version + "/content";
    }

    /**
     * 插入产物行，模拟 Worker 已保存报告元数据。
     *
     * @param tenant 租户代码
     * @param workspaceId 工作空间 ID
     * @param createdBy 创建人
     * @param status 产物状态
     * @param currentVersion 当前版本
     * @return 产物 ID
     */
    private UUID seedArtifact(String tenant, String workspaceId, String createdBy, String status, int currentVersion) {
        UUID artifactId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO artifact (
                            tenant_id, id, workspace_id, research_task_id, kind, title, status,
                            current_version, created_by, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, :workspaceId, NULL, 'REPORT', '正文测试报告', :status,
                            :currentVersion, :createdBy, NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(artifactId))
                .param("workspaceId", BinaryUuids.toBytes(UUID.fromString(workspaceId)))
                .param("status", status)
                .param("currentVersion", currentVersion)
                .param("createdBy", BinaryUuids.toBytes(UUID.fromString(createdBy)))
                .update();
        return artifactId;
    }

    /**
     * 插入产物版本元数据。
     *
     * @param tenant 租户代码
     * @param artifactId 产物 ID
     * @param version 版本号
     * @param storageKey 对象 key
     * @param contentType 正文 MIME
     * @param sizeBytes 正文大小
     * @param createdBy 生成人
     * @return 版本 ID
     */
    private UUID seedVersion(
            String tenant,
            UUID artifactId,
            int version,
            String storageKey,
            String contentType,
            long sizeBytes,
            String createdBy) {
        UUID versionId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO artifact_version (
                            tenant_id, id, artifact_id, version, storage_key, content_type,
                            size_bytes, sha256, summary, created_by, created_at
                        ) VALUES (
                            :tenantId, :id, :artifactId, :version, :storageKey, :contentType,
                            :sizeBytes, :sha256, '正文测试', :createdBy, NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(versionId))
                .param("artifactId", BinaryUuids.toBytes(artifactId))
                .param("version", version)
                .param("storageKey", storageKey)
                .param("contentType", contentType)
                .param("sizeBytes", sizeBytes)
                .param("sha256", "0123456789abcdef".repeat(4))
                .param("createdBy", BinaryUuids.toBytes(UUID.fromString(createdBy)))
                .update();
        return versionId;
    }

    /**
     * 把正文写入 RustFS 测试桶。
     *
     * @param key 对象 key
     * @param contentType 正文 MIME
     * @param content 正文
     */
    private void putObject(String key, String contentType, byte[] content) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET)
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(content));
    }
}
