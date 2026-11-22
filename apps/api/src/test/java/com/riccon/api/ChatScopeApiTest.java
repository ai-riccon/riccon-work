package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.MockWorkerServer;
import com.riccon.api.support.MockWorkerServer.Scenario;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * M03 会话内容入口空间范围集成测试。
 *
 * <p>使用 mock Worker 走真实发送路径，覆盖：
 *
 * <ul>
 *   <li>共享空间成员可以向他人创建的会话追加消息，消息与运行记录归属实际发送人；
 *   <li>其他部门、未分配部门、个人空间他人与跨租户发送统一 404，且不调用 Worker；
 *   <li>停止生成只允许会话创建人或该运行的发起人，其余成员返回 404。
 * </ul>
 */
@Tag("dept-isolation")
@Tag("security")
class ChatScopeApiTest extends ApiIntegrationTestSupport {

    /** 测试共享密钥。 */
    private static final String SECRET = "test-service-token-secret-content-scope";

    /** mock Worker。 */
    private static final MockWorkerServer WORKER = new MockWorkerServer(SECRET);

    /** 额外成员统一密码。 */
    private static final String MEMBER_PASSWORD = "member-password-12";

    /**
     * 注入 mock Worker 地址与密钥。
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void workerProperties(DynamicPropertyRegistry registry) {
        registry.add("riccon.worker.client.base-url", WORKER::url);
        registry.add("riccon.worker.client.service-token-secret", () -> SECRET);
    }

    /** 每个用例前重置 mock Worker。 */
    @BeforeEach
    void resetMock() {
        WORKER.reset(Scenario.SUCCESS);
    }

    /** 关闭 mock Worker。 */
    @AfterAll
    static void stopMock() {
        WORKER.close();
    }

    /** 共享空间成员可以在他人创建的会话中继续发送消息。 */
    @Test
    @DisplayName("共享空间成员可向他人会话发送消息")
    void sendMessage_memberContributesToSharedConversation() {
        ScopeFixture fixture = scopeFixture();
        String clientMessageId = "client-" + UUID.randomUUID();

        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/conversations/" + fixture.conversationId() + "/messages",
                client.jsonBody("content", "继续调研这个主题", "clientMessageId", clientMessageId),
                fixture.member().sessionCookie(),
                fixture.member().csrfToken(),
                Map.of("Accept", "text/event-stream", "Idempotency-Key", "idem-" + UUID.randomUUID()));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("riccon.chat.completed.v1");

        Long runs = jdbcClient
                .sql("""
                        SELECT COUNT(*) FROM agent_run
                         WHERE tenant_id = :tenantId AND conversation_id = :conversationId AND user_id = :userId
                        """)
                .param("tenantId", fixture.tenant())
                .param("conversationId", BinaryUuids.toBytes(UUID.fromString(fixture.conversationId())))
                .param("userId", BinaryUuids.toBytes(fixture.memberId()))
                .query(Long.class)
                .single();
        assertThat(runs).isEqualTo(1L);
        Long messages = jdbcClient
                .sql("""
                        SELECT COUNT(*) FROM message
                         WHERE tenant_id = :tenantId AND conversation_id = :conversationId
                        """)
                .param("tenantId", fixture.tenant())
                .param("conversationId", BinaryUuids.toBytes(UUID.fromString(fixture.conversationId())))
                .query(Long.class)
                .single();
        assertThat(messages).isEqualTo(2L);
        String title = jdbcClient
                .sql("SELECT title FROM conversation WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", fixture.tenant())
                .param("id", BinaryUuids.toBytes(UUID.fromString(fixture.conversationId())))
                .query(String.class)
                .single();
        assertThat(title).isNotBlank();

