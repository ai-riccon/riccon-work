package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.Sha256;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.FileApiTestSupport;
import com.riccon.api.support.TestFiles;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 文件生命周期集成测试。
 *
 * <p>覆盖列表/过滤、下载预签名 URL、解析失败重试、软删除审计、单会话 5 个上限与跨租户/跨用户隔离。
 */
class FileLifecycleApiTest extends FileApiTestSupport {

    /** 列表返回文件与最近解析状态，可按会话过滤。 */
    @Test
    @DisplayName("文件列表与会话过滤")
    void listFiles_returnsLatestParseAndFiltersByConversation() {
        TenantFixture fixture = tenantFixture();
        UUID conversationId = createConversation(fixture.tenant(), fixture.memberWorkspace(), userId(fixture.member()));
        upload(fixture.member(), fixture.memberWorkspace(), "one.pdf", "application/pdf", TestFiles.pdf(), null);
        upload(
                fixture.member(),
                fixture.memberWorkspace(),
                "two.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                TestFiles.docx(),
                null);
        JsonNode withConversation = upload(
                fixture.member(),
                fixture.memberWorkspace(),
                "three.pdf",
                "application/pdf",
                TestFiles.pdf(),
                conversationId.toString());

        ApiTestClient.ApiResponse all = client.get(
                "/api/v1/workspaces/" + fixture.memberWorkspace() + "/files?page=1&size=100",
                fixture.member().sessionCookie(),
                Map.of());
        assertThat(all.status()).as(all.body()).isEqualTo(200);
        JsonNode allBody = client.json(all);
        assertThat(allBody.path("total").asLong()).isEqualTo(3);
        assertThat(allBody.path("items").get(0).path("parse").path("status").asText())
                .isEqualTo("PENDING");
        assertThat(allBody.path("items").get(0).path("sha256").asText()).hasSize(64);

        ApiTestClient.ApiResponse filtered = client.get(
                "/api/v1/workspaces/" + fixture.memberWorkspace() + "/files?page=1&size=100&conversationId="
                        + conversationId,
                fixture.member().sessionCookie(),
                Map.of());
        assertThat(filtered.status()).as(filtered.body()).isEqualTo(200);
        JsonNode filteredBody = client.json(filtered);
        assertThat(filteredBody.path("total").asLong()).isEqualTo(1);
        assertThat(filteredBody.path("items").get(0).path("id").asText())
                .isEqualTo(withConversation.path("id").asText());

        ApiTestClient.ApiResponse get = client.get(
                "/api/v1/files/" + withConversation.path("id").asText(),
                fixture.member().sessionCookie(),
                Map.of());
        assertThat(get.status()).as(get.body()).isEqualTo(200);
        assertThat(client.json(get).path("conversationId").asText()).isEqualTo(conversationId.toString());

        ApiTestClient.ApiResponse adminList = client.get(
                "/api/v1/workspaces/" + fixture.memberWorkspace() + "/files",
                fixture.admin().sessionCookie(),
                Map.of());
        assertThat(adminList.status()).isEqualTo(404);
    }

