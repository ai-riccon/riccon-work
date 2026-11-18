package com.riccon.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.FileApiTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M03 独立安全测试基座。
 *
 * <p>由 verifier 维护，不复用 Builder 的部门夹具；所有租户、部门、空间、账号均通过公开 HTTP 接口在真实
 * MySQL 上创建。测试目标是从攻击者视角验证部门/租户边界，而不是重复 Builder 的功能断言。
 *
 * <ul>
 *   <li>租户 A：部门 A（租户管理员、负责人、同部门成员）、部门 B（其他部门成员）、未分配成员；
 *   <li>租户 B：独立成员，用于跨租户用例；
 *   <li>个人空间由首次访问自动创建，部门空间通过租户管理员创建。
 * </ul>
 */
abstract class SecurityTestSupport extends FileApiTestSupport {

    /** 普通成员统一密码。 */
    protected static final String MEMBER_PASSWORD = "member-password-12";

    /** 服务令牌测试密钥；只用于本地 mock worker，不进入生产配置。 */
    protected static final String SERVICE_SECRET = "security-suite-service-token-secret-0001";

    /** JSON 序列化器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 创建 M03 安全测试夹具。
     *
     * @param prefix 租户代码前缀，保证同容器内唯一
     * @return 夹具
     */
    protected Fixture fixture(String prefix) {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode(prefix);
        createTenant(platform, tenant);
        LoginSession tenantAdmin = createAndLoginTenantAdmin(platform, tenant);
        UUID tenantAdminId = UUID.fromString(userId(tenantAdmin));

        String managerUsername = "mgr" + randomSuffix();
        JsonNode managerUser = createUser(platform, tenant, managerUsername, MEMBER_PASSWORD, List.of("MEMBER"));
        LoginSession manager = login(tenant, managerUsername, MEMBER_PASSWORD);
        UUID managerId = UUID.fromString(managerUser.path("id").asText());

        CreatedMember sibling = createMember(platform, tenant);
        CreatedMember outsider = createMember(platform, tenant);
        CreatedMember unassigned = createMember(platform, tenant);

        String suffix = randomSuffix();
        UUID departmentA = createDepartment(platform, tenant, "销售部" + suffix);
        UUID departmentB = createDepartment(platform, tenant, "市场部" + suffix);
        assertThat(addDepartmentMember(platform, tenant, departmentA, tenantAdminId)
                        .status())
                .isEqualTo(201);
        assertThat(addDepartmentMember(platform, tenant, departmentA, managerId).status())
                .isEqualTo(201);
        assertThat(addDepartmentMember(platform, tenant, departmentA, sibling.id())
                        .status())
                .isEqualTo(201);
        assertThat(addDepartmentMember(platform, tenant, departmentB, outsider.id())
                        .status())
                .isEqualTo(201);
        assertThat(transferDepartmentManager(platform, tenant, departmentA, managerId)
                        .status())
                .isEqualTo(200);

        String deptWorkspace = createDepartmentWorkspace(tenantAdmin, departmentA, "部门共享空间", "DEPT")
                .path("id")
                .asText();
        String customWorkspace = createDepartmentWorkspace(tenantAdmin, departmentA, "指定成员空间", "CUSTOM")
                .path("id")
                .asText();
        String otherDepartmentWorkspace = createDepartmentWorkspace(tenantAdmin, departmentB, "市场部空间", "DEPT")
                .path("id")
                .asText();
        String adminPersonal = personalWorkspace(tenantAdmin);

        return new Fixture(
                tenant,
                platform,
                tenantAdmin,
                tenantAdminId,
                manager,
                managerId,
                managerUsername,
                sibling,
                outsider,
                unassigned,
                departmentA,
                departmentB,
                deptWorkspace,
                customWorkspace,
                otherDepartmentWorkspace,
                adminPersonal);
    }

