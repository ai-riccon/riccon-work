package com.riccon.api.knowledge.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.knowledge.connector.ConnectorApiTestSupport.ConnectorFixture;
import com.riccon.api.knowledge.connector.ConnectorApiTestSupport.Credential;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.domain.audit.AuditActions;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * 连接器同步文档的跨租户/跨部门隔离与 U8 管理性读取（TEN-037、SEC-CONN-004、M05-AC-09 M05b 段）。
 *
 * <p>连接器文档仍走 M05 文档 ACL；本类只补齐 M05b 特有的对象：DOC 由连接器同步产生、带完整
 * {@code externalId} 与固定标题，跨租户/跨部门访问不得通过列表/详情泄露这些值。租户管理员绕过 ACL
 * 读取时必须写 {@code knowledge.document.admin_read}，且审计不含标题或 externalId。
 */
@Tag("tenant-isolation")
@TestPropertySource(properties = "riccon.kb.acl.enforcement-enabled=true")
class ConnectorTenantIsolationVerifyTest extends ConnectorApiTestSupport {

    /** 文档标题 canary；用于断言跨租户响应与审计不泄露。 */
    private static final String TITLE_CANARY = "V2B-TEN037-TITLE-CANARY";

    /** 完整 externalId canary；用于断言只显示末段/不出现于跨租户响应。 */
    private static final String EXTERNAL_ID_CANARY = "v2b/ten037/external-canary.pdf";

    @Test
    @DisplayName("TEN-037：连接器文档跨租户/跨部门列表与详情 404，U8 管理读取写审计")
    void ten037_connectorDocumentCrossTenantAndDepartmentIsolation() {
        ConnectorFixture owner = connectorFixture();
        ConnectorFixture foreign = connectorFixture();
        String ownerConnector = createConnectorId(owner);
        String ownerDepartment = createDepartment(owner, "V2B-DEPT-A");
        String foreignDepartment = createDepartment(foreign, "V2B-DEPT-B");
        LoginSession ownerDeptMember = seedMember(owner, "v2bowner");
        LoginSession ownerCreator = seedMember(owner, "v2bcreator");
        LoginSession foreignDeptMember = seedMember(foreign, "v2bforeign");
        addDepartmentMember(owner, ownerDepartment, actorId(ownerDeptMember));
        addDepartmentMember(owner, ownerDepartment, actorId(ownerCreator));
        addDepartmentMember(foreign, foreignDepartment, actorId(foreignDeptMember));

        UUID documentId = insertConnectorDocument(
                owner.tenant(),
                ownerConnector,
                TITLE_CANARY,
                EXTERNAL_ID_CANARY,
                "DEPT",
                UUID.fromString(ownerDepartment),
                actorId(ownerCreator));

        // 同部门可读；创建人之外的同租户管理员按 U8 管理性读取也能读，并写审计。
        assertThat(detail(ownerDeptMember, documentId).status()).isEqualTo(200);
        assertThat(detail(owner.admin(), documentId).status()).isEqualTo(200);
        List<AuditRow> adminReads = adminReads(owner.tenant(), actorId(owner.admin()));
        assertThat(adminReads).hasSize(1);
        JsonNode adminDetails = JSON.readTree(adminReads.get(0).details());
        assertThat(adminDetails.path("scope").asText()).isEqualTo("DETAIL");
        assertThat(adminDetails.path("visibility").asText()).isEqualTo("DEPT");
        assertThat(adminReads.get(0).details()).doesNotContain(TITLE_CANARY).doesNotContain(EXTERNAL_ID_CANARY);

        // 跨部门成员与跨租户管理员都不能通过详情探测到文档、标题或 externalId。
        assertNotFound(detail(foreignDeptMember, documentId), TITLE_CANARY, EXTERNAL_ID_CANARY);
        assertNotFound(detail(foreign.admin(), documentId), TITLE_CANARY, EXTERNAL_ID_CANARY);

        // 列表同样不出现该文档；跨租户管理员列表不泄露 ID、标题或 externalId。
        JsonNode foreignList = listDocuments(foreign.admin());
        assertThat(foreignList.path("items").toString())
                .doesNotContain(documentId.toString())
                .doesNotContain(TITLE_CANARY)
                .doesNotContain(EXTERNAL_ID_CANARY);
        JsonNode ownerList = listDocuments(ownerDeptMember);
        assertThat(ownerList.path("items").toString()).contains(documentId.toString());

        // ACL 从 DEPT 收窄为 USERS 后，原先同部门的成员下一次请求立即 404；管理员仍可 U8 读取并再写审计。
        jdbcClient
                .sql("""
                        UPDATE knowledge_document
                           SET visibility = 'USERS', department_id = NULL, acl_version = acl_version + 1
                         WHERE tenant_id = :tenant AND id = :id
                        """)
                .param("tenant", owner.tenant())
                .param("id", BinaryUuids.toBytes(documentId))
                .update();
        assertNotFound(detail(ownerDeptMember, documentId), TITLE_CANARY, EXTERNAL_ID_CANARY);
        assertThat(detail(owner.admin(), documentId).status()).isEqualTo(200);
        assertThat(adminReads(owner.tenant(), actorId(owner.admin()))).hasSize(2);

        // 列表详情响应只显示 externalId 末段，不返回完整 key。
        JsonNode ownerDetail = client.json(detail(owner.admin(), documentId));
        assertThat(ownerDetail.path("externalId").asText()).isEqualTo("ten037/external-canary.pdf");
        assertThat(ownerDetail.path("sourceType").asText()).isEqualTo("CONNECTOR");
        assertThat(ownerDetail.path("retentionDays").asInt()).isEqualTo(180);
        assertThat(ownerDetail.path("retentionPolicy").asText()).isEqualTo("DEPLOYMENT_DEFAULT");
        assertThat(ownerDetail.path("retentionExpiresAt").isNull()).isFalse();
        assertThat(ownerDetail.toString()).doesNotContain(EXTERNAL_ID_CANARY);
    }