    /** 预签名 URL 可真实下载对象，TTL 不超过 300 秒；跨租户不可获取。 */
    @Test
    @DisplayName("下载地址可用且跨租户 404")
    void downloadUrl_presignsObjectAndBlocksCrossTenant() throws Exception {
        TenantFixture fixture = tenantFixture();
        byte[] content = TestFiles.pdf();
        JsonNode file =
                upload(fixture.member(), fixture.memberWorkspace(), "download.pdf", "application/pdf", content, null);
        String fileId = file.path("id").asText();

        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/files/" + fileId + "/download-url", fixture.member().sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = client.json(response);
        String url = body.path("url").asText();
        assertThat(url).contains(fileId);
        Instant expiresAt = Instant.parse(body.path("expiresAt").asText());
        assertThat(expiresAt).isAfter(Instant.now().minusSeconds(1));
        assertThat(expiresAt).isBefore(Instant.now().plusSeconds(301));

        HttpResponse<byte[]> download = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(content);

        TenantFixture other = tenantFixture();
        ApiTestClient.ApiResponse crossTenant = client.get(
                "/api/v1/files/" + fileId + "/download-url", other.member().sessionCookie(), Map.of());
        assertThat(crossTenant.status()).isEqualTo(404);
        assertThat(client.json(crossTenant).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        ApiTestClient.ApiResponse otherUser = client.get(
                "/api/v1/files/" + fileId + "/download-url", fixture.admin().sessionCookie(), Map.of());
        assertThat(otherUser.status()).isEqualTo(404);
    }

    /** 失败的解析可以创建新尝试并重新投递 outbox；PENDING/SUCCEEDED 不可重试。 */
    @Test
    @DisplayName("解析重试创建新尝试并重新投递 outbox")
    void retryParse_failedFile_createsNewAttemptAndOutbox() throws Exception {
        TenantFixture fixture = tenantFixture();
        JsonNode file = upload(
                fixture.member(), fixture.memberWorkspace(), "broken.pdf", "application/pdf", TestFiles.pdf(), null);
        String fileId = file.path("id").asText();
        markParseFailed(fixture.tenant(), fileId);

        ApiTestClient.ApiResponse failed =
                client.get("/api/v1/files/" + fileId, fixture.member().sessionCookie(), Map.of());
        assertThat(client.json(failed).path("parse").path("status").asText()).isEqualTo("FAILED");
        assertThat(client.json(failed).path("parse").path("errorCode").asText()).isEqualTo("RICCON-FILE-422");

        ApiTestClient.ApiResponse retried = client.post(
                "/api/v1/files/" + fileId + "/parse-retries",
                "{}",
                fixture.member().sessionCookie(),
                fixture.member().csrfToken(),
                Map.of("Idempotency-Key", "retry-key-12345678"));
        assertThat(retried.status()).as(retried.body()).isEqualTo(202);
        JsonNode retriedBody = client.json(retried);
        assertThat(retriedBody.path("parse").path("attempt").asInt()).isEqualTo(2);
        assertThat(retriedBody.path("parse").path("status").asText()).isEqualTo("PENDING");
        String newParseId = retriedBody.path("parse").path("id").asText();

        Long attempts = jdbcClient
                .sql("SELECT COUNT(*) FROM file_parse WHERE tenant_id = :tenantId AND file_id = :fileId")
                .param("tenantId", fixture.tenant())
                .param("fileId", com.riccon.api.persistence.BinaryUuids.toBytes(UUID.fromString(fileId)))
                .query(Long.class)
                .single();
        assertThat(attempts).isEqualTo(2L);

        Map<String, Object> outbox = outboxForFile(fixture.tenant(), fileId);
        assertThat(outbox.get("event_type")).isEqualTo("riccon.file.parse.requested.v1");
        assertThat(outbox.get("idempotency_key")).isEqualTo("file:" + fileId + ":parse:" + newParseId);
        tools.jackson.databind.JsonNode payload =
                new tools.jackson.databind.ObjectMapper().readTree((String) outbox.get("payload"));
        assertThat(payload.path("parseId").asText()).isEqualTo(newParseId);
        assertThat(payload.path("attempt").asInt()).isEqualTo(2);

        ApiTestClient.ApiResponse repeated = client.post(
                "/api/v1/files/" + fileId + "/parse-retries",
                "{}",
                fixture.member().sessionCookie(),
                fixture.member().csrfToken(),
                Map.of("Idempotency-Key", "retry-key-12345678"));
        assertThat(repeated.status()).isEqualTo(409);
        assertThat(client.json(repeated).path("code").asText()).isEqualTo("RICCON-CONFLICT-409");

        markLatestParse(fixture.tenant(), fileId, "SUCCEEDED");
        ApiTestClient.ApiResponse succeeded = client.post(
                "/api/v1/files/" + fileId + "/parse-retries",
                "{}",
                fixture.member().sessionCookie(),
                fixture.member().csrfToken(),
                Map.of());
        assertThat(succeeded.status()).isEqualTo(409);

        ApiTestClient.ApiResponse shortKey = client.post(
                "/api/v1/files/" + fileId + "/parse-retries",
                "{}",
                fixture.member().sessionCookie(),
                fixture.member().csrfToken(),
                Map.of("Idempotency-Key", "short"));
        assertThat(shortKey.status()).isEqualTo(400);

        TenantFixture other = tenantFixture();
        ApiTestClient.ApiResponse crossTenant = client.post(
                "/api/v1/files/" + fileId + "/parse-retries",
                "{}",
                other.member().sessionCookie(),
                other.member().csrfToken(),
                Map.of());
        assertThat(crossTenant.status()).isEqualTo(404);
    }

    /** 删除是软删除并写审计；对象保留给维护任务清理。 */
    @Test
    @DisplayName("删除文件写审计、列表隐藏且对象保留")
    void deleteFile_softDeletesAndAudits() {
        TenantFixture fixture = tenantFixture();
        JsonNode file = upload(
                fixture.member(), fixture.memberWorkspace(), "delete.pdf", "application/pdf", TestFiles.pdf(), null);
        String fileId = file.path("id").asText();
        String storageKey = (String) fileRow(fixture.tenant(), fileId).get("storage_key");

        ApiTestClient.ApiResponse deleted = client.delete(
                "/api/v1/files/" + fileId,
                fixture.member().sessionCookie(),
                fixture.member().csrfToken());
        assertThat(deleted.status()).as(deleted.body()).isEqualTo(204);

        Map<String, Object> row = fileRow(fixture.tenant(), fileId);
        assertThat(row.get("status")).isEqualTo("DELETED");
        assertThat(row.get("deleted_at")).isNotNull();

        ApiTestClient.ApiResponse get =
                client.get("/api/v1/files/" + fileId, fixture.member().sessionCookie(), Map.of());
        assertThat(get.status()).isEqualTo(404);

        ApiTestClient.ApiResponse list = client.get(
                "/api/v1/workspaces/" + fixture.memberWorkspace() + "/files",
                fixture.member().sessionCookie(),
                Map.of());
        assertThat(client.json(list).path("total").asLong()).isZero();

        assertThat(auditRows("file.deleted"))
                .filteredOn(audit -> fileId.equals(audit.targetId()))
                .singleElement()
                .satisfies(audit -> {
                    assertThat(audit.tenantId()).isEqualTo(fixture.tenant());
                    assertThat(audit.outcome()).isEqualTo("SUCCESS");
                    assertThat(audit.details()).contains(fileId).contains(Sha256.hex(storageKey));
                });
        assertThat(objectExists(storageKey)).isTrue();

        ApiTestClient.ApiResponse again = client.delete(
                "/api/v1/files/" + fileId,
                fixture.member().sessionCookie(),
                fixture.member().csrfToken());
        assertThat(again.status()).isEqualTo(404);

        TenantFixture other = tenantFixture();
        ApiTestClient.ApiResponse crossTenant = client.delete(
                "/api/v1/files/" + fileId,
                other.member().sessionCookie(),
                other.member().csrfToken());
        assertThat(crossTenant.status()).isEqualTo(404);
    }

    /** 单会话最多 5 个文件；删除后可继续上传。 */
    @Test
    @DisplayName("单会话 5 个文件上限")
    void upload_conversationLimit_returnsConflict() {
        TenantFixture fixture = tenantFixture();
        UUID conversationId = createConversation(fixture.tenant(), fixture.memberWorkspace(), userId(fixture.member()));
        String firstId = null;
        for (int index = 0; index < 5; index++) {
            JsonNode file = upload(
                    fixture.member(),
                    fixture.memberWorkspace(),
                    "file-" + index + ".pdf",
                    "application/pdf",
                    TestFiles.pdf(),
                    conversationId.toString());
            if (index == 0) {
                firstId = file.path("id").asText();
            }
        }
        ApiTestClient.ApiResponse sixth = uploadRaw(
                fixture.member(),
                fixture.memberWorkspace(),
                "sixth.pdf",
                "application/pdf",
                TestFiles.pdf(),
                conversationId.toString());
        assertThat(sixth.status()).isEqualTo(409);
        assertThat(client.json(sixth).path("code").asText()).isEqualTo("RICCON-FILE-409");

        assertThat(firstId).isNotNull();
        ApiTestClient.ApiResponse deleted = client.delete(
                "/api/v1/files/" + firstId,
                fixture.member().sessionCookie(),
                fixture.member().csrfToken());
        assertThat(deleted.status()).isEqualTo(204);

        upload(
                fixture.member(),
                fixture.memberWorkspace(),
                "sixth.pdf",
                "application/pdf",
                TestFiles.pdf(),
                conversationId.toString());
    }

    /** 上传时引用其他工作空间的会话返回 404。 */
    @Test
    @DisplayName("会话不属于工作空间时返回 404")
    void upload_conversationFromOtherWorkspace_returnsNotFound() {
        TenantFixture fixture = tenantFixture();
        JsonNode team = createTeam(fixture.admin(), "另一个空间");
        UUID conversationId = createConversation(fixture.tenant(), fixture.memberWorkspace(), userId(fixture.member()));
        ApiTestClient.ApiResponse response = uploadRaw(
                fixture.member(),
                team.path("id").asText(),
                "file.pdf",
                "application/pdf",
                TestFiles.pdf(),
                conversationId.toString());
        assertThat(response.status()).isEqualTo(404);
    }

    /**
     * 将文件最近一次解析标记为 FAILED。
     *
     * @param tenant 租户
     * @param fileId 文件 ID
     */
    private void markParseFailed(String tenant, String fileId) {
        jdbcClient
                .sql("""
                        UPDATE file_parse
                           SET status = 'FAILED', error_code = 'RICCON-FILE-422',
                               error_message = 'file has no extractable text', updated_at = NOW(6)
                         WHERE tenant_id = :tenantId AND file_id = :fileId
                        """)
                .param("tenantId", tenant)
                .param("fileId", com.riccon.api.persistence.BinaryUuids.toBytes(UUID.fromString(fileId)))
                .update();
    }

    /**
     * 将文件最近一次解析标记为指定状态。
     *
     * @param tenant 租户
     * @param fileId 文件 ID
     * @param status 目标状态
     */
    private void markLatestParse(String tenant, String fileId, String status) {
        jdbcClient
                .sql("""
                        UPDATE file_parse
                           SET status = :status, updated_at = NOW(6)
                         WHERE tenant_id = :tenantId
                           AND file_id = :fileId
                           AND attempt = (
                               SELECT max_attempt FROM (
                                   SELECT MAX(attempt) AS max_attempt FROM file_parse
                                    WHERE tenant_id = :tenantId AND file_id = :fileId
                               ) latest
                           )
                        """)
                .param("tenantId", tenant)
                .param("fileId", com.riccon.api.persistence.BinaryUuids.toBytes(UUID.fromString(fileId)))
                .param("status", status)
                .update();
    }
}