    /**
     * 创建独立租户 B 的成员，用于跨租户断言。
     *
     * @param platform 平台管理员会话
     * @return 租户 B 的成员会话
     */
    protected CrossTenantMember crossTenantMember(LoginSession platform) {
        String tenant = uniqueTenantCode("xtenant");
        createTenant(platform, tenant);
        CreatedMember member = createMember(platform, tenant);
        LoginSession tenantAdmin = createAndLoginTenantAdmin(platform, tenant);
        return new CrossTenantMember(tenant, member, tenantAdmin);
    }

    /**
     * 创建额外普通成员并登录。
     *
     * @param platform 平台管理员会话
     * @param tenant 目标租户
     * @return 成员账号与会话
     */
    protected CreatedMember createMember(LoginSession platform, String tenant) {
        String username = "mem" + randomSuffix();
        JsonNode user = createUser(platform, tenant, username, MEMBER_PASSWORD, List.of("MEMBER"));
        return new CreatedMember(
                UUID.fromString(user.path("id").asText()), username, login(tenant, username, MEMBER_PASSWORD));
    }

    /**
     * 通过平台管理员创建部门。
     *
     * @param platform 平台管理员会话
     * @param tenant 目标租户
     * @param name 部门名
     * @return 部门 ID
     */
    protected UUID createDepartment(LoginSession platform, String tenant, String name) {
        ApiTestClient.ApiResponse response = client.post(
                adminDepartmentsPath(tenant),
                client.jsonBody("name", name),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return UUID.fromString(client.json(response).path("id").asText());
    }

    /**
     * 平台部门路径。
     *
     * @param tenant 目标租户
     * @return 路径
     */
    protected String adminDepartmentsPath(String tenant) {
        return "/api/v1/admin/tenants/" + tenant + "/departments";
    }

    /**
     * 把账号加入部门。
     *
     * @param platform 平台管理员会话
     * @param tenant 目标租户
     * @param departmentId 部门
     * @param userId 账号
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse addDepartmentMember(
            LoginSession platform, String tenant, UUID departmentId, UUID userId) {
        return client.post(
                adminDepartmentsPath(tenant) + "/" + departmentId + "/members",
                client.jsonBody("userId", userId.toString()),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
    }

    /**
     * 把账号移出部门。
     *
     * @param platform 平台管理员会话
     * @param tenant 目标租户
     * @param departmentId 部门
     * @param userId 账号
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse removeDepartmentMember(
            LoginSession platform, String tenant, UUID departmentId, UUID userId) {
        return client.delete(
                adminDepartmentsPath(tenant) + "/" + departmentId + "/members/" + userId,
                platform.sessionCookie(),
                platform.csrfToken());
    }

    /**
     * 移交部门负责人。
     *
     * @param platform 平台管理员会话
     * @param tenant 目标租户
     * @param departmentId 部门
     * @param userId 新负责人
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse transferDepartmentManager(
            LoginSession platform, String tenant, UUID departmentId, UUID userId) {
        return client.put(
                adminDepartmentsPath(tenant) + "/" + departmentId + "/manager",
                client.jsonBody("userId", userId.toString()),
                platform.sessionCookie(),
                platform.csrfToken());
    }

    /**
     * 通过部门路径创建空间。
     *
     * @param session 租户管理员或负责人
     * @param departmentId 部门
     * @param name 名称
     * @param visibility DEPT 或 CUSTOM
     * @return 空间 JSON
     */
    protected JsonNode createDepartmentWorkspace(
            LoginSession session, UUID departmentId, String name, String visibility) {
        ApiTestClient.ApiResponse response = createDepartmentWorkspaceRaw(session, departmentId, name, visibility);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }

    /**
     * 通过部门路径创建空间的原始响应。
     *
     * @param session 会话
     * @param departmentId 部门
     * @param name 名称
     * @param visibility 可见性
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse createDepartmentWorkspaceRaw(
            LoginSession session, UUID departmentId, String name, String visibility) {
        return client.post(
                "/api/v1/departments/" + departmentId + "/workspaces",
                client.jsonBody("name", name, "visibility", visibility),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
    }

    /**
     * 通过 POST /workspaces 创建空间。
     *
     * @param session 租户管理员
     * @param departmentId 部门
     * @param name 名称
     * @param visibility 可见性
     * @return 空间 JSON
     */
    protected JsonNode createWorkspaceAsAdmin(LoginSession session, UUID departmentId, String name, String visibility) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workspaces",
                client.jsonBody("name", name, "departmentId", departmentId.toString(), "visibility", visibility),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }

