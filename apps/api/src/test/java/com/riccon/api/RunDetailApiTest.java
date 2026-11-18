package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.ResearchApiTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 运行详情接口集成测试。
 *
 * <p>覆盖 M02-AC-04 的模型调用可见性：J1 打开会话运行详情时能看到模型标识、开始/结束时间、token 与状态；
 * 同时验证阶段时间线复用、跨租户与个人空间他人会话统一 404、共享空间对话运行对成员可见、报告任务运行按工作空间可见性控制。
 */
class RunDetailApiTest extends ResearchApiTestSupport {

    /** 会话运行详情返回状态、阶段与模型调用记录。 */
    @Test
    @DisplayName("会话运行详情返回模型调用")
    void get_chatRun_returnsModelInvocationsAndStages() {
        Fixture fixture = fixture();
        String conversationId = createConversation(fixture.member(), fixture.memberWorkspace());
        UUID runId = seedChatRun(fixture.tenant(), conversationId, userId(fixture.member()), "RUNNING", "GENERATION");
        UUID modelId = ensureModelCatalog();
        UUID invocationId = seedModelInvocation(
                fixture.tenant(), runId, UUID.fromString(conversationId), modelId, "SUCCEEDED", 120, 850, 64, 128);
        seedStage(fixture.tenant(), runId.toString(), "RETRIEVAL", "SUCCEEDED", """
                {"query":"运行详情","hitCount":2}
                """);

        ApiTestClient.ApiResponse response =
                client.get("/api/v1/runs/" + runId, fixture.member().sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = client.json(response);
        assertThat(body.path("id").asText()).isEqualTo(runId.toString());
        assertThat(body.path("kind").asText()).isEqualTo("CHAT");
        assertThat(body.path("status").asText()).isEqualTo("RUNNING");
        assertThat(body.path("currentStage").asText()).isEqualTo("GENERATION");
        assertThat(body.path("attempt").asInt()).isEqualTo(1);
        assertThat(body.path("startedAt").isNull()).isFalse();
        assertThat(body.path("stages")).hasSize(1);
        assertThat(body.path("stages").get(0).path("stage").asText()).isEqualTo("RETRIEVAL");
        assertThat(body.path("stages").get(0).path("details").path("hitCount").asInt())
                .isEqualTo(2);
        assertThat(body.path("modelInvocations")).hasSize(1);
        JsonNode invocation = body.path("modelInvocations").get(0);
        assertThat(invocation.path("id").asText()).isEqualTo(invocationId.toString());
        assertThat(invocation.path("modelId").asText()).isEqualTo(modelId.toString());
        assertThat(invocation.path("modelKey").asText()).startsWith("run-detail-model-");
        assertThat(invocation.path("purpose").asText()).isEqualTo("CHAT");
        assertThat(invocation.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(invocation.path("firstTokenMs").asInt()).isEqualTo(120);
        assertThat(invocation.path("durationMs").asLong()).isEqualTo(850);
        assertThat(invocation.path("promptTokens").asInt()).isEqualTo(64);
        assertThat(invocation.path("completionTokens").asInt()).isEqualTo(128);
        assertThat(invocation.path("startedAt").isNull()).isFalse();
        assertThat(invocation.path("finishedAt").isNull()).isFalse();
    }

    /** 跨租户运行与个人空间他人会话统一 404，不暴露运行是否存在。 */
    @Test
    @DisplayName("跨租户或个人空间他人运行返回 404")
    void get_invisibleRun_returns404() {
        Fixture first = fixture();
        Fixture second = fixture();
        String conversationId = createConversation(first.member(), first.memberWorkspace());
        UUID runId = seedChatRun(first.tenant(), conversationId, userId(first.member()), "SUCCEEDED", "GENERATION");

        ApiTestClient.ApiResponse crossTenant =
                client.get("/api/v1/runs/" + runId, second.member().sessionCookie(), Map.of());
        assertThat(crossTenant.status()).isEqualTo(404);
        assertThat(client.json(crossTenant).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        ApiTestClient.ApiResponse otherUser =
                client.get("/api/v1/runs/" + runId, first.admin().sessionCookie(), Map.of());
        assertThat(otherUser.status()).isEqualTo(404);
        assertThat(client.json(otherUser).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        ApiTestClient.ApiResponse missing =
                client.get("/api/v1/runs/" + UUID.randomUUID(), first.member().sessionCookie(), Map.of());
        assertThat(missing.status()).isEqualTo(404);

        ApiTestClient.ApiResponse anonymous = client.get("/api/v1/runs/" + runId);
        assertThat(anonymous.status()).isEqualTo(401);
    }

    /** 报告任务运行沿用工作空间可见性：任务创建人可见，同一租户其他个人空间成员不可见。 */
    @Test
    @DisplayName("报告任务运行按工作空间可见")
    void get_researchRun_followsWorkspaceVisibility() {
        Fixture fixture = fixture();
        UUID toolId = createMcpTool(fixture.tenant());
        JsonNode task = createTask(fixture.admin(), fixture.adminWorkspace(), "运行详情可见性主题", toolId, "run-detail-0001");
        String runId = runId(task);

        ApiTestClient.ApiResponse owner =
                client.get("/api/v1/runs/" + runId, fixture.admin().sessionCookie(), Map.of());
        assertThat(owner.status()).as(owner.body()).isEqualTo(200);
        JsonNode body = client.json(owner);
        assertThat(body.path("kind").asText()).isEqualTo("RESEARCH_REPORT");
        assertThat(body.path("status").asText()).isEqualTo("PENDING");
        assertThat(body.path("startedAt").isNull()).isFalse();
        assertThat(body.path("modelInvocations")).isEmpty();
        assertThat(body.path("stages")).isEmpty();

        ApiTestClient.ApiResponse otherMember =
                client.get("/api/v1/runs/" + runId, fixture.member().sessionCookie(), Map.of());
        assertThat(otherMember.status()).isEqualTo(404);
    }

    /** 对话运行按会话所属工作空间判定：共享空间成员可读，其他部门统一 404。 */
    @Test
    @DisplayName("共享空间对话运行对本部门成员可见")
    void get_sharedDeptChatRun_visibleToDepartmentMembers() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("rund");
        createTenant(platform, tenant);
        LoginSession admin = createAndLoginTenantAdmin(platform, tenant);
        LoginSession member = createAndLoginMember(platform, tenant);
        UUID departmentId = createDepartment(platform, tenant, "运行部门" + randomSuffix());
        addDepartmentMember(platform, tenant, departmentId, userId(admin));
        addDepartmentMember(platform, tenant, departmentId, userId(member));
        String teamId = createTeamWorkspace(admin, departmentId, "运行共享空间");
        String conversationId = createConversation(admin, teamId);
        UUID runId = seedChatRun(tenant, conversationId, userId(admin), "SUCCEEDED", "GENERATION");

        ApiTestClient.ApiResponse memberView = client.get("/api/v1/runs/" + runId, member.sessionCookie(), Map.of());
        assertThat(memberView.status()).as(memberView.body()).isEqualTo(200);
        assertThat(client.json(memberView).path("kind").asText()).isEqualTo("CHAT");

        UUID otherDepartmentId = createDepartment(platform, tenant, "其他运行部门" + randomSuffix());
        LoginSession outsider = createAndLoginMember(platform, tenant);
        addDepartmentMember(platform, tenant, otherDepartmentId, userId(outsider));
        ApiTestClient.ApiResponse otherDepartment =
                client.get("/api/v1/runs/" + runId, outsider.sessionCookie(), Map.of());
        assertThat(otherDepartment.status()).isEqualTo(404);
        assertThat(client.json(otherDepartment).path("code").asText()).isEqualTo("RICCON-TENANT-404");
    }

    /**
     * 创建会话。
     *
     * @param session 登录会话
     * @param workspaceId 工作空间 ID
     * @return 会话 ID
     */
    private String createConversation(LoginSession session, String workspaceId) {
        ApiTestClient.ApiResponse created = client.post(
                "/api/v1/workspaces/" + workspaceId + "/conversations",
                "{}",
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return client.json(created).path("id").asText();
    }

    /**
     * 通过平台管理员创建部门。
     *
     * @param platform 平台管理员会话
     * @param tenant 租户
     * @param name 部门名
     * @return 部门 ID
     */
    private UUID createDepartment(LoginSession platform, String tenant, String name) {
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
     * 把账号加入部门。
     *
     * @param platform 平台管理员会话
     * @param tenant 租户
     * @param departmentId 部门 ID
     * @param memberId 账号 ID
     */
    private void addDepartmentMember(LoginSession platform, String tenant, UUID departmentId, String memberId) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/admin/tenants/" + tenant + "/departments/" + departmentId + "/members",
                client.jsonBody("userId", memberId),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
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
     * 生成唯一后缀。
     *
     * @return 8 位后缀
     */
    private static String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * 直接插入对话运行，模拟 API 创建、Worker 推进后的状态。
     *
     * @param tenant 租户
     * @param conversationId 会话 ID
     * @param memberId 发起人
     * @param status 运行状态
     * @param currentStage 当前阶段
     * @return 运行 ID
     */
    private UUID seedChatRun(
            String tenant, String conversationId, String memberId, String status, String currentStage) {
        UUID runId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO agent_run (
                            tenant_id, id, kind, conversation_id, user_id, status, current_stage,
                            attempt, max_attempts, trace_id, created_at, updated_at, started_at
                        ) VALUES (
                            :tenantId, :id, 'CHAT', :conversationId, :userId, :status, :currentStage,
                            1, 3, 'run-detail-trace', NOW(6), NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(runId))
                .param("conversationId", BinaryUuids.toBytes(UUID.fromString(conversationId)))
                .param("userId", BinaryUuids.toBytes(UUID.fromString(memberId)))
                .param("status", status)
                .param("currentStage", currentStage)
                .update();
        return runId;
    }

    /**
     * 确保模型目录存在，返回测试模型 ID。
     *
     * @return 模型 ID
     */
    private UUID ensureModelCatalog() {
        UUID providerId = UuidV7.generate();
        UUID modelId = UuidV7.generate();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcClient
                .sql("""
                        INSERT INTO model_provider (id, code, name, protocol, base_url, status, created_at, updated_at)
                        VALUES (:id, :code, 'Run Detail Provider', 'OPENAI_COMPATIBLE',
                                'https://model.invalid/v1', 'ACTIVE', NOW(6), NOW(6))
                        """)
                .param("id", BinaryUuids.toBytes(providerId))
                .param("code", "run-detail-" + suffix)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO model (
                            id, provider_id, model_key, display_name, context_window, max_output_tokens,
                            status, created_at, updated_at
                        ) VALUES (
                            :id, :providerId, :modelKey, 'Run Detail Model', 8192, 1024,
                            'ACTIVE', NOW(6), NOW(6)
                        )
                        """)
                .param("id", BinaryUuids.toBytes(modelId))
                .param("providerId", BinaryUuids.toBytes(providerId))
                .param("modelKey", "run-detail-model-" + suffix)
                .update();
        return modelId;
    }

    /**
     * 插入模型调用记录。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param conversationId 会话 ID
     * @param modelId 模型 ID
     * @param status 调用状态
     * @param firstTokenMs 首字延迟
     * @param durationMs 总耗时
     * @param promptTokens 输入 token
     * @param completionTokens 输出 token
     * @return 调用 ID
     */
    private UUID seedModelInvocation(
            String tenant,
            UUID runId,
            UUID conversationId,
            UUID modelId,
            String status,
            int firstTokenMs,
            long durationMs,
            int promptTokens,
            int completionTokens) {
        UUID invocationId = UuidV7.generate();
        String modelKey = jdbcClient
                .sql("SELECT model_key FROM model WHERE id = :id")
                .param("id", BinaryUuids.toBytes(modelId))
                .query(String.class)
                .single();
        jdbcClient
                .sql("""
                        INSERT INTO model_invocation (
                            tenant_id, id, run_id, conversation_id, message_id, model_id,
                            model_key_snapshot, purpose, status, first_token_ms, duration_ms,
                            prompt_tokens, completion_tokens, trace_id, started_at, finished_at
                        ) VALUES (
                            :tenantId, :id, :runId, :conversationId, NULL, :modelId,
                            :modelKey, 'CHAT', :status, :firstTokenMs, :durationMs,
                            :promptTokens, :completionTokens, 'run-detail-trace', NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(invocationId))
                .param("runId", BinaryUuids.toBytes(runId))
                .param("conversationId", BinaryUuids.toBytes(conversationId))
                .param("modelId", BinaryUuids.toBytes(modelId))
                .param("modelKey", modelKey)
                .param("status", status)
                .param("firstTokenMs", firstTokenMs)
                .param("durationMs", durationMs)
                .param("promptTokens", promptTokens)
                .param("completionTokens", completionTokens)
                .update();
        return invocationId;
    }
}
