package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.Sha256;
import com.riccon.api.persistence.BinaryUuids;
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
 * 知识库文档集成测试。
 *
 * <p>覆盖从工作空间文件登记、重复登记收敛、KNOWLEDGE_INDEX outbox、列表/状态过滤、读取与跨租户隔离。
 */
class KnowledgeApiTest extends FileApiTestSupport {

    /** JSON 解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 登记知识库文档写入元数据、运行与 outbox。 */
    @Test
    @DisplayName("文件登记为知识库文档并投递索引事件")
    void createDocument_registersMetadataAndOutbox() throws Exception {
        TenantFixture fixture = tenantFixture();
        byte[] content = TestFiles.pdf();
        JsonNode file =
                upload(fixture.admin(), fixture.adminWorkspace(), "knowledge.pdf", "application/pdf", content, null);
        String fileId = file.path("id").asText();

        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/knowledge/documents",
                client.jsonBody("title", "市场调研资料", "fileId", fileId),
                fixture.admin().sessionCookie(),
                fixture.admin().csrfToken(),
                Map.of("Idempotency-Key", "knowledge-key-12345678"));
        assertThat(response.status()).as(response.body()).isEqualTo(202);
        JsonNode document = client.json(response);
        String documentId = document.path("id").asText();
        assertThat(document.path("title").asText()).isEqualTo("市场调研资料");
        assertThat(document.path("sourceType").asText()).isEqualTo("UPLOAD");
        assertThat(document.path("fileId").asText()).isEqualTo(fileId);
        assertThat(document.path("status").asText()).isEqualTo("PENDING");
        assertThat(document.path("checksum").asText()).isEqualTo(Sha256.hex(content));
        assertThat(document.path("mimeType").asText()).isEqualTo("application/pdf");
        assertThat(document.path("sizeBytes").asLong()).isEqualTo(content.length);
        assertThat(document.path("errorCode").isNull()).isTrue();
        assertThat(document.path("indexedAt").isNull()).isTrue();

