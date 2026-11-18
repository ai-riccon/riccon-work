package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.FileApiTestSupport;
import com.riccon.api.support.TestFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * M03 内容入口空间范围集成测试。
 *
 * <p>使用真实 MySQL 与对象存储，覆盖 ADR-0018 第 3.1/3.2 节的共享空间语义：
 *
 * <ul>
 *   <li>本部门成员可读取同一空间内的会话、消息、文件、下载地址、报告任务、产物与运行详情；
 *   <li>其他部门、未分配部门、个人空间他人与跨租户统一 404；
 *   <li>标题修改、会话删除与文件删除仍按对象级规则限制为创建人/上传者；
 *   <li>workspace 包外没有第二份可见性 SQL。
 * </ul>
 *
 * <p>测试不 mock 持久化；文件上传与下载地址使用真实 RustFS 容器，其他对象通过迁移后的真实表种子。
 */
@Tag("dept-isolation")
class ContentScopeApiTest extends FileApiTestSupport {

    /** 额外成员统一密码。 */
    private static final String MEMBER_PASSWORD = "member-password-12";

    /** 本部门成员可读取共享空间内的全部内容对象。 */
    @Test
    @DisplayName("共享空间内容对本部门成员可见")
    void deptSpaceContent_visibleToDepartmentMembers() {
        ScopeFixture fixture = scopeFixture();
        String conversationId = createConversation(fixture.admin(), fixture.teamWorkspaceId());
        String fileId = upload(
                        fixture.admin(),
                        fixture.teamWorkspaceId(),
                        "shared.pdf",
                        "application/pdf",
                        TestFiles.pdf(),
                        null)
                .path("id")
                .asText();
        ResearchSeed research = seedResearchTask(fixture, "共享空间报告");
        ArtifactSeed artifact = seedArtifact(fixture, "共享空间产物");
        UUID chatRun = seedChatRun(fixture, conversationId, fixture.adminId());
        UUID fileRun = seedFileParseRun(fixture, fileId, fixture.adminId());

        JsonNode conversations =
                list(fixture.member(), "/api/v1/workspaces/" + fixture.teamWorkspaceId() + "/conversations");
        assertThat(conversations.path("total").asLong()).isEqualTo(1);
        assertThat(get(fixture.member(), "/api/v1/conversations/" + conversationId)
                        .status())
                .isEqualTo(200);
        assertThat(get(fixture.member(), "/api/v1/conversations/" + conversationId + "/messages")
                        .status())
                .isEqualTo(200);

        JsonNode files = list(fixture.member(), "/api/v1/workspaces/" + fixture.teamWorkspaceId() + "/files");
        assertThat(files.path("total").asLong()).isEqualTo(1);
        assertThat(get(fixture.member(), "/api/v1/files/" + fileId).status()).isEqualTo(200);
        assertThat(get(fixture.member(), "/api/v1/files/" + fileId + "/download-url")
                        .status())
                .isEqualTo(200);

        JsonNode tasks = list(fixture.member(), "/api/v1/workspaces/" + fixture.teamWorkspaceId() + "/research-tasks");
        assertThat(tasks.path("total").asLong()).isEqualTo(1);
        assertThat(get(fixture.member(), "/api/v1/research-tasks/" + research.taskId())
                        .status())
                .isEqualTo(200);

        JsonNode artifacts = list(fixture.member(), "/api/v1/workspaces/" + fixture.teamWorkspaceId() + "/artifacts");
        assertThat(artifacts.path("total").asLong()).isEqualTo(1);
        assertThat(get(fixture.member(), "/api/v1/artifacts/" + artifact.artifactId())
                        .status())
                .isEqualTo(200);
        assertThat(get(fixture.member(), "/api/v1/artifacts/" + artifact.artifactId() + "/versions")
                        .status())
                .isEqualTo(200);
        assertThat(get(fixture.member(), "/api/v1/artifacts/" + artifact.artifactId() + "/versions/1/download-url")
                        .status())
                .isEqualTo(200);

        assertThat(get(fixture.member(), "/api/v1/runs/" + chatRun).status()).isEqualTo(200);
        assertThat(get(fixture.member(), "/api/v1/runs/" + research.runId()).status())
                .isEqualTo(200);
        assertThat(get(fixture.member(), "/api/v1/runs/" + fileRun).status()).isEqualTo(200);
    }

