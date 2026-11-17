package com.riccon.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * J2 报告任务、审批与产物测试的共享 fixture。
 *
 * <p>租户、账号与工作空间通过公开接口创建；MCP 工具目录、审批、阶段、调用记录与产物属于 Worker 写入的
 * 数据，测试直接落库模拟“Worker 已完成若干阶段”，不调用真实 Worker。所有 fixture 使用唯一租户代码，
 * 测试之间互不干扰。
 */
public abstract class ResearchApiTestSupport extends ApiIntegrationTestSupport {

    /** JSON 解析器；只用于断言 outbox payload 等原始 JSON。 */
    protected static final ObjectMapper TEST_JSON = new ObjectMapper();

    /** 测试用 sha256 占位值：格式合法但不是任何真实内容的摘要。 */
    protected static final String TEST_SHA256 = "0123456789abcdef".repeat(4);

    /**
     * 创建两个租户员工：租户管理员与普通成员，并准备各自个人空间。
     *
     * @return 租户 fixture
     */
    protected Fixture fixture() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("t");
        createTenant(platform, tenant);
        LoginSession admin = createAndLoginTenantAdmin(platform, tenant);
        LoginSession member = createAndLoginMember(platform, tenant);
        String adminWorkspace = personalWorkspace(admin);
        String memberWorkspace = personalWorkspace(member);
        return new Fixture(tenant, platform, admin, member, adminWorkspace, memberWorkspace);
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
     * 创建 MCP server、只读工具并授权给租户。
     *
     * @param tenant 租户代码
     * @return 工具 ID
     */
    protected UUID createMcpTool(String tenant) {
        UUID serverId = UuidV7.generate();
        UUID toolId = UuidV7.generate();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcClient
                .sql("""
                        INSERT INTO mcp_server (id, code, name, transport, endpoint, status, created_at, updated_at)
                        VALUES (:id, :code, 'Mock MCP', 'HTTP', 'https://mcp.invalid/rpc', 'ACTIVE', NOW(6), NOW(6))
                        """)
                .param("id", BinaryUuids.toBytes(serverId))
                .param("code", "mock-" + suffix)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO mcp_tool (
                            id, server_id, name, description, risk_level, requires_approval, status,
                            created_at, updated_at
                        ) VALUES (
                            :id, :serverId, :name, 'mock read-only tool', 'READ', TRUE, 'ACTIVE', NOW(6), NOW(6)
                        )
                        """)
                .param("id", BinaryUuids.toBytes(toolId))
                .param("serverId", BinaryUuids.toBytes(serverId))
                .param("name", "search-" + suffix)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO mcp_tool_grant (
                            tenant_id, mcp_tool_id, enabled, granted_at, updated_at
                        ) VALUES (:tenantId, :toolId, TRUE, NOW(6), NOW(6))
                        """)
                .param("tenantId", tenant)
                .param("toolId", BinaryUuids.toBytes(toolId))
                .update();
        return toolId;
    }

    /**
     * 通过 HTTP 创建报告任务并断言 201。
     *
     * @param session 登录会话
     * @param workspaceId 工作空间 ID
     * @param topic 主题
     * @param toolId 工具 ID
     * @param idempotencyKey 幂等键
     * @return 任务 JSON
     */
    protected JsonNode createTask(
            LoginSession session, String workspaceId, String topic, UUID toolId, String idempotencyKey) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workspaces/" + workspaceId + "/research-tasks",
                client.jsonBody("topic", topic, "mcpToolId", toolId.toString()),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of("Idempotency-Key", idempotencyKey));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }

    /**
     * 读取任务的运行 ID。
     *
     * @param task 任务 JSON
     * @return 运行 ID
     */
    protected String runId(JsonNode task) {
        return task.path("currentRunId").asText();
    }

    /**
     * 把运行推进到等待审批，模拟 Worker 到达审批点。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     */
    protected void markRunWaitingApproval(String tenant, String runId) {
        jdbcClient
                .sql("""
                        UPDATE agent_run
                           SET status = 'WAITING_APPROVAL',
                               current_stage = 'APPROVAL',
                               started_at = COALESCE(started_at, NOW(6)),
                               updated_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("tenantId", tenant)
                .param("runId", BinaryUuids.toBytes(UUID.fromString(runId)))
                .update();
    }

    /**
     * 把运行推进到失败，模拟 Worker 不可重试错误。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param errorCode 稳定错误码
     * @param errorMessage 脱敏说明
     */
    protected void markRunFailed(String tenant, String runId, String errorCode, String errorMessage) {
        jdbcClient
                .sql("""
                        UPDATE agent_run
                           SET status = 'FAILED',
                               error_code = :errorCode,
                               error_message = :errorMessage,
                               finished_at = NOW(6),
                               updated_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("tenantId", tenant)
                .param("runId", BinaryUuids.toBytes(UUID.fromString(runId)))
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .update();
    }

    /**
     * 插入阶段时间线。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param stage 阶段名
     * @param status 阶段状态
     * @param detailsJson details JSON；可为空
     */
    protected void seedStage(String tenant, String runId, String stage, String status, String detailsJson) {
        jdbcClient
                .sql("""
                        INSERT INTO agent_run_stage (
                            tenant_id, id, run_id, stage, attempt_no, status, details,
                            started_at, finished_at, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, :runId, :stage, 1, :status, :details,
                            NOW(6), NOW(6), NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(UuidV7.generate()))
                .param("runId", BinaryUuids.toBytes(UUID.fromString(runId)))
                .param("stage", stage)
                .param("status", status)
                .param("details", detailsJson)
                .update();
    }

    /**
     * 插入待审批记录，并写入密文列以便测试断言“不会返回密文”。
     *
     * @param tenant 租户
     * @param taskId 任务 ID
     * @param runId 运行 ID
     * @param toolId 工具 ID
     * @param requestedBy 发起人
     * @param toolName 工具名
     * @param status 审批状态
     * @param previewJson 脱敏预览 JSON
     * @return 审批 ID
     */
    protected UUID seedApproval(
            String tenant,
            String taskId,
            String runId,
            UUID toolId,
            String requestedBy,
            String toolName,
            String status,
            String previewJson) {
        UUID approvalId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO approval (
                            tenant_id, id, research_task_id, run_id, stage, attempt_no, status,
                            action_type, mcp_tool_id, tool_name, params_preview, params_ciphertext,
                            params_key_id, params_sha256, requested_by, expires_at, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, :taskId, :runId, 'APPROVAL', 1, :status,
                            'MCP_TOOL_CALL', :toolId, :toolName, :preview, :ciphertext,
                            'dek-test', :sha256, :requestedBy, DATE_ADD(NOW(6), INTERVAL 1 HOUR), NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(approvalId))
                .param("taskId", BinaryUuids.toBytes(UUID.fromString(taskId)))
                .param("runId", BinaryUuids.toBytes(UUID.fromString(runId)))
                .param("status", status)
                .param("toolId", BinaryUuids.toBytes(toolId))
                .param("toolName", toolName)
                .param("preview", previewJson)
                .param("ciphertext", new byte[48])
                .param("sha256", TEST_SHA256)
                .param("requestedBy", BinaryUuids.toBytes(UUID.fromString(requestedBy)))
                .update();
        return approvalId;
    }

    /**
     * 插入 MCP 调用记录。
     *
     * @param tenant 租户
     * @param taskId 任务 ID
     * @param runId 运行 ID
     * @param approvalId 审批 ID
     * @param toolId 工具 ID
     * @param toolName 工具名
     * @param status 调用状态
     * @return 调用 ID
     */
    protected UUID seedInvocation(
            String tenant, String taskId, String runId, UUID approvalId, UUID toolId, String toolName, String status) {
        UUID invocationId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO mcp_invocation (
                            tenant_id, id, run_id, research_task_id, approval_id, mcp_tool_id,
                            tool_name, status, params_sha256, result_sha256, duration_ms,
                            result_preview, started_at, finished_at, created_at
                        ) VALUES (
                            :tenantId, :id, :runId, :taskId, :approvalId, :toolId,
                            :toolName, :status, :sha256, :sha256, 42,
                            'mock result summary', NOW(6), NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(invocationId))
                .param("runId", BinaryUuids.toBytes(UUID.fromString(runId)))
                .param("taskId", BinaryUuids.toBytes(UUID.fromString(taskId)))
                .param("approvalId", approvalId == null ? null : BinaryUuids.toBytes(approvalId))
                .param("toolId", BinaryUuids.toBytes(toolId))
                .param("toolName", toolName)
                .param("status", status)
                .param("sha256", TEST_SHA256)
                .update();
        return invocationId;
    }

    /**
     * 插入产物。
     *
     * @param tenant 租户
     * @param workspaceId 工作空间 ID
     * @param taskId 报告任务 ID
     * @param createdBy 创建人
     * @param title 标题
     * @param status 产物状态
     * @param currentVersion 当前版本
     * @return 产物 ID
     */
    protected UUID seedArtifact(
            String tenant,
            String workspaceId,
            String taskId,
            String createdBy,
            String title,
            String status,
            int currentVersion) {
        UUID artifactId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO artifact (
                            tenant_id, id, workspace_id, research_task_id, kind, title, status,
                            current_version, created_by, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, :workspaceId, :taskId, 'REPORT', :title, :status,
                            :currentVersion, :createdBy, NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(artifactId))
                .param("workspaceId", BinaryUuids.toBytes(UUID.fromString(workspaceId)))
                .param("taskId", taskId == null ? null : BinaryUuids.toBytes(UUID.fromString(taskId)))
                .param("title", title)
                .param("status", status)
                .param("currentVersion", currentVersion)
                .param("createdBy", BinaryUuids.toBytes(UUID.fromString(createdBy)))
                .update();
        return artifactId;
    }

    /**
     * 插入产物版本。
     *
     * @param tenant 租户
     * @param artifactId 产物 ID
     * @param version 版本号
     * @param createdBy 生成人
     * @param summary 摘要
     * @return 版本 ID
     */
    protected UUID seedArtifactVersion(String tenant, UUID artifactId, int version, String createdBy, String summary) {
        UUID versionId = UuidV7.generate();
        String storageKey = "tenants/" + tenant + "/artifacts/" + artifactId + "/v" + version + ".json";
        jdbcClient
                .sql("""
                        INSERT INTO artifact_version (
                            tenant_id, id, artifact_id, version, storage_key, content_type,
                            size_bytes, sha256, summary, created_by, created_at
                        ) VALUES (
                            :tenantId, :id, :artifactId, :version, :storageKey, 'application/json',
                            128, :sha256, :summary, :createdBy, NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(versionId))
                .param("artifactId", BinaryUuids.toBytes(artifactId))
                .param("version", version)
                .param("storageKey", storageKey)
                .param("sha256", TEST_SHA256)
                .param("summary", summary)
                .param("createdBy", BinaryUuids.toBytes(UUID.fromString(createdBy)))
                .update();
        return versionId;
    }

    /**
     * 插入 MCP 来源的产物引用。
     *
     * @param tenant 租户
     * @param versionId 版本 ID
     * @param ordinal 序号
     * @param invocationId MCP 调用 ID
     * @param title 标题快照
     * @param snippet 片段
     * @return 引用 ID
     */
    protected UUID seedCitation(
            String tenant, UUID versionId, int ordinal, UUID invocationId, String title, String snippet) {
        UUID citationId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO artifact_citation (
                            tenant_id, id, artifact_version_id, ordinal, source_type, mcp_invocation_id,
                            title_snapshot, snippet, locator_type, created_at
                        ) VALUES (
                            :tenantId, :id, :versionId, :ordinal, 'MCP', :invocationId,
                            :title, :snippet, NULL, NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(citationId))
                .param("versionId", BinaryUuids.toBytes(versionId))
                .param("ordinal", ordinal)
                .param("invocationId", BinaryUuids.toBytes(invocationId))
                .param("title", title)
                .param("snippet", snippet)
                .update();
        return citationId;
    }

    /**
     * 读取指定运行最新 outbox 事件的 payload。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @return payload JSON；不存在时为空
     */
    protected List<String> outboxPayloads(String tenant, String runId) {
        return jdbcClient
                .sql("""
                        SELECT payload FROM agent_run_outbox
                         WHERE tenant_id = :tenantId AND run_id = :runId
                         ORDER BY created_at ASC, id ASC
                        """)
                .param("tenantId", tenant)
                .param("runId", BinaryUuids.toBytes(UUID.fromString(runId)))
                .query(String.class)
                .list();
    }

    /**
     * 读取指定运行的事件类型列表。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @return 事件类型
     */
    protected List<String> outboxEventTypes(String tenant, String runId) {
        return jdbcClient
                .sql("""
                        SELECT event_type FROM agent_run_outbox
                         WHERE tenant_id = :tenantId AND run_id = :runId
                         ORDER BY created_at ASC, id ASC
                        """)
                .param("tenantId", tenant)
                .param("runId", BinaryUuids.toBytes(UUID.fromString(runId)))
                .query(String.class)
                .list();
    }

    /**
     * 统计指定运行的事件数。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param eventType 事件类型
     * @return 事件数
     */
    protected long outboxCount(String tenant, String runId, String eventType) {
        return jdbcClient
                .sql("""
                        SELECT COUNT(*) FROM agent_run_outbox
                         WHERE tenant_id = :tenantId AND run_id = :runId AND event_type = :eventType
                        """)
                .param("tenantId", tenant)
                .param("runId", BinaryUuids.toBytes(UUID.fromString(runId)))
                .param("eventType", eventType)
                .query(Long.class)
                .single();
    }

    /**
     * 解析 outbox payload（尺寸仅用于测试）。
     *
     * @param payloadJson JSON 文本
     * @return 字段映射
     */
    protected Map<String, Object> payload(String payloadJson) {
        try {
            return TEST_JSON.readValue(
                    payloadJson, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (JacksonException exception) {
            throw new IllegalStateException("outbox payload is not valid JSON", exception);
        }
    }

    /**
     * 读取任务当前状态（直接查库，用于断言 API 投影结果）。
     *
     * @param tenant 租户
     * @param taskId 任务 ID
     * @return 状态名
     */
    protected String taskStatus(String tenant, String taskId) {
        return jdbcClient
                .sql("SELECT status FROM research_task WHERE tenant_id = :tenantId AND id = :taskId")
                .param("tenantId", tenant)
                .param("taskId", BinaryUuids.toBytes(UUID.fromString(taskId)))
                .query(String.class)
                .single();
    }

    /**
     * 读取运行状态。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @return 状态名
     */
    protected String runStatus(String tenant, String runId) {
        return jdbcClient
                .sql("SELECT status FROM agent_run WHERE tenant_id = :tenantId AND id = :runId")
                .param("tenantId", tenant)
                .param("runId", BinaryUuids.toBytes(UUID.fromString(runId)))
                .query(String.class)
                .single();
    }

    /**
     * 读取审批状态。
     *
     * @param tenant 租户
     * @param approvalId 审批 ID
     * @return 状态名
     */
    protected String approvalStatus(String tenant, UUID approvalId) {
        return jdbcClient
                .sql("SELECT status FROM approval WHERE tenant_id = :tenantId AND id = :approvalId")
                .param("tenantId", tenant)
                .param("approvalId", BinaryUuids.toBytes(approvalId))
                .query(String.class)
                .single();
    }

    /**
     * 登录响应中的账号 ID。
     *
     * @param session 登录会话
     * @return 账号 ID
     */
    protected String userId(LoginSession session) {
        return session.body().path("user").path("id").asText();
    }

    /**
     * 租户 fixture。
     *
     * @param tenant 租户代码
     * @param platform 平台管理员
     * @param admin 租户管理员
     * @param member 普通成员
     * @param adminWorkspace 管理员个人空间
     * @param memberWorkspace 成员个人空间
     */
    protected record Fixture(
            String tenant,
            LoginSession platform,
            LoginSession admin,
            LoginSession member,
            String adminWorkspace,
            String memberWorkspace) {}
}