        Map<String, Object> row = knowledgeRow(fixture.tenant(), documentId);
        assertThat(row.get("title")).isEqualTo("市场调研资料");
        assertThat(row.get("file_id")).isEqualTo(UUID.fromString(fileId));
        assertThat(row.get("checksum")).isEqualTo(Sha256.hex(content));
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("created_by")).isEqualTo(UUID.fromString(userId(fixture.admin())));

        Map<String, Object> outbox = knowledgeOutbox(fixture.tenant(), documentId);
        assertThat(outbox.get("event_type")).isEqualTo("riccon.knowledge.index.requested.v1");
        assertThat(outbox.get("event_version")).isEqualTo(1);
        assertThat(outbox.get("kind")).isEqualTo("KNOWLEDGE_INDEX");
        assertThat(outbox.get("idempotency_key")).isEqualTo("doc:" + documentId + ":index:1");
        JsonNode payload = JSON.readTree((String) outbox.get("payload"));
        assertThat(payload.path("documentId").asText()).isEqualTo(documentId);
        assertThat(payload.path("fileId").asText()).isEqualTo(fileId);
        assertThat(payload.path("attempt").asInt()).isEqualTo(1);
    }

    /** 同一来源文件重复登记返回既有文档，不重复投递索引任务。 */
    @Test
    @DisplayName("同一文件重复登记收敛到既有文档")
    void createDocument_duplicateFile_returnsExistingDocument() {
        TenantFixture fixture = tenantFixture();
        JsonNode file = upload(
                fixture.admin(), fixture.adminWorkspace(), "duplicate.pdf", "application/pdf", TestFiles.pdf(), null);
        String fileId = file.path("id").asText();

        JsonNode first = createDocument(fixture.admin(), "第一次登记", fileId);
        JsonNode second = createDocument(fixture.admin(), "第二次登记", fileId);
        assertThat(second.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(second.path("title").asText()).isEqualTo("第一次登记");

        Long count = jdbcClient
                .sql("""
                        SELECT COUNT(*) FROM agent_run_outbox
                         WHERE tenant_id = :tenantId AND idempotency_key = :key
                        """)
                .param("tenantId", fixture.tenant())
                .param("key", "doc:" + first.path("id").asText() + ":index:1")
                .query(Long.class)
                .single();
        assertThat(count).isEqualTo(1L);
    }

    /** 普通员工可以读取但无 knowledge.manage，不能登记文档。 */
    @Test
    @DisplayName("普通员工登记文档返回 403")
    void createDocument_member_isForbidden() {
        TenantFixture fixture = tenantFixture();
        JsonNode file = upload(
                fixture.admin(), fixture.adminWorkspace(), "member.pdf", "application/pdf", TestFiles.pdf(), null);
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/knowledge/documents",
                client.jsonBody("title", "无权限", "fileId", file.path("id").asText()),
                fixture.member().sessionCookie(),
                fixture.member().csrfToken(),
                Map.of());
        assertThat(response.status()).isEqualTo(403);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-AUTH-403");
    }

    /** 列表与读取按租户隔离，状态过滤只返回匹配文档。 */
    @Test
    @DisplayName("知识库列表、状态过滤与读取")
    void listAndGetDocuments_areTenantScoped() {
        TenantFixture fixture = tenantFixture();
        JsonNode file =
                upload(fixture.admin(), fixture.adminWorkspace(), "list.pdf", "application/pdf", TestFiles.pdf(), null);
        JsonNode document =
                createDocument(fixture.admin(), "列表文档", file.path("id").asText());
        String documentId = document.path("id").asText();

        ApiTestClient.ApiResponse memberList = client.get(
                "/api/v1/knowledge/documents?page=1&size=100", fixture.member().sessionCookie(), Map.of());
        assertThat(memberList.status()).as(memberList.body()).isEqualTo(200);
        assertThat(client.json(memberList).path("items").toString()).contains(documentId);

        ApiTestClient.ApiResponse pending = client.get(
                "/api/v1/knowledge/documents?page=1&size=100&status=PENDING",
                fixture.member().sessionCookie(),
                Map.of());
        assertThat(client.json(pending).path("total").asLong()).isEqualTo(1);

        ApiTestClient.ApiResponse indexed = client.get(
                "/api/v1/knowledge/documents?page=1&size=100&status=INDEXED",
                fixture.member().sessionCookie(),
                Map.of());
        assertThat(client.json(indexed).path("total").asLong()).isZero();

        ApiTestClient.ApiResponse get = client.get(
                "/api/v1/knowledge/documents/" + documentId, fixture.member().sessionCookie(), Map.of());
        assertThat(get.status()).as(get.body()).isEqualTo(200);
        assertThat(client.json(get).path("title").asText()).isEqualTo("列表文档");

        TenantFixture other = tenantFixture();
        ApiTestClient.ApiResponse crossTenant = client.get(
                "/api/v1/knowledge/documents/" + documentId, other.member().sessionCookie(), Map.of());
        assertThat(crossTenant.status()).isEqualTo(404);
        assertThat(client.json(crossTenant).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        ApiTestClient.ApiResponse otherList = client.get(
                "/api/v1/knowledge/documents?page=1&size=100", other.member().sessionCookie(), Map.of());
        assertThat(client.json(otherList).path("items").toString()).doesNotContain(documentId);
    }

    /** 来源文件不可见或参数不合法时拒绝登记。 */
    @Test
    @DisplayName("不可见文件与非法参数")
    void createDocument_invalidFileOrPayload_returnsExpectedErrors() {
        TenantFixture fixtureA = tenantFixture();
        TenantFixture fixtureB = tenantFixture();
        JsonNode fileB = upload(
                fixtureB.admin(), fixtureB.adminWorkspace(), "other.pdf", "application/pdf", TestFiles.pdf(), null);

        ApiTestClient.ApiResponse crossTenant = client.post(
                "/api/v1/knowledge/documents",
                client.jsonBody("title", "跨租户", "fileId", fileB.path("id").asText()),
                fixtureA.admin().sessionCookie(),
                fixtureA.admin().csrfToken(),
                Map.of());
        assertThat(crossTenant.status()).isEqualTo(404);

        ApiTestClient.ApiResponse blankTitle = client.post(
                "/api/v1/knowledge/documents",
                client.jsonBody("title", "  ", "fileId", fileB.path("id").asText()),
                fixtureB.admin().sessionCookie(),
                fixtureB.admin().csrfToken(),
                Map.of());
        assertThat(blankTitle.status()).isEqualTo(400);

        ApiTestClient.ApiResponse missingFile = client.post(
                "/api/v1/knowledge/documents",
                client.jsonBody("title", "缺少文件"),
                fixtureB.admin().sessionCookie(),
                fixtureB.admin().csrfToken(),
                Map.of());
        assertThat(missingFile.status()).isEqualTo(400);

        ApiTestClient.ApiResponse unknownFile = client.post(
                "/api/v1/knowledge/documents",
                client.jsonBody("title", "不存在", "fileId", UUID.randomUUID().toString()),
                fixtureB.admin().sessionCookie(),
                fixtureB.admin().csrfToken(),
                Map.of());
        assertThat(unknownFile.status()).isEqualTo(404);

        ApiTestClient.ApiResponse invalidStatus = client.get(
                "/api/v1/knowledge/documents?status=UNKNOWN", fixtureB.admin().sessionCookie(), Map.of());
        assertThat(invalidStatus.status()).isEqualTo(400);

        ApiTestClient.ApiResponse unknownDocument = client.get(
                "/api/v1/knowledge/documents/" + UUID.randomUUID(),
                fixtureB.admin().sessionCookie(),
                Map.of());
        assertThat(unknownDocument.status()).isEqualTo(404);
    }

    /**
     * 创建知识库文档并断言 202。
     *
     * @param session 租户管理员会话
     * @param title 标题
     * @param fileId 文件 ID
     * @return 文档 JSON
     */
    private JsonNode createDocument(LoginSession session, String title, String fileId) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/knowledge/documents",
                client.jsonBody("title", title, "fileId", fileId),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of("Idempotency-Key", "knowledge-key-12345678"));
        assertThat(response.status()).as(response.body()).isEqualTo(202);
        return client.json(response);
    }

    /**
     * 查询知识库文档行。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 列映射
     */
    private Map<String, Object> knowledgeRow(String tenant, String documentId) {
        return jdbcClient
                .sql("""
                        SELECT title, file_id, checksum, mime_type, size_bytes, status, created_by
                          FROM knowledge_document
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(UUID.fromString(documentId)))
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("title", rs.getString("title"));
                    row.put("file_id", BinaryUuids.fromBytes(rs.getBytes("file_id")));
                    row.put("checksum", rs.getString("checksum"));
                    row.put("mime_type", rs.getString("mime_type"));
                    row.put("size_bytes", rs.getLong("size_bytes"));
                    row.put("status", rs.getString("status"));
                    row.put("created_by", BinaryUuids.fromBytes(rs.getBytes("created_by")));
                    return row;
                })
                .single();
    }

    /**
     * 查询知识库 outbox 与运行。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 列映射
     */
    private Map<String, Object> knowledgeOutbox(String tenant, String documentId) {
        return jdbcClient
                .sql("""
                        SELECT o.event_type, o.event_version, o.idempotency_key, o.payload,
                               o.status AS outbox_status, r.kind, r.status AS run_status
                          FROM agent_run_outbox o
                          JOIN agent_run r ON r.tenant_id = o.tenant_id AND r.id = o.run_id
                         WHERE o.tenant_id = :tenantId
                           AND o.idempotency_key = :key
                        """)
                .param("tenantId", tenant)
                .param("key", "doc:" + documentId + ":index:1")
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("event_type", rs.getString("event_type"));
                    row.put("event_version", rs.getInt("event_version"));
                    row.put("idempotency_key", rs.getString("idempotency_key"));
                    row.put("payload", rs.getString("payload"));
                    row.put("outbox_status", rs.getString("outbox_status"));
                    row.put("kind", rs.getString("kind"));
                    row.put("run_status", rs.getString("run_status"));
                    return row;
                })
                .single();
    }
}