        assertThat(auditRows("message.sent").stream()
                        .filter(row -> fixture.tenant().equals(row.tenantId()))
                        .toList())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.actorId()).isEqualTo(fixture.memberId().toString());
                    assertThat(row.targetId()).isEqualTo(fixture.conversationId());
                    assertThat(row.details()).contains("attachmentCount");
                });
    }

    /** 不可访问的空间发送消息统一 404，且不调用 Worker。 */
    @Test
    @DisplayName("跨部门、个人空间他人与跨租户发送消息返回 404")
    void sendMessage_outsideSpace_returnsNotFound() {
        ScopeFixture fixture = scopeFixture();
        String personalConversation = createConversation(fixture.member(), fixture.memberPersonalId());

        ApiTestClient.ApiResponse otherDepartment = send(fixture.outsider(), fixture.conversationId(), "跨部门发送");
        assertThat(otherDepartment.status()).isEqualTo(404);
        assertThat(client.json(otherDepartment).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        ApiTestClient.ApiResponse unassigned = send(fixture.unassigned(), fixture.conversationId(), "未分配发送");
        assertThat(unassigned.status()).isEqualTo(404);

        ApiTestClient.ApiResponse personalOther = send(fixture.admin(), personalConversation, "个人空间发送");
        assertThat(personalOther.status()).isEqualTo(404);

        ScopeFixture otherTenant = scopeFixture();
        ApiTestClient.ApiResponse crossTenant = send(otherTenant.member(), fixture.conversationId(), "跨租户发送");
        assertThat(crossTenant.status()).isEqualTo(404);

        assertThat(WORKER.chatRequestCount()).isZero();
        Long runs = jdbcClient
                .sql("SELECT COUNT(*) FROM agent_run WHERE tenant_id = :tenantId AND conversation_id = :id")
                .param("tenantId", fixture.tenant())
                .param("id", BinaryUuids.toBytes(UUID.fromString(fixture.conversationId())))
                .query(Long.class)
                .single();
        assertThat(runs).isZero();
    }

    /** 停止生成只允许会话创建人或运行发起人；其他可见成员统一 404。 */
    @Test
    @DisplayName("停止生成按创建人或运行发起人授权")
    void stopGeneration_creatorOrRunInitiatorOnly() {
        ScopeFixture fixture = scopeFixture();
        UUID adminRun = seedRun(fixture, fixture.adminId());
        UUID memberRun = seedRun(fixture, fixture.memberId());

        ApiTestClient.ApiResponse outsiderStop = stop(fixture.outsider(), fixture.conversationId(), adminRun);
        assertThat(outsiderStop.status()).isEqualTo(404);
        assertThat(WORKER.cancelRequestCount()).isZero();

        ApiTestClient.ApiResponse memberStopsAdminRun = stop(fixture.member(), fixture.conversationId(), adminRun);
        assertThat(memberStopsAdminRun.status()).isEqualTo(404);
        assertThat(WORKER.cancelRequestCount()).isZero();

        ApiTestClient.ApiResponse memberStopsOwnRun = stop(fixture.member(), fixture.conversationId(), memberRun);
        assertThat(memberStopsOwnRun.status()).as(memberStopsOwnRun.body()).isEqualTo(202);
        assertThat(WORKER.cancelRequestCount()).isEqualTo(1);

        ApiTestClient.ApiResponse adminStopsAdminRun = stop(fixture.admin(), fixture.conversationId(), adminRun);
        assertThat(adminStopsAdminRun.status()).as(adminStopsAdminRun.body()).isEqualTo(202);
        assertThat(WORKER.cancelRequestCount()).isEqualTo(2);

        UUID secondMemberRun = seedRun(fixture, fixture.memberId());
        ApiTestClient.ApiResponse creatorStopsMemberRun =
                stop(fixture.admin(), fixture.conversationId(), secondMemberRun);
        assertThat(creatorStopsMemberRun.status())
                .as(creatorStopsMemberRun.body())
                .isEqualTo(202);
        assertThat(WORKER.cancelRequestCount()).isEqualTo(3);
    }

    /**
     * 创建隔离租户夹具：部门 A（管理员/成员）、部门 B（其他成员）与未分配部门成员。
     *
     * @return 夹具
     */
    private ScopeFixture scopeFixture() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("chat-scope");
        createTenant(platform, tenant);
        LoginSession admin = createAndLoginTenantAdmin(platform, tenant);
        LoginSession member = createAndLoginMember(platform, tenant);
        String memberPersonal = personalWorkspace(member);
        UUID departmentA = createDepartmentId(platform, tenant, "会话研发部" + randomSuffix());
        assertThat(addMember(platform, tenant, departmentA, userId(admin)).status())
                .isEqualTo(201);
        assertThat(addMember(platform, tenant, departmentA, userId(member)).status())
                .isEqualTo(201);
        CreatedMember outsider = createMember(platform, tenant);
        UUID departmentB = createDepartmentId(platform, tenant, "会话市场部" + randomSuffix());
        assertThat(addMember(platform, tenant, departmentB, outsider.id().toString())
                        .status())
                .isEqualTo(201);
        CreatedMember unassigned = createMember(platform, tenant);
        String teamWorkspace = createTeamWorkspace(admin, departmentA, "会话共享空间");
        String conversationId = createConversation(admin, teamWorkspace);
        return new ScopeFixture(
                tenant,
                admin,
                UUID.fromString(userId(admin)),
                member,
                UUID.fromString(userId(member)),
                outsider.session(),
                unassigned.session(),
                memberPersonal,
                conversationId);
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
     * 创建部门团队空间。
     *
     * @param admin 租户管理员会话
     * @param departmentId 部门 ID
     * @param name 空间名
     * @return 空间 ID
     */
    private String createTeamWorkspace(LoginSession admin, UUID departmentId, String name) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workspaces",
                client.jsonBody("name", name, "departmentId", departmentId.toString()),
                admin.sessionCookie(),
                admin.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response).path("id").asText();
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
     * 读取个人空间。
     *
     * @param session 登录会话
     * @return 空间 ID
     */
    private String personalWorkspace(LoginSession session) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workspaces?page=1&size=100&kind=PERSONAL", session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = client.json(response);
        assertThat(body.path("items")).isNotEmpty();
        return body.path("items").get(0).path("id").asText();
    }

    /**
     * 发送消息。
     *
     * @param session 登录会话
     * @param conversationId 会话 ID
     * @param content 内容
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse send(LoginSession session, String conversationId, String content) {
        return client.post(
                "/api/v1/conversations/" + conversationId + "/messages",
                client.jsonBody("content", content, "clientMessageId", "client-" + UUID.randomUUID()),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of("Accept", "text/event-stream", "Idempotency-Key", "idem-" + UUID.randomUUID()));
    }

    /**
     * 停止生成。
     *
     * @param session 登录会话
     * @param conversationId 会话 ID
     * @param runId 运行 ID
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse stop(LoginSession session, String conversationId, UUID runId) {
        return client.post(
                "/api/v1/conversations/" + conversationId + "/stop",
                client.jsonBody("runId", runId.toString()),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of("Idempotency-Key", "idem-" + UUID.randomUUID()));
    }

    /**
     * 种一条运行中的对话运行，模拟 Worker 正在生成。
     *
     * @param fixture 夹具
     * @param userId 发起人
     * @return 运行 ID
     */
    private UUID seedRun(ScopeFixture fixture, UUID userId) {
        UUID runId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO agent_run (
                            tenant_id, id, kind, conversation_id, user_id, status, attempt, max_attempts,
                            trace_id, created_at, updated_at, started_at
                        ) VALUES (
                            :tenantId, :id, 'CHAT', :conversationId, :userId, 'RUNNING', 1, 3,
                            'chat-scope', NOW(6), NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", fixture.tenant())
                .param("id", BinaryUuids.toBytes(runId))
                .param("conversationId", BinaryUuids.toBytes(UUID.fromString(fixture.conversationId())))
                .param("userId", BinaryUuids.toBytes(userId))
                .update();
        return runId;
    }

    /**
     * 读取登录响应中的账号 ID。
     *
     * @param session 登录会话
     * @return 账号 ID
     */
    private static String userId(LoginSession session) {
        return session.body().path("user").path("id").asText();
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
     * 会话范围夹具。
     *
     * @param tenant 租户
     * @param admin 租户管理员会话
     * @param adminId 租户管理员账号 ID
     * @param member 部门 A 成员会话
     * @param memberId 部门 A 成员账号 ID
     * @param outsider 部门 B 成员会话
     * @param unassigned 未分配部门成员会话
     * @param memberPersonalId 普通成员个人空间
     * @param conversationId 部门共享空间会话
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
            String conversationId) {}

    /**
     * 额外成员。
     *
     * @param id 账号 ID
     * @param session 登录会话
     */
    private record CreatedMember(UUID id, LoginSession session) {}
}