    /** 其他部门、未分配部门、个人空间他人与跨租户统一 404 且不泄露内容。 */
    @Test
    @DisplayName("跨部门、个人空间他人与跨租户内容统一 404")
    void outsideScopeContent_returnsNotFound() {
        ScopeFixture fixture = scopeFixture();
        String conversationId = createConversation(fixture.admin(), fixture.teamWorkspaceId());
        String fileId = upload(
                        fixture.admin(),
                        fixture.teamWorkspaceId(),
                        "hidden.pdf",
                        "application/pdf",
                        TestFiles.pdf(),
                        null)
                .path("id")
                .asText();
        ResearchSeed research = seedResearchTask(fixture, "不可见报告");
        ArtifactSeed artifact = seedArtifact(fixture, "不可见产物");
        UUID chatRun = seedChatRun(fixture, conversationId, fixture.adminId());
        String memberConversation = createConversation(fixture.member(), fixture.memberPersonalId());
        String memberFileId = upload(
                        fixture.member(),
                        fixture.memberPersonalId(),
                        "personal.pdf",
                        "application/pdf",
                        TestFiles.pdf(),
                        null)
                .path("id")
                .asText();

        assertNotFound(get(fixture.outsider(), "/api/v1/conversations/" + conversationId));
        assertNotFound(get(fixture.outsider(), "/api/v1/workspaces/" + fixture.teamWorkspaceId() + "/conversations"));
        assertNotFound(get(fixture.outsider(), "/api/v1/files/" + fileId));
        assertNotFound(get(fixture.outsider(), "/api/v1/files/" + fileId + "/download-url"));
        assertNotFound(get(fixture.outsider(), "/api/v1/workspaces/" + fixture.teamWorkspaceId() + "/files"));
        assertNotFound(get(fixture.outsider(), "/api/v1/research-tasks/" + research.taskId()));
        assertNotFound(get(fixture.outsider(), "/api/v1/workspaces/" + fixture.teamWorkspaceId() + "/research-tasks"));
        assertNotFound(get(fixture.outsider(), "/api/v1/artifacts/" + artifact.artifactId()));
        assertNotFound(get(fixture.outsider(), "/api/v1/workspaces/" + fixture.teamWorkspaceId() + "/artifacts"));
        assertNotFound(get(fixture.outsider(), "/api/v1/runs/" + chatRun));
        assertNotFound(get(fixture.outsider(), "/api/v1/runs/" + research.runId()));

        assertNotFound(get(fixture.unassigned(), "/api/v1/conversations/" + conversationId));
        assertNotFound(get(fixture.unassigned(), "/api/v1/files/" + fileId));

        // 个人空间仍仅 owner：同部门管理员与其他部门都不可读。
        assertNotFound(get(fixture.admin(), "/api/v1/conversations/" + memberConversation));
        assertNotFound(get(fixture.admin(), "/api/v1/files/" + memberFileId));
        assertNotFound(get(fixture.outsider(), "/api/v1/conversations/" + memberConversation));

        // 跨租户保持统一 404。
        ScopeFixture otherTenant = scopeFixture();
        assertNotFound(get(otherTenant.outsider(), "/api/v1/conversations/" + conversationId));
        assertNotFound(get(otherTenant.outsider(), "/api/v1/files/" + fileId));
        assertNotFound(get(otherTenant.outsider(), "/api/v1/research-tasks/" + research.taskId()));
        assertNotFound(get(otherTenant.outsider(), "/api/v1/artifacts/" + artifact.artifactId()));
        assertNotFound(get(otherTenant.outsider(), "/api/v1/runs/" + chatRun));
    }