    /**
     * TEN-037 套件完整性清单：M05b 段必须有实际测试方法，且运行零 skip。
     */
    @Test
    @DisplayName("TEN-037 套件完整性清单")
    void ten037SuiteIntegrityManifest() {
        List<String> methodNames = Arrays.stream(getClass().getDeclaredMethods())
                .map(Method::getName)
                .toList();
        assertThat(methodNames.stream().anyMatch(name -> name.startsWith("ten037_")))
                .as("missing test method for TEN-037")
                .isTrue();
        assertThat(methodNames.stream().anyMatch(name -> name.startsWith("secConn004_")))
                .as("missing test method for SEC-CONN-004")
                .isTrue();
        System.out.println("SUITE-MANIFEST TEN-037 SEC-CONN-004");
    }

    /**
     * SEC-CONN-004：同租户普通员工访问 8 个连接器端点全部 403，不泄露配置或凭据。
     */
    @Test
    @DisplayName("SEC-CONN-004：普通员工 8 个连接器端点全部 403")
    void secConn004_memberForbiddenOnAllConnectorEndpoints() {
        ConnectorFixture fixture = connectorFixture();
        String connectorId = createConnectorId(fixture);
        LoginSession member = fixture.member();

        assertForbidden(client.get("/api/v1/knowledge/connectors", member.sessionCookie(), Map.of()));
        assertForbidden(client.post(
                "/api/v1/knowledge/connectors",
                client.jsonBody(
                        "name",
                        "member-" + UUID.randomUUID().toString().substring(0, 8),
                        "type",
                        "S3",
                        "endpointId",
                        ENDPOINT_ID,
                        "config",
                        Map.of("bucket", BUCKET),
                        "defaultVisibility",
                        "TENANT"),
                member.sessionCookie(),
                member.csrfToken(),
                Map.of("Idempotency-Key", "member-create-" + UUID.randomUUID())));
        assertForbidden(client.patch(
                "/api/v1/knowledge/connectors/" + connectorId,
                "{\"name\":\"member-rename\"}",
                member.sessionCookie(),
                member.csrfToken()));
        assertForbidden(client.delete(
                "/api/v1/knowledge/connectors/" + connectorId, member.sessionCookie(), member.csrfToken()));
        assertForbidden(client.put(
                "/api/v1/knowledge/connectors/" + connectorId + "/credential",
                client.jsonBody("accessKeyId", "member-access", "secretAccessKey", "member-secret"),
                member.sessionCookie(),
                member.csrfToken()));
        assertForbidden(client.post(
                "/api/v1/knowledge/connectors/" + connectorId + "/test",
                "",
                member.sessionCookie(),
                member.csrfToken(),
                Map.of()));
        assertForbidden(client.post(
                "/api/v1/knowledge/connectors/" + connectorId + "/sync",
                "",
                member.sessionCookie(),
                member.csrfToken(),
                Map.of("Idempotency-Key", "member-sync-" + UUID.randomUUID())));
        assertForbidden(
                client.get("/api/v1/knowledge/connectors/" + connectorId + "/items", member.sessionCookie(), Map.of()));

        // 跨租户管理员持所有者 connectorId 写凭据：统一 404，不泄露连接器存在性。
        ConnectorFixture foreign = connectorFixture();
        assertNotFound(putCredential(foreign.admin(), connectorId, Credential.valid()));

        // 普通员工的失败请求不能产生连接器审计或同步事件。
        String tenant = fixture.tenant();
        assertThat(auditCount(tenant, "knowledge.connector.created")).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                                SELECT COUNT(*) FROM agent_run_outbox
                                 WHERE tenant_id = :tenant
                                   AND event_type = 'riccon.knowledge.connector.sync.requested.v1'
                                """).param("tenant", tenant).query(Long.class).single())
                .isZero();
    }

    /**
     * 统计指定租户的连接器动作审计。
     *
     * @param tenant 租户
     * @param action 动作
     * @return 条数
     */
    private long auditCount(String tenant, String action) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM audit_event WHERE tenant_id = :tenant AND action = :action")
                .param("tenant", tenant)
                .param("action", action)
                .query(Long.class)
                .single();
    }

    /**
     * 断言 403 AUTH 且响应不含会员输入。
     *
     * @param response 响应
     */
    private static void assertForbidden(ApiTestClient.ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(403);
        assertThat(response.body()).contains("RICCON-AUTH-403");
    }

    /**
     * 创建连接器并返回 ID。
     *
     * @param fixture 租户夹具
     * @return 连接器 ID
     */
    private String createConnectorId(ConnectorFixture fixture) {
        return json(
                        createConnector(
                                fixture.admin(),
                                "v2b-ten-" + UUID.randomUUID().toString().substring(0, 8),
                                "TENANT",
                                null,
                                false,
                                null),
                        201)
                .path("id")
                .asText();
    }

    /**
     * 创建部门。
     *
     * @param fixture 租户夹具
     * @param name 部门名
     * @return 部门 ID
     */
    private String createDepartment(ConnectorFixture fixture, String name) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/admin/tenants/" + fixture.tenant() + "/departments",
                client.jsonBody("name", name),
                fixture.platform().sessionCookie(),
                fixture.platform().csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response).path("id").asText();
    }

    /**
     * 把成员加入部门。
     *
     * @param fixture 租户夹具
     * @param departmentId 部门 ID
     * @param userId 用户 ID
     */
    private void addDepartmentMember(ConnectorFixture fixture, String departmentId, UUID userId) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/admin/tenants/" + fixture.tenant() + "/departments/" + departmentId + "/members",
                client.jsonBody("userId", userId.toString()),
                fixture.platform().sessionCookie(),
                fixture.platform().csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
    }

    /**
     * 创建并登录一个普通员工。
     *
     * @param fixture 租户夹具
     * @param prefix 登录名前缀
     * @return 登录会话
     */
    private LoginSession seedMember(ConnectorFixture fixture, String prefix) {
        return seedAndLoginLocalUser(
                fixture.platform(),
                fixture.tenant(),
                prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                "member-password-12",
                List.of("MEMBER"));
    }

    /**
     * 插入一个带完整 externalId 的连接器文档。
     *
     * @param tenant 租户
     * @param connectorId 连接器 ID
     * @param title 标题
     * @param externalId 完整源对象 key
     * @param visibility 可见范围
     * @param departmentId 部门（DEPT 时必填）
     * @param createdBy 创建人
     * @return 文档 ID
     */
    private UUID insertConnectorDocument(
            String tenant,
            String connectorId,
            String title,
            String externalId,
            String visibility,
            UUID departmentId,
            UUID createdBy) {
        UUID documentId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_document (
                            tenant_id, id, title, source_type, file_id, connector_id, external_id, checksum,
                            mime_type, size_bytes, status, created_by, created_at, updated_at, visibility,
                            department_id, parse_engine, acl_version, retention_expires_at
                        ) VALUES (
                            :tenant, :id, :title, 'CONNECTOR', NULL, :connectorId, :externalId, :checksum,
                            'application/pdf', 4, 'INDEXED', :createdBy, NOW(6), NOW(6), :visibility,
                            :departmentId, 'NONE', 1, DATE_ADD(NOW(6), INTERVAL 180 DAY)
                        )
                        """)
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(documentId))
                .param("title", title)
                .param("connectorId", BinaryUuids.toBytes(UUID.fromString(connectorId)))
                .param("externalId", externalId)
                .param("checksum", "a".repeat(64))
                .param("createdBy", BinaryUuids.toBytes(createdBy))
                .param("visibility", visibility)
                .param("departmentId", departmentId == null ? null : BinaryUuids.toBytes(departmentId))
                .update();
        return documentId;
    }

    /**
     * 读取文档详情。
     *
     * @param session 会话
     * @param documentId 文档 ID
     * @return HTTP 响应
     */
    private ApiTestClient.ApiResponse detail(LoginSession session, UUID documentId) {
        return client.get("/api/v1/knowledge/documents/" + documentId, session.sessionCookie(), Map.of());
    }

    /**
     * 读取知识库列表。
     *
     * @param session 会话
     * @return JSON
     */
    private JsonNode listDocuments(LoginSession session) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/knowledge/documents?page=1&size=100", session.sessionCookie(), Map.of());
        return json(response, 200);
    }

    /**
     * 断言 404 且响应不泄露标题/externalId。
     *
     * @param response 响应
     * @param forbidden 禁止出现的值
     */
    private static void assertNotFound(ApiTestClient.ApiResponse response, String... forbidden) {
        assertThat(response.status()).as(response.body()).isEqualTo(404);
        assertThat(response.body()).contains("RICCON-TENANT-404");
        for (String value : forbidden) {
            assertThat(response.body()).doesNotContain(value);
        }
    }

    /**
     * 读取指定租户/操作者的 admin_read 审计。
     *
     * @param tenant 租户
     * @param actorId 操作者
     * @return 审计行
     */
    private List<AuditRow> adminReads(String tenant, UUID actorId) {
        return auditRows(AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ).stream()
                .filter(row -> tenant.equals(row.tenantId()))
                .filter(row -> actorId.toString().equals(row.actorId()))
                .toList();
    }
}
