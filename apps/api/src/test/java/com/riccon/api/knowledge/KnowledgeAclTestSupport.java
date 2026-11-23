package com.riccon.api.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.FileApiTestSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M05 知识库 ACL/下载/删除测试基座。
 *
 * <p>复用真实 MySQL 与 RustFS 容器；每个用例使用唯一租户隔离数据。部门与成员通过平台管理端点创建，
 * 登录、CSRF 与权限仍走真实链路。所有子类显式开启 {@code riccon.kb.acl.enforcement-enabled=true}。
 */
abstract class KnowledgeAclTestSupport extends FileApiTestSupport {

    /** 测试密码（仅测试占位）。 */
    protected static final String TEST_PASSWORD = "member-password-12";

    /** JSON 序列化。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 通过平台管理员创建部门。
     *
     * @param platform 平台管理员会话
     * @param tenant 租户代码
     * @param name 部门名
     * @return 部门 ID
     */
    protected UUID createDepartment(LoginSession platform, String tenant, String name) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/admin/tenants/" + tenant + "/departments",
                client.jsonBody("name", name),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return UUID.fromString(client.json(response).path("id").asText());
    }

    /**
     * 停用部门。
     *
     * @param platform 平台管理员会话
     * @param tenant 租户代码
     * @param departmentId 部门 ID
     */
    protected void disableDepartment(LoginSession platform, String tenant, UUID departmentId) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/admin/tenants/" + tenant + "/departments/" + departmentId + "/disable",
                client.jsonBody(),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
    }

    /**
     * 把账号加入部门。
     *
     * @param platform 平台管理员会话
     * @param tenant 租户代码
     * @param departmentId 部门 ID
     * @param userId 账号 ID
     */
    protected void addDepartmentMember(LoginSession platform, String tenant, UUID departmentId, UUID userId) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/admin/tenants/" + tenant + "/departments/" + departmentId + "/members",
                client.jsonBody("userId", userId.toString()),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
    }

    /**
     * 把账号移出部门。
     *
     * @param platform 平台管理员会话
     * @param tenant 租户代码
     * @param departmentId 部门 ID
     * @param userId 账号 ID
     */
    protected void removeDepartmentMember(LoginSession platform, String tenant, UUID departmentId, UUID userId) {
        ApiTestClient.ApiResponse response = client.delete(
                "/api/v1/admin/tenants/" + tenant + "/departments/" + departmentId + "/members/" + userId,
                platform.sessionCookie(),
                platform.csrfToken());
        assertThat(response.status()).as(response.body()).isEqualTo(204);
    }

    /**
     * 种子并登录一个指定角色的账号。
     *
     * @param platform 平台管理员会话
     * @param tenant 租户代码
     * @param prefix 登录名前缀
     * @param roles 角色
     * @return 登录会话
     */
    protected LoginSession seedLogin(LoginSession platform, String tenant, String prefix, List<String> roles) {
        String username = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        seedLocalUser(tenant, username, TEST_PASSWORD, roles, actorId(platform));
        return login(tenant, username, TEST_PASSWORD);
    }

    /**
     * 上传 PDF 并登记知识库文档。
     *
     * @param fixture 租户夹具
     * @param title 标题
     * @param visibility 可见范围；为空表示 TENANT
     * @param departmentId 部门
     * @param userIds 用户名单
     * @return 文档 JSON
     */
    protected JsonNode createDocument(
            TenantFixture fixture, String title, String visibility, UUID departmentId, List<UUID> userIds) {
        return createDocumentAs(fixture.admin(), fixture.adminWorkspace(), title, visibility, departmentId, userIds);
    }

    /**
     * 由指定管理员上传并登记文档。
     *
     * @param creator 创建人会话（须有 {@code knowledge.manage}）
     * @param workspaceId 创建人个人空间
     * @param title 标题
     * @param visibility 可见范围
     * @param departmentId 部门
     * @param userIds 用户名单
     * @return 文档 JSON
     */
    protected JsonNode createDocumentAs(
            LoginSession creator,
            String workspaceId,
            String title,
            String visibility,
            UUID departmentId,
            List<UUID> userIds) {
        JsonNode file = upload(
                creator, workspaceId, title + ".pdf", "application/pdf", com.riccon.api.support.TestFiles.pdf(), null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("fileId", file.path("id").asText());
        if (visibility != null) {
            body.put("visibility", visibility);
        }
        if (departmentId != null) {
            body.put("departmentId", departmentId.toString());
        }
        if (userIds != null) {
            body.put("userIds", userIds.stream().map(UUID::toString).toList());
        }
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/knowledge/documents",
                JSON.writeValueAsString(body),
                creator.sessionCookie(),
                creator.csrfToken(),
                Map.of(
                        "Idempotency-Key",
                        "knowledge-acl-" + UUID.randomUUID().toString().substring(0, 8)));
        assertThat(response.status()).as(response.body()).isEqualTo(202);
        return client.json(response);
    }

    /**
     * 读取文档 ACL。
     *
     * @param session 会话
     * @param documentId 文档 ID
     * @return 响应
     */
    protected ApiTestClient.ApiResponse getAcl(LoginSession session, String documentId) {
        return client.get("/api/v1/knowledge/documents/" + documentId + "/acl", session.sessionCookie(), Map.of());
    }

    /**
     * 整体替换文档 ACL。
     *
     * @param session 会话
     * @param documentId 文档 ID
     * @param body ACL 请求体
     * @return 响应
     */
    protected ApiTestClient.ApiResponse putAcl(LoginSession session, String documentId, Map<String, Object> body) {
        return client.put(
                "/api/v1/knowledge/documents/" + documentId + "/acl",
                JSON.writeValueAsString(body),
                session.sessionCookie(),
                session.csrfToken());
    }

    /**
     * 读取文档详情。
     *
     * @param session 会话
     * @param documentId 文档 ID
     * @return 响应
     */
    protected ApiTestClient.ApiResponse getDocument(LoginSession session, String documentId) {
        return client.get("/api/v1/knowledge/documents/" + documentId, session.sessionCookie(), Map.of());
    }

    /**
     * 列出知识库。
     *
     * @param session 会话
     * @return 响应
     */
    protected ApiTestClient.ApiResponse listDocuments(LoginSession session) {
        return client.get("/api/v1/knowledge/documents?page=1&size=100", session.sessionCookie(), Map.of());
    }

    /**
     * 查询文档关键列。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 列映射
     */
    protected Map<String, Object> documentRow(String tenant, String documentId) {
        return jdbcClient
                .sql("""
                        SELECT visibility, department_id, acl_version, status, deleted_at,
                               parse_engine, retention_expires_at, created_at, created_by
                          FROM knowledge_document
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(UUID.fromString(documentId)))
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("visibility", rs.getString("visibility"));
                    row.put("department_id", BinaryUuids.fromBytes(rs.getBytes("department_id")));
                    row.put("acl_version", rs.getInt("acl_version"));
                    row.put("status", rs.getString("status"));
                    row.put("deleted_at", rs.getObject("deleted_at"));
                    row.put("parse_engine", rs.getString("parse_engine"));
                    row.put("retention_expires_at", rs.getObject("retention_expires_at"));
                    row.put("created_at", rs.getObject("created_at"));
                    row.put("created_by", BinaryUuids.fromBytes(rs.getBytes("created_by")));
                    return row;
                })
                .single();
    }

    /**
     * 查询文档 ACL 行数。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 行数
     */
    protected long aclRowCount(String tenant, String documentId) {
        return jdbcClient
                .sql("""
                        SELECT COUNT(*) FROM knowledge_document_acl
                         WHERE tenant_id = :tenantId AND document_id = :documentId
                        """)
                .param("tenantId", tenant)
                .param("documentId", BinaryUuids.toBytes(UUID.fromString(documentId)))
                .query(Long.class)
                .single();
    }

    /**
     * 查询某文档的索引事件。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 事件行，按创建时间升序
     */
    protected List<OutboxRow> documentEvents(String tenant, String documentId) {
        return jdbcClient
                .sql("""
                        SELECT idempotency_key, payload, status
                          FROM agent_run_outbox
                         WHERE tenant_id = :tenantId
                           AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.documentId')) = :documentId
                         ORDER BY created_at ASC, id ASC
                        """)
                .param("tenantId", tenant)
                .param("documentId", documentId)
                .query((rs, rowNum) ->
                        new OutboxRow(rs.getString("idempotency_key"), rs.getString("payload"), rs.getString("status")))
                .list();
    }

    /**
     * 查询某文档的审计行（按目标 ID）。
     *
     * @param action 动作
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 审计行
     */
    protected List<AuditRow> documentAudit(String action, String tenant, String documentId) {
        List<AuditRow> rows = new ArrayList<>();
        for (AuditRow row : auditRows(action)) {
            if (tenant.equals(row.tenantId()) && documentId.equals(row.targetId())) {
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * 索引事件行。
     *
     * @param idempotencyKey 幂等键
     * @param payload payload JSON
     * @param status 状态
     */
    protected record OutboxRow(String idempotencyKey, String payload, String status) {}
}
