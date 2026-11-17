package com.riccon.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 文件与知识库 API 测试的租户、工作空间与上传辅助。
 *
 * <p>所有 fixture 通过公开接口创建，不使用测试专用后门；会话、CSRF 与租户隔离仍走真实 HTTP 与数据库。
 */
public abstract class FileApiTestSupport extends ApiStorageIntegrationTestSupport {

    /**
     * 创建两个租户员工：租户管理员与普通成员，并各自准备个人空间。
     *
     * @return 租户 fixture
     */
    protected TenantFixture tenantFixture() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("t");
        createTenant(platform, tenant);
        LoginSession admin = createAndLoginTenantAdmin(platform, tenant);
        LoginSession member = createAndLoginMember(platform, tenant);
        String adminWorkspace = personalWorkspace(admin);
        String memberWorkspace = personalWorkspace(member);
        return new TenantFixture(tenant, platform, admin, member, adminWorkspace, memberWorkspace);
    }

    /**
     * 读取当前会话的个人空间 ID；列表首次调用会自动创建。
     *
     * @param session 登录会话
     * @return 个人空间 ID
     */
    protected String personalWorkspace(LoginSession session) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workspaces?page=1&size=100&kind=PERSONAL", session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = client.json(response);
        assertThat(body.path("items")).isNotEmpty();
        return body.path("items").get(0).path("id").asText();
    }

    /**
     * 创建团队空间。
     *
     * @param session 租户管理员会话
     * @param name 名称
     * @return 工作空间 JSON
     */
    protected JsonNode createTeam(LoginSession session, String name) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workspaces",
                client.jsonBody("name", name),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }

    /**
     * 上传文件并断言 201。
     *
     * @param session 登录会话
     * @param workspaceId 工作空间 ID
     * @param filename 文件名
     * @param contentType 声明 MIME
     * @param content 内容
     * @param conversationId 可选会话
     * @return 文件 JSON
     */
    protected JsonNode upload(
            LoginSession session,
            String workspaceId,
            String filename,
            String contentType,
            byte[] content,
            String conversationId) {
        ApiTestClient.ApiResponse response =
                uploadRaw(session, workspaceId, filename, contentType, content, conversationId);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }

    /**
     * 上传文件并返回原始响应。
     *
     * @param session 登录会话
     * @param workspaceId 工作空间 ID
     * @param filename 文件名
     * @param contentType 声明 MIME
     * @param content 内容
     * @param conversationId 可选会话
     * @return HTTP 响应
     */
    protected ApiTestClient.ApiResponse uploadRaw(
            LoginSession session,
            String workspaceId,
            String filename,
            String contentType,
            byte[] content,
            String conversationId) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (conversationId != null) {
            fields.put("conversationId", conversationId);
        }
        return client.postMultipart(
                "/api/v1/workspaces/" + workspaceId + "/files",
                fields,
                "file",
                filename,
                contentType,
                content,
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
    }

    /**
     * 直接种子会话，供文件数量、过滤与下载测试使用；会话 API 不属于本任务。
     *
     * @param tenant 租户
     * @param workspaceId 工作空间 ID
     * @param userId 创建人 ID
     * @return 会话 ID
     */
    protected UUID createConversation(String tenant, String workspaceId, String userId) {
        UUID conversationId = UuidV7.generate(Instant.now());
        jdbcClient
                .sql("""
                        INSERT INTO conversation (
                            tenant_id, id, workspace_id, created_by, title, status, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, :workspaceId, :createdBy, '测试会话', 'ACTIVE', NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(conversationId))
                .param("workspaceId", BinaryUuids.toBytes(UUID.fromString(workspaceId)))
                .param("createdBy", BinaryUuids.toBytes(UUID.fromString(userId)))
                .update();
        return conversationId;
    }

    /**
     * 查询文件行。
     *
     * @param tenant 租户
     * @param fileId 文件 ID
     * @return 列映射
     */
    protected Map<String, Object> fileRow(String tenant, String fileId) {
        return jdbcClient
                .sql("""
                        SELECT workspace_id, conversation_id, uploaded_by, original_name, format,
                               mime_type, size_bytes, sha256, storage_key, status, created_at, deleted_at
                          FROM file
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(UUID.fromString(fileId)))
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("workspace_id", BinaryUuids.fromBytes(rs.getBytes("workspace_id")));
                    row.put("conversation_id", BinaryUuids.fromBytes(rs.getBytes("conversation_id")));
                    row.put("uploaded_by", BinaryUuids.fromBytes(rs.getBytes("uploaded_by")));
                    row.put("original_name", rs.getString("original_name"));
                    row.put("format", rs.getString("format"));
                    row.put("mime_type", rs.getString("mime_type"));
                    row.put("size_bytes", rs.getLong("size_bytes"));
                    row.put("sha256", rs.getString("sha256"));
                    row.put("storage_key", rs.getString("storage_key"));
                    row.put("status", rs.getString("status"));
                    row.put("created_at", rs.getObject("created_at", java.time.LocalDateTime.class));
                    row.put("deleted_at", rs.getObject("deleted_at", java.time.LocalDateTime.class));
                    return row;
                })
                .single();
    }

    /**
     * 查询文件关联的 agent_run 与 outbox。
     *
     * @param tenant 租户
     * @param fileId 文件 ID
     * @return 列映射
     */
    protected Map<String, Object> outboxForFile(String tenant, String fileId) {
        return jdbcClient
                .sql("""
                        SELECT o.id AS event_id, o.event_type, o.event_version, o.idempotency_key,
                               o.payload, o.status AS outbox_status, o.attempts,
                               r.id AS run_id, r.kind, r.status AS run_status, r.file_id, r.user_id, r.trace_id
                          FROM agent_run_outbox o
                          JOIN agent_run r ON r.tenant_id = o.tenant_id AND r.id = o.run_id
                         WHERE o.tenant_id = :tenantId
                           AND r.file_id = :fileId
                         ORDER BY o.created_at DESC, o.id DESC
                         LIMIT 1
                        """)
                .param("tenantId", tenant)
                .param("fileId", BinaryUuids.toBytes(UUID.fromString(fileId)))
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("event_id", BinaryUuids.fromBytes(rs.getBytes("event_id")));
                    row.put("event_type", rs.getString("event_type"));
                    row.put("event_version", rs.getInt("event_version"));
                    row.put("idempotency_key", rs.getString("idempotency_key"));
                    row.put("payload", rs.getString("payload"));
                    row.put("outbox_status", rs.getString("outbox_status"));
                    row.put("attempts", rs.getInt("attempts"));
                    row.put("run_id", BinaryUuids.fromBytes(rs.getBytes("run_id")));
                    row.put("kind", rs.getString("kind"));
                    row.put("run_status", rs.getString("run_status"));
                    row.put("file_id", BinaryUuids.fromBytes(rs.getBytes("file_id")));
                    row.put("user_id", BinaryUuids.fromBytes(rs.getBytes("user_id")));
                    row.put("trace_id", rs.getString("trace_id"));
                    return row;
                })
                .single();
    }

    /**
     * 读取登录用户的账号 ID。
     *
     * @param session 登录会话
     * @return 账号 ID 字符串
     */
    protected String userId(LoginSession session) {
        return session.body().path("user").path("id").asText();
    }

    /**
     * 测试租户 fixture。
     *
     * @param tenant 租户代码
     * @param platform 平台管理员会话
     * @param admin 租户管理员会话
     * @param member 普通成员会话
     * @param adminWorkspace 管理员个人空间
     * @param memberWorkspace 成员个人空间
     */
    protected record TenantFixture(
            String tenant,
            LoginSession platform,
            LoginSession admin,
            LoginSession member,
            String adminWorkspace,
            String memberWorkspace) {}
}