    /** 共享空间不放宽对象级写权限：标题/会话删除只允许创建人，文件删除只允许上传者。 */
    @Test
    @DisplayName("共享空间写操作保持创建人/上传者对象级权限")
    void sharedSpaceMutations_stayCreatorOrUploaderOnly() {
        ScopeFixture fixture = scopeFixture();
        String adminConversation = createConversation(fixture.admin(), fixture.teamWorkspaceId());
        String memberConversation = createConversation(fixture.member(), fixture.teamWorkspaceId());
        String adminFile = upload(
                        fixture.admin(),
                        fixture.teamWorkspaceId(),
                        "owner-only.pdf",
                        "application/pdf",
                        TestFiles.pdf(),
                        null)
                .path("id")
                .asText();

        assertThat(patch(fixture.member(), adminConversation, "成员改标题").status()).isEqualTo(403);
        assertThat(delete(fixture.member(), "/api/v1/conversations/" + adminConversation)
                        .status())
                .isEqualTo(403);
        assertThat(get(fixture.admin(), "/api/v1/conversations/" + adminConversation)
                        .status())
                .isEqualTo(200);

        assertThat(patch(fixture.member(), memberConversation, "创建人改标题").status())
                .isEqualTo(200);
        assertThat(delete(fixture.member(), "/api/v1/conversations/" + memberConversation)
                        .status())
                .isEqualTo(204);

        ApiTestClient.ApiResponse memberDeleteFile = delete(fixture.member(), "/api/v1/files/" + adminFile);
        assertThat(memberDeleteFile.status()).isEqualTo(403);
        assertThat(client.json(memberDeleteFile).path("code").asText()).isEqualTo("RICCON-AUTH-403");
        assertThat(get(fixture.admin(), "/api/v1/files/" + adminFile).status()).isEqualTo(200);
        assertThat(delete(fixture.admin(), "/api/v1/files/" + adminFile).status())
                .isEqualTo(204);
    }