    /**
     * 加入 CUSTOM 空间显式成员。
     *
     * @param session 负责人或租户管理员
     * @param workspaceId 空间
     * @param userId 账号
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse addWorkspaceMember(LoginSession session, String workspaceId, UUID userId) {
        return client.post(
                "/api/v1/workspaces/" + workspaceId + "/members",
                client.jsonBody("userId", userId.toString()),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
    }

    /**
     * 移出 CUSTOM 空间显式成员。
     *
     * @param session 负责人或租户管理员
     * @param workspaceId 空间
     * @param userId 账号
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse removeWorkspaceMember(LoginSession session, String workspaceId, UUID userId) {
        return client.delete(
                "/api/v1/workspaces/" + workspaceId + "/members/" + userId,
                session.sessionCookie(),
                session.csrfToken());
    }

    /**
     * 在指定空间创建会话并断言 201。
     *
     * @param session 会话
     * @param workspaceId 空间
     * @return 会话 ID
     */
    protected String createConversation(LoginSession session, String workspaceId) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workspaces/" + workspaceId + "/conversations",
                "{}",
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response).path("id").asText();
    }

    /**
     * GET 请求。
     *
     * @param session 会话
     * @param path 路径
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse get(LoginSession session, String path) {
        return client.get(path, session.sessionCookie(), Map.of());
    }

    /**
     * GET 列表并断言 200。
     *
     * @param session 会话
     * @param path 路径
     * @return JSON
     */
    protected JsonNode list(LoginSession session, String path) {
        ApiTestClient.ApiResponse response = get(session, path + (path.contains("?") ? "&" : "?") + "page=1&size=100");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 断言统一 404：状态与错误码一致，响应不含任何业务字段。
     *
     * @param response 原始响应
     */
    protected void assertNotFound(ApiTestClient.ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(404);
        JsonNode body = client.json(response);
        assertThat(body.path("code").asText()).isEqualTo("RICCON-TENANT-404");
        assertThat(body.path("id").isMissingNode()).isTrue();
        assertThat(body.path("name").isMissingNode()).isTrue();
        assertThat(body.path("workspaceId").isMissingNode()).isTrue();
        assertThat(body.path("fileId").isMissingNode()).isTrue();
        assertThat(body.path("items").isMissingNode()).isTrue();
    }

    /**
     * 断言响应体不包含业务内容或账号标识。
     *
     * @param response 原始响应
     * @param markers 不允许出现的文本
     */
    protected void assertNoLeak(ApiTestClient.ApiResponse response, String... markers) {
        for (String marker : markers) {
            assertThat(response.body()).as("response leaked %s", marker).doesNotContain(marker);
        }
        assertThat(response.body()).doesNotContain("@");
    }

    /**
     * 读取账号当前主部门。
     *
     * @param tenant 租户
     * @param userId 账号
     * @return 部门 ID 或 {@code null}
     */
    protected UUID userDepartment(String tenant, UUID userId) {
        List<byte[]> rows = jdbcClient
                .sql("SELECT department_id FROM app_user WHERE tenant_id = :tenantId AND id = :userId")
                .param("tenantId", tenant)
                .param("userId", BinaryUuids.toBytes(userId))
                .query((rs, rowNum) -> rs.getBytes("department_id"))
                .list();
        assertThat(rows).as("user row must exist").hasSize(1);
        return BinaryUuids.fromBytes(rows.get(0));
    }

    /**
     * 读取账号在指定租户的主部门；账号不属于该租户时返回 {@code null}。
     *
     * @param tenant 租户
     * @param userId 账号
     * @return 部门 ID 或 {@code null}
     */
    protected UUID userDepartmentOrNull(String tenant, UUID userId) {
        List<byte[]> rows = jdbcClient
                .sql("SELECT department_id FROM app_user WHERE tenant_id = :tenantId AND id = :userId")
                .param("tenantId", tenant)
                .param("userId", BinaryUuids.toBytes(userId))
                .query((rs, rowNum) -> rs.getBytes("department_id"))
                .list();
        return rows.isEmpty() ? null : BinaryUuids.fromBytes(rows.get(0));
    }

    /**
     * 统计 CUSTOM 空间显式成员行。
     *
     * @param tenant 租户
     * @param workspaceId 空间
     * @return 行数
     */
    protected long workspaceMemberCount(String tenant, String workspaceId) {
        return jdbcClient
                .sql("""
                        SELECT COUNT(*) FROM workspace_member
                         WHERE tenant_id = :tenantId AND workspace_id = :workspaceId
                        """)
                .param("tenantId", tenant)
                .param("workspaceId", BinaryUuids.toBytes(UUID.fromString(workspaceId)))
                .query(Long.class)
                .single();
    }

    /**
     * 种子一条报告任务与当前运行。
     *
     * @param tenant 租户
     * @param workspaceId 空间
     * @param createdBy 创建人
     * @param topic 主题
     * @return 任务与运行 ID
     */
    protected ResearchSeed seedResearchTask(String tenant, String workspaceId, UUID createdBy, String topic) {
        UUID taskId = UuidV7.generate();
        UUID runId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO research_task (
                            tenant_id, id, workspace_id, created_by, topic, status, current_stage,
                            mcp_tool_id, current_run_id, idempotency_key, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, :workspaceId, :createdBy, :topic, 'PENDING', NULL,
                            NULL, NULL, :idempotencyKey, NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(taskId))
                .param("workspaceId", BinaryUuids.toBytes(UUID.fromString(workspaceId)))
                .param("createdBy", BinaryUuids.toBytes(createdBy))
                .param("topic", topic)
                .param("idempotencyKey", "security-" + randomSuffix())
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO agent_run (
                            tenant_id, id, kind, research_task_id, user_id, status, attempt, max_attempts,
                            trace_id, created_at, updated_at, started_at
                        ) VALUES (
                            :tenantId, :id, 'RESEARCH_REPORT', :taskId, :userId, 'PENDING', 1, 3,
                            'security-suite', NOW(6), NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(runId))
                .param("taskId", BinaryUuids.toBytes(taskId))
                .param("userId", BinaryUuids.toBytes(createdBy))
                .update();
        jdbcClient
                .sql("""
                        UPDATE research_task SET current_run_id = :runId
                         WHERE tenant_id = :tenantId AND id = :taskId
                        """)
                .param("tenantId", tenant)
                .param("runId", BinaryUuids.toBytes(runId))
                .param("taskId", BinaryUuids.toBytes(taskId))
                .update();
        return new ResearchSeed(taskId, runId);
    }

    /**
     * 种子一条产物与版本；存储键属于租户 A 的空间。
     *
     * @param tenant 租户
     * @param workspaceId 空间
     * @param createdBy 创建人
     * @param title 标题
     * @return 产物 ID
     */
    protected UUID seedArtifact(String tenant, String workspaceId, UUID createdBy, String title) {
        UUID artifactId = UuidV7.generate();
        UUID versionId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO artifact (
                            tenant_id, id, workspace_id, research_task_id, conversation_id, kind, title,
                            status, current_version, created_by, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, :workspaceId, NULL, NULL, 'REPORT', :title,
                            'SAVED', 1, :createdBy, NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(artifactId))
                .param("workspaceId", BinaryUuids.toBytes(UUID.fromString(workspaceId)))
                .param("title", title)
                .param("createdBy", BinaryUuids.toBytes(createdBy))
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO artifact_version (
                            tenant_id, id, artifact_id, version, storage_key, content_type,
                            size_bytes, sha256, summary, created_by, created_at
                        ) VALUES (
                            :tenantId, :id, :artifactId, 1, :storageKey, 'text/markdown',
                            16, :sha256, '安全测试产物摘要', :createdBy, NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(versionId))
                .param("artifactId", BinaryUuids.toBytes(artifactId))
                .param("storageKey", "tenants/" + tenant + "/artifact/" + artifactId + "/v1")
                .param("sha256", "b".repeat(64))
                .param("createdBy", BinaryUuids.toBytes(createdBy))
                .update();
        return artifactId;
    }

    /**
     * 种子一条对话运行。
     *
     * @param tenant 租户
     * @param conversationId 会话
     * @param userId 发起人
     * @return 运行 ID
     */
    protected UUID seedChatRun(String tenant, UUID conversationId, UUID userId) {
        UUID runId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO agent_run (
                            tenant_id, id, kind, conversation_id, user_id, status, attempt, max_attempts,
                            trace_id, created_at, updated_at, started_at
                        ) VALUES (
                            :tenantId, :id, 'CHAT', :conversationId, :userId, 'SUCCEEDED', 1, 3,
                            'security-suite', NOW(6), NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(runId))
                .param("conversationId", BinaryUuids.toBytes(conversationId))
                .param("userId", BinaryUuids.toBytes(userId))
                .update();
        return runId;
    }

    /**
     * 签发与 API/Worker 相同格式的 HMAC 服务令牌。
     *
     * @param tenant 租户
     * @param userId 用户
     * @param runId 运行
     * @param expiresAt 过期时间
     * @return 令牌
     */
    protected String signServiceToken(String tenant, UUID userId, UUID runId, Instant expiresAt) {
        Instant now = Instant.now();
        Map<String, Object> claims = new java.util.LinkedHashMap<>();
        claims.put("iss", "riccon-api");
        claims.put("aud", "riccon-worker");
        claims.put("sub", "user:" + userId);
        claims.put("tenantId", tenant);
        claims.put("runId", runId.toString());
        claims.put("scope", "agent:run");
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        try {
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(MAPPER.writeValueAsBytes(claims));
            String signingInput = "riccon1." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SERVICE_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
            return signingInput + "." + signature;
        } catch (Exception exception) {
            throw new IllegalStateException("test service token signing failed", exception);
        }
    }

    /**
     * 生成 8 位随机后缀。
     *
     * @return 后缀
     */
    protected String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * 安全测试夹具。
     *
     * @param tenant 租户 A
     * @param platform 平台管理员
     * @param tenantAdmin 租户管理员（部门 A 成员）
     * @param tenantAdminId 租户管理员账号 ID
     * @param manager 部门 A 负责人
     * @param managerId 负责人账号 ID
     * @param managerUsername 负责人登录名
     * @param sibling 部门 A 普通成员
     * @param outsider 部门 B 成员
     * @param unassigned 未分配部门成员
     * @param departmentA 部门 A
     * @param departmentB 部门 B
     * @param departmentWorkspace 部门 A 的 DEPT 空间
     * @param customWorkspace 部门 A 的 CUSTOM 空间
     * @param otherDepartmentWorkspace 部门 B 的 DEPT 空间
     * @param adminPersonal 租户管理员个人空间
     */
    protected record Fixture(
            String tenant,
            LoginSession platform,
            LoginSession tenantAdmin,
            UUID tenantAdminId,
            LoginSession manager,
            UUID managerId,
            String managerUsername,
            CreatedMember sibling,
            CreatedMember outsider,
            CreatedMember unassigned,
            UUID departmentA,
            UUID departmentB,
            String departmentWorkspace,
            String customWorkspace,
            String otherDepartmentWorkspace,
            String adminPersonal) {}

    /**
     * 额外成员。
     *
     * @param id 账号 ID
     * @param username 登录名
     * @param session 会话
     */
    protected record CreatedMember(UUID id, String username, LoginSession session) {}

    /**
     * 跨租户成员。
     *
     * @param tenant 租户 B
     * @param member 成员
     * @param tenantAdmin 租户 B 管理员
     */
    protected record CrossTenantMember(String tenant, CreatedMember member, LoginSession tenantAdmin) {}

    /**
     * 报告任务种子。
     *
     * @param taskId 任务
     * @param runId 运行
     */
    protected record ResearchSeed(UUID taskId, UUID runId) {}
}