    /** workspace 包外不得出现 M02 的第二份空间可见性谓词（TASK-0033 F-007）。 */
    @Test
    @DisplayName("workspace 包外没有第二份可见性 SQL")
    void noVisibilitySqlOutsideWorkspacePackage() throws IOException {
        Path apiSource = Path.of("src/main/java/com/riccon/api");
        if (!Files.isDirectory(apiSource)) {
            apiSource = Path.of("apps/api/src/main/java/com/riccon/api");
        }
        assertThat(apiSource).isDirectory();

        try (Stream<Path> paths = Files.walk(apiSource)) {
            List<Path> offenders = paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains("/workspace/"))
                    .filter(ContentScopeApiTest::containsLegacyVisibilitySql)
                    .toList();
            assertThat(offenders).as("workspace 包外不得复制 TEAM/owner 可见性 SQL").isEmpty();
        }
    }

    /**
     * 判断文件是否包含旧可见性 SQL；只匹配 SQL 形状，允许注释里说明被删除的规则。
     *
     * @param path Java 源文件
     * @return 命中时返回 {@code true}
     */
    private static boolean containsLegacyVisibilitySql(Path path) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            return source.contains("WORKSPACE_VISIBLE")
                    || source.matches("(?s).*w\\.kind\\s*=\\s*'TEAM'.*")
                    || source.matches("(?s).*w\\.owner_user_id\\s*=\\s*:userId.*");
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read " + path, exception);
        }
    }

    /**
     * 创建隔离租户夹具：部门 A（管理员/成员）、部门 B（其他成员）与未分配部门成员。
     *
     * @return 夹具
     */
    private ScopeFixture scopeFixture() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("content");
        createTenant(platform, tenant);
        LoginSession admin = createAndLoginTenantAdmin(platform, tenant);
        LoginSession member = createAndLoginMember(platform, tenant);
        String memberPersonal = personalWorkspace(member);
        UUID departmentA = createDepartmentId(platform, tenant, "内容研发部" + randomSuffix());
        assertThat(addMember(platform, tenant, departmentA, userId(admin)).status())
                .isEqualTo(201);
        assertThat(addMember(platform, tenant, departmentA, userId(member)).status())
                .isEqualTo(201);
        CreatedMember outsider = createMember(platform, tenant);
        UUID departmentB = createDepartmentId(platform, tenant, "内容市场部" + randomSuffix());
        assertThat(addMember(platform, tenant, departmentB, outsider.id().toString())
                        .status())
                .isEqualTo(201);
        CreatedMember unassigned = createMember(platform, tenant);
        String teamWorkspace =
                createTeam(admin, departmentA, "内容共享空间").path("id").asText();
        return new ScopeFixture(
                tenant,
                admin,
                UUID.fromString(userId(admin)),
                member,
                UUID.fromString(userId(member)),
                outsider.session(),
                unassigned.session(),
                memberPersonal,
                teamWorkspace);
    }

    /**
     * 创建额外普通成员并登录。
     *
     * @param platform 平台管理员会话
     * @param tenant 租户
     * @return 成员账号与登录会话
     */
    private CreatedMember createMember(LoginSession platform, String tenant) {
        String username = "mem" + randomSuffix();
        JsonNode user = createUser(platform, tenant, username, MEMBER_PASSWORD, List.of("MEMBER"));
        return new CreatedMember(UUID.fromString(user.path("id").asText()), login(tenant, username, MEMBER_PASSWORD));
    }

    /**
     * 通过平台管理员创建部门并返回 ID。
     *
     * @param platform 平台管理员会话
     * @param tenant 租户
     * @param name 部门名
     * @return 部门 ID
     */
    private UUID createDepartmentId(LoginSession platform, String tenant, String name) {
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
     * 通过平台管理员把账号加入部门。
     *
     * @param platform 平台管理员会话
     * @param tenant 租户
     * @param departmentId 部门 ID
     * @param userId 账号 ID
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse addMember(
            LoginSession platform, String tenant, UUID departmentId, String userId) {
        return client.post(
                "/api/v1/admin/tenants/" + tenant + "/departments/" + departmentId + "/members",
                client.jsonBody("userId", userId),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
    }

    /**
     * 通过 HTTP 创建会话。
     *
     * @param session 登录会话
     * @param workspaceId 工作空间 ID
     * @return 会话 ID
     */
    private String createConversation(LoginSession session, String workspaceId) {
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
     * 种一条报告任务与当前运行。
     *
     * @param fixture 夹具
     * @param topic 主题
     * @return 任务与运行 ID
     */
    private ResearchSeed seedResearchTask(ScopeFixture fixture, String topic) {
        UUID taskId = UuidV7.generate();
        UUID runId = UuidV7.generate();
        String key = "content-" + randomSuffix();
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
                .param("tenantId", fixture.tenant())
                .param("id", BinaryUuids.toBytes(taskId))
                .param("workspaceId", BinaryUuids.toBytes(UUID.fromString(fixture.teamWorkspaceId())))
                .param("createdBy", BinaryUuids.toBytes(fixture.adminId()))
                .param("topic", topic)
                .param("idempotencyKey", key)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO agent_run (
                            tenant_id, id, kind, research_task_id, user_id, status, attempt, max_attempts,
                            trace_id, created_at, updated_at, started_at
                        ) VALUES (
                            :tenantId, :id, 'RESEARCH_REPORT', :taskId, :userId, 'PENDING', 1, 3,
                            'content-scope', NOW(6), NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", fixture.tenant())
                .param("id", BinaryUuids.toBytes(runId))
                .param("taskId", BinaryUuids.toBytes(taskId))
                .param("userId", BinaryUuids.toBytes(fixture.adminId()))
                .update();
        jdbcClient
                .sql("""
                        UPDATE research_task SET current_run_id = :runId
                         WHERE tenant_id = :tenantId AND id = :taskId
                        """)
                .param("tenantId", fixture.tenant())
                .param("runId", BinaryUuids.toBytes(runId))
                .param("taskId", BinaryUuids.toBytes(taskId))
                .update();
        return new ResearchSeed(taskId, runId);
    }

    /**
     * 种一条产物与版本。
     *
     * @param fixture 夹具
     * @param title 标题
     * @return 产物 ID
     */
    private ArtifactSeed seedArtifact(ScopeFixture fixture, String title) {
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
                .param("tenantId", fixture.tenant())
                .param("id", BinaryUuids.toBytes(artifactId))
                .param("workspaceId", BinaryUuids.toBytes(UUID.fromString(fixture.teamWorkspaceId())))
                .param("title", title)
                .param("createdBy", BinaryUuids.toBytes(fixture.adminId()))
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO artifact_version (
                            tenant_id, id, artifact_id, version, storage_key, content_type,
                            size_bytes, sha256, summary, created_by, created_at
                        ) VALUES (
                            :tenantId, :id, :artifactId, 1, :storageKey, 'text/markdown',
                            12, :sha256, '共享空间产物摘要', :createdBy, NOW(6)
                        )
                        """)
                .param("tenantId", fixture.tenant())
                .param("id", BinaryUuids.toBytes(versionId))
                .param("artifactId", BinaryUuids.toBytes(artifactId))
                .param("storageKey", "tenants/" + fixture.tenant() + "/artifact/" + artifactId + "/v1")
                .param("sha256", "a".repeat(64))
                .param("createdBy", BinaryUuids.toBytes(fixture.adminId()))
                .update();
        return new ArtifactSeed(artifactId);
    }

    /**
     * 种一条对话运行。
     *
     * @param fixture 夹具
     * @param conversationId 会话 ID
     * @param userId 发起人
     * @return 运行 ID
     */
    private UUID seedChatRun(ScopeFixture fixture, String conversationId, UUID userId) {
        UUID runId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO agent_run (
                            tenant_id, id, kind, conversation_id, user_id, status, attempt, max_attempts,
                            trace_id, created_at, updated_at, started_at
                        ) VALUES (
                            :tenantId, :id, 'CHAT', :conversationId, :userId, 'SUCCEEDED', 1, 3,
                            'content-scope', NOW(6), NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", fixture.tenant())
                .param("id", BinaryUuids.toBytes(runId))
                .param("conversationId", BinaryUuids.toBytes(UUID.fromString(conversationId)))
                .param("userId", BinaryUuids.toBytes(userId))
                .update();
        return runId;
    }

    /**
     * 种一条文件解析运行。
     *
     * @param fixture 夹具
     * @param fileId 文件 ID
     * @param userId 发起人
     * @return 运行 ID
     */
    private UUID seedFileParseRun(ScopeFixture fixture, String fileId, UUID userId) {
        UUID runId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO agent_run (
                            tenant_id, id, kind, file_id, user_id, status, attempt, max_attempts,
                            trace_id, created_at, updated_at, started_at
                        ) VALUES (
                            :tenantId, :id, 'FILE_PARSE', :fileId, :userId, 'SUCCEEDED', 1, 3,
                            'content-scope', NOW(6), NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", fixture.tenant())
                .param("id", BinaryUuids.toBytes(runId))
                .param("fileId", BinaryUuids.toBytes(UUID.fromString(fileId)))
                .param("userId", BinaryUuids.toBytes(userId))
                .update();
        return runId;
    }

    /**
     * GET 请求。
     *
     * @param session 登录会话
     * @param path 路径
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse get(LoginSession session, String path) {
        return client.get(path, session.sessionCookie(), Map.of());
    }

    /**
     * GET 列表并断言 200。
     *
     * @param session 登录会话
     * @param path 路径
     * @return 响应 JSON
     */
    private JsonNode list(LoginSession session, String path) {
        ApiTestClient.ApiResponse response = get(session, path + "?page=1&size=20");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * PATCH 会话标题。
     *
     * @param session 登录会话
     * @param conversationId 会话 ID
     * @param title 新标题
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse patch(LoginSession session, String conversationId, String title) {
        return client.patch(
                "/api/v1/conversations/" + conversationId,
                client.jsonBody("title", title),
                session.sessionCookie(),
                session.csrfToken());
    }

    /**
     * DELETE 请求。
     *
     * @param session 登录会话
     * @param path 路径
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse delete(LoginSession session, String path) {
        return client.delete(path, session.sessionCookie(), session.csrfToken());
    }

    /**
     * 断言统一 404。
     *
     * @param response 原始响应
     */
    private void assertNotFound(ApiTestClient.ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(404);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-TENANT-404");
    }

    /**
     * 生成唯一后缀。
     *
     * @return 8 位后缀
     */
    private static String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * 内容范围夹具。
     *
     * @param tenant 租户
     * @param admin 租户管理员会话
     * @param adminId 租户管理员账号 ID
     * @param member 部门 A 普通成员会话
     * @param memberId 部门 A 普通成员账号 ID
     * @param outsider 部门 B 成员会话
     * @param unassigned 未分配部门成员会话
     * @param memberPersonalId 普通成员个人空间
     * @param teamWorkspaceId 部门 A 共享空间
     */
    private record ScopeFixture(
            String tenant,
            LoginSession admin,
            UUID adminId,
            LoginSession member,
            UUID memberId,
            LoginSession outsider,
            LoginSession unassigned,
            String memberPersonalId,
            String teamWorkspaceId) {}

    /**
     * 额外成员。
     *
     * @param id 账号 ID
     * @param session 登录会话
     */
    private record CreatedMember(UUID id, LoginSession session) {}

    /**
     * 报告任务种子。
     *
     * @param taskId 任务 ID
     * @param runId 运行 ID
     */
    private record ResearchSeed(UUID taskId, UUID runId) {}

    /**
     * 产物种子。
     *
     * @param artifactId 产物 ID
     */
    private record ArtifactSeed(UUID artifactId) {}
}
