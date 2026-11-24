package com.riccon.api.workflow.version;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.workflow.domain.WorkflowGraphJson;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 发布/版本/停用启用/移交集成测试基座。
 *
 * <p>复用真实 MySQL（Testcontainers）、Flyway V15、真实会话/CSRF/权限链与 HTTP；每个 fixture 使用唯一
 * 租户代码隔离数据。账号行用 fixture 种子写入以避免重复 bcrypt 编码，登录仍走真实 HTTP。部门通过平台
 * 管理员真实接口创建，用于验证 DEPARTMENT 发布范围的负责人/启用条件。
 */
public abstract class WorkflowVersionTestSupport extends ApiIntegrationTestSupport {

    /** 测试 JSON 编解码。 */
    protected static final ObjectMapper JSON = new ObjectMapper();

    /** 流程请求非法。 */
    protected static final String WF_400 = "RICCON-WF-400";

    /** 流程状态冲突。 */
    protected static final String WF_409 = "RICCON-WF-409";

    /** 不可见/跨租户统一 404。 */
    protected static final String TENANT_404 = "RICCON-TENANT-404";

    /** 可见但无能力 403。 */
    protected static final String AUTH_403 = "RICCON-AUTH-403";

    /** 默认审批人角色；所有者与其他编辑者都是该角色，保证发布校验有非发布者审批人。 */
    protected static final String DEFAULT_APPROVER_ROLE = "WORKFLOW_EDITOR";

    /**
     * 七节点合法图骨架；审批人配置与工具 ID 由测试替换。
     *
     * <p>节点配置包含 {@code SECRET-PROMPT-MARKER}，用于断言审计与版本列表不泄露 prompt。
     */
    private static final String GRAPH_TEMPLATE = """
            {
              "schemaVersion": 1,
              "nodes": [
                {"id":"start","type":"START","name":"开始","config":{}},
                {"id":"retrieval","type":"RETRIEVAL","name":"检索知识库","config":{"queryTemplate":"{{input.topic}}","topK":5}},
                {"id":"draft","type":"MODEL","name":"生成草稿","config":{"promptTemplate":"SECRET-PROMPT-MARKER","outputKind":"TEXT"}},
                {"id":"approval","type":"APPROVAL","name":"审批取数","config":__ASSIGNEE__},
                {"id":"tool","type":"MCP_TOOL","name":"取数","config":{"toolId":"__TOOL__","argsTemplate":{"query":"{{input.topic}}"}}},
                {"id":"report","type":"MODEL","name":"生成报告","config":{"promptTemplate":"整合","outputKind":"TEXT"}},
                {"id":"end","type":"END","name":"结束","config":{"resultFrom":"report","resultTitle":"市场调研报告"}}
              ],
              "edges": [
                {"id":"e1","source":"start","target":"retrieval"},
                {"id":"e2","source":"retrieval","target":"draft"},
                {"id":"e3","source":"draft","target":"approval"},
                {"id":"e4","source":"approval","target":"tool"},
                {"id":"e5","source":"tool","target":"report"},
                {"id":"e6","source":"report","target":"end"}
              ]
            }
            """;

    /**
     * 构造使用默认审批角色的合法图。
     *
     * @param fixture 测试夹具
     * @return 图 JSON
     */
    protected static String defaultGraph(Fixture fixture) {
        return graph(fixture.toolId(), roleApprover(DEFAULT_APPROVER_ROLE));
    }

    /**
     * 构造指定审批人配置的合法图。
     *
     * @param toolId 本租户已授权工具
     * @param assigneeConfig 审批节点 config JSON
     * @return 图 JSON
     */
    protected static String graph(UUID toolId, String assigneeConfig) {
        return GRAPH_TEMPLATE.replace("__TOOL__", toolId.toString()).replace("__ASSIGNEE__", assigneeConfig);
    }

    /**
     * 构造角色审批配置。
     *
     * @param role 角色码
     * @return 审批节点 config JSON
     */
    protected static String roleApprover(String role) {
        return "{\"assigneeType\":\"ROLE\",\"assigneeRole\":\"" + role
                + "\",\"actionKind\":\"MCP_TOOL\",\"targetNodeKey\":\"tool\"}";
    }

    /**
     * 构造用户审批配置。
     *
     * @param userId 审批人账号
     * @return 审批节点 config JSON
     */
    protected static String userApprover(UUID userId) {
        return "{\"assigneeType\":\"USER\",\"assigneeUserIds\":[\"" + userId
                + "\"],\"actionKind\":\"MCP_TOOL\",\"targetNodeKey\":\"tool\"}";
    }

    /**
     * 创建带租户管理员、所有者、其他编辑者、普通员工与已授权工具的隔离租户。
     *
     * @param prefix 租户代码前缀
     * @return 测试夹具
     */
    protected Fixture fixture(String prefix) {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode(prefix);
        createTenant(platform, tenant);
        LoginSession tenantAdmin = createAndLoginTenantAdmin(platform, tenant);
        LoginSession owner = seedAndLoginLocalUser(
                platform, tenant, "owner" + shortId(), "owner-password-12", List.of("WORKFLOW_EDITOR"));
        LoginSession otherEditor = seedAndLoginLocalUser(
                platform, tenant, "editor" + shortId(), "editor-password-12", List.of("WORKFLOW_EDITOR"));
        LoginSession member =
                seedAndLoginLocalUser(platform, tenant, "member" + shortId(), "member-password-12", List.of("MEMBER"));
        UUID toolId = createTool(tenant);
        return new Fixture(
                tenant,
                platform,
                tenantAdmin,
                owner,
                otherEditor,
                member,
                actorId(tenantAdmin),
                actorId(owner),
                actorId(otherEditor),
                actorId(member),
                toolId);
    }

    /**
     * 创建空白草稿并断言 201。
     *
     * @param session 会话
     * @param name 名称
     * @return 响应
     */
    protected JsonNode createDraft(LoginSession session, String name) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workflows",
                client.jsonBody("name", name),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }

    /**
     * 保存草稿并断言 200。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @param version 当前草稿版本
     * @param graph 图 JSON
     * @return 响应
     */
    protected JsonNode saveDraft(LoginSession session, String workflowId, int version, String graph) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("graph", JSON.readTree(graph));
        ApiTestClient.ApiResponse response = client.put(
                "/api/v1/workflows/" + workflowId + "/draft",
                JSON.writeValueAsString(body),
                session.sessionCookie(),
                session.csrfToken());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 调用发布端点并返回原始响应。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @param runScope 运行范围
     * @param departmentId 部门 ID；可空
     * @param idempotencyKey 幂等键；为空时不带请求头
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse publish(
            LoginSession session, String workflowId, String runScope, UUID departmentId, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (runScope != null) {
            body.put("runScope", runScope);
        }
        if (departmentId != null) {
            body.put("departmentId", departmentId.toString());
        }
        Map<String, String> headers = idempotencyKey == null ? Map.of() : Map.of("Idempotency-Key", idempotencyKey);
        return client.post(
                "/api/v1/workflows/" + workflowId + "/publish",
                JSON.writeValueAsString(body),
                session.sessionCookie(),
                session.csrfToken(),
                headers);
    }

    /**
     * 发布并断言 201。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @param runScope 运行范围
     * @param departmentId 部门 ID；可空
     * @param idempotencyKey 幂等键
     * @return 响应
     */
    protected JsonNode publishOk(
            LoginSession session, String workflowId, String runScope, UUID departmentId, String idempotencyKey) {
        ApiTestClient.ApiResponse response = publish(session, workflowId, runScope, departmentId, idempotencyKey);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }

    /**
     * 列出版本并断言 200。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @param query 查询串；可空
     * @return 响应
     */
    protected JsonNode listVersions(LoginSession session, String workflowId, String query) {
        String suffix = query == null || query.isBlank() ? "" : "?" + query;
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workflows/" + workflowId + "/versions" + suffix, session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 停用流程并返回原始响应。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse disable(LoginSession session, String workflowId) {
        return client.post(
                "/api/v1/workflows/" + workflowId + "/disable",
                null,
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
    }

    /**
     * 启用流程并返回原始响应。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse enable(LoginSession session, String workflowId) {
        return client.post(
                "/api/v1/workflows/" + workflowId + "/enable",
                null,
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
    }

    /**
     * 移交所有权并返回原始响应。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @param targetUserId 目标账号
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse transfer(LoginSession session, String workflowId, UUID targetUserId) {
        return client.post(
                "/api/v1/workflows/" + workflowId + "/transfer",
                client.jsonBody("ownerUserId", targetUserId.toString()),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
    }

    /**
     * 读取流程详情并断言状态。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @param status 期望状态码
     * @return 响应
     */
    protected JsonNode getDetail(LoginSession session, String workflowId, int status) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workflows/" + workflowId, session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        return client.json(response);
    }

    /**
     * 断言错误响应状态码与稳定错误码。
     *
     * @param response 响应
     * @param status 期望状态码
     * @param code 期望错误码
     */
    protected void assertProblem(ApiTestClient.ApiResponse response, int status, String code) {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        assertThat(client.json(response).path("code").asText()).isEqualTo(code);
    }

    /**
     * 统计流程版本数。
     *
     * @param tenant 租户
     * @param workflowId 流程 ID
     * @return 版本数
     */
    protected long versionCount(String tenant, String workflowId) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM workflow_version WHERE tenant_id = :tenant AND workflow_id = :workflowId")
                .param("tenant", tenant)
                .param("workflowId", BinaryUuids.toBytes(UUID.fromString(workflowId)))
                .query(Long.class)
                .single();
    }

    /**
     * 读取流程版本行（按版本号升序）。
     *
     * @param tenant 租户
     * @param workflowId 流程 ID
     * @return 版本行
     */
    protected List<VersionRow> versionRows(String tenant, String workflowId) {
        return jdbcClient
                .sql("""
                        SELECT id, version_no, graph_json, graph_sha256, run_scope, department_id,
                               node_count, published_by, published_at
                          FROM workflow_version
                         WHERE tenant_id = :tenant AND workflow_id = :workflowId
                         ORDER BY version_no ASC
                        """)
                .param("tenant", tenant)
                .param("workflowId", BinaryUuids.toBytes(UUID.fromString(workflowId)))
                .query((rs, rowNum) -> new VersionRow(
                        BinaryUuids.fromBytes(rs.getBytes("id")),
                        rs.getInt("version_no"),
                        rs.getString("graph_json"),
                        rs.getString("graph_sha256"),
                        rs.getString("run_scope"),
                        BinaryUuids.fromBytes(rs.getBytes("department_id")),
                        rs.getInt("node_count"),
                        BinaryUuids.fromBytes(rs.getBytes("published_by")),
                        SqlTimestamps.toInstant(rs.getObject("published_at", LocalDateTime.class))))
                .list();
    }

    /**
     * 读取流程当前发布版本 ID。
     *
     * @param tenant 租户
     * @param workflowId 流程 ID
     * @return 版本 ID；未发布时为空
     */
    protected UUID publishedVersionId(String tenant, String workflowId) {
        return jdbcClient
                .sql("""
                        SELECT published_version_id FROM workflow_definition
                         WHERE tenant_id = :tenant AND id = :workflowId
                        """)
                .param("tenant", tenant)
                .param("workflowId", BinaryUuids.toBytes(UUID.fromString(workflowId)))
                .query(byte[].class)
                .optional()
                .map(BinaryUuids::fromBytes)
                .orElse(null);
    }

    /**
     * 读取流程当前发布版本号。
     *
     * @param tenant 租户
     * @param workflowId 流程 ID
     * @return 版本号；未发布时为空
     */
    protected Integer publishedVersionNo(String tenant, String workflowId) {
        return jdbcClient
                .sql(
                        "SELECT published_version_no FROM workflow_definition WHERE tenant_id = :tenant AND id = :workflowId")
                .param("tenant", tenant)
                .param("workflowId", BinaryUuids.toBytes(UUID.fromString(workflowId)))
                .query(Integer.class)
                .optional()
                .orElse(null);
    }

    /**
     * 读取流程状态。
     *
     * @param tenant 租户
     * @param workflowId 流程 ID
     * @return 状态
     */
    protected String definitionStatus(String tenant, String workflowId) {
        return jdbcClient
                .sql("SELECT status FROM workflow_definition WHERE tenant_id = :tenant AND id = :workflowId")
                .param("tenant", tenant)
                .param("workflowId", BinaryUuids.toBytes(UUID.fromString(workflowId)))
                .query(String.class)
                .single();
    }

    /**
     * 读取流程所有者。
     *
     * @param tenant 租户
     * @param workflowId 流程 ID
     * @return 所有者账号 ID
     */
    protected UUID ownerUserIdInDatabase(String tenant, String workflowId) {
        byte[] bytes = jdbcClient
                .sql("SELECT owner_user_id FROM workflow_definition WHERE tenant_id = :tenant AND id = :workflowId")
                .param("tenant", tenant)
                .param("workflowId", BinaryUuids.toBytes(UUID.fromString(workflowId)))
                .query(byte[].class)
                .single();
        return BinaryUuids.fromBytes(bytes);
    }

    /**
     * 直接插入一条绑定指定版本的在途运行，用于验证发布新版本不改变旧运行绑定。
     *
     * @param tenant 租户
     * @param workflowId 流程 ID
     * @param versionId 绑定版本 ID
     * @param versionNo 版本号
     * @param requesterUserId 发起人
     * @return 运行 ID
     */
    protected UUID insertRunFixture(
            String tenant, String workflowId, UUID versionId, int versionNo, UUID requesterUserId) {
        UUID runId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO workflow_run (
                            tenant_id, id, workflow_id, workflow_version_id, version_no, requester_user_id,
                            status, inputs_json, result_status, result_size_bytes, execution_ms,
                            approval_wait_ms, claim_attempts, idempotency_key, retention_expires_at,
                            created_at, updated_at
                        ) VALUES (
                            :tenant, :id, :workflowId, :versionId, :versionNo, :requester,
                            'PENDING', '{}', 'NONE', 0, 0, 0, 0, :key,
                            DATE_ADD(NOW(6), INTERVAL 180 DAY), NOW(6), NOW(6)
                        )
                        """)
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(runId))
                .param("workflowId", BinaryUuids.toBytes(UUID.fromString(workflowId)))
                .param("versionId", BinaryUuids.toBytes(versionId))
                .param("versionNo", versionNo)
                .param("requester", BinaryUuids.toBytes(requesterUserId))
                .param("key", "run-fixture-" + runId)
                .update();
        return runId;
    }

    /**
     * 读取运行绑定的版本 ID。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @return 版本 ID
     */
    protected UUID runVersionId(String tenant, UUID runId) {
        byte[] bytes = jdbcClient
                .sql("SELECT workflow_version_id FROM workflow_run WHERE tenant_id = :tenant AND id = :runId")
                .param("tenant", tenant)
                .param("runId", BinaryUuids.toBytes(runId))
                .query(byte[].class)
                .single();
        return BinaryUuids.fromBytes(bytes);
    }

    /**
     * 通过平台管理员创建部门并断言 201。
     *
     * @param platform 平台管理员会话
     * @param tenant 租户
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
     * 通过平台管理员把账号加入部门并断言 201。
     *
     * @param platform 平台管理员会话
     * @param tenant 租户
     * @param departmentId 部门 ID
     * @param userId 账号
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
     * 通过平台管理员任命部门负责人并断言 200。
     *
     * @param platform 平台管理员会话
     * @param tenant 租户
     * @param departmentId 部门 ID
     * @param userId 负责人
     */
    protected void transferDepartmentManager(LoginSession platform, String tenant, UUID departmentId, UUID userId) {
        ApiTestClient.ApiResponse response = client.put(
                "/api/v1/admin/tenants/" + tenant + "/departments/" + departmentId + "/manager",
                client.jsonBody("userId", userId.toString()),
                platform.sessionCookie(),
                platform.csrfToken());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
    }

    /**
     * 创建由指定成员负责的启用部门。
     *
     * @param fixture 测试夹具
     * @param name 部门名
     * @param managerUserId 负责人账号
     * @return 部门 ID
     */
    protected UUID createManagedDepartment(Fixture fixture, String name, UUID managerUserId) {
        UUID departmentId = createDepartment(fixture.platform(), fixture.tenant(), name);
        addDepartmentMember(fixture.platform(), fixture.tenant(), departmentId, managerUserId);
        transferDepartmentManager(fixture.platform(), fixture.tenant(), departmentId, managerUserId);
        return departmentId;
    }

    /**
     * 通过租户管理员接口禁用账号。
     *
     * @param fixture 测试夹具
     * @param userId 账号
     */
    protected void disableUser(Fixture fixture, UUID userId) {
        ApiTestClient.ApiResponse response = client.patch(
                "/api/v1/users/" + userId,
                client.jsonBody("status", "DISABLED"),
                fixture.tenantAdmin().sessionCookie(),
                fixture.tenantAdmin().csrfToken());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
    }

    /**
     * 创建本测试租户可用的 MCP 工具。
     *
     * <p>工具要求审批，因此合法图必须在工具节点前放置 {@code APPROVAL actionKind=MCP_TOOL}；
     * 本基座的图模板已满足。
     *
     * @param tenant 租户
     * @return 工具 ID
     */
    private UUID createTool(String tenant) {
        UUID serverId = UuidV7.generate();
        UUID toolId = UuidV7.generate();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcClient
                .sql("""
                        INSERT INTO mcp_server (id, code, name, transport, endpoint, status, created_at, updated_at)
                        VALUES (:id, :code, 'Mock MCP', 'HTTP', 'https://mcp.invalid/rpc', 'ACTIVE', NOW(6), NOW(6))
                        """)
                .param("id", BinaryUuids.toBytes(serverId))
                .param("code", "wfv-" + suffix)
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
                        INSERT INTO mcp_tool_grant (tenant_id, mcp_tool_id, enabled, granted_at, updated_at)
                        VALUES (:tenantId, :toolId, TRUE, NOW(6), NOW(6))
                        """)
                .param("tenantId", tenant)
                .param("toolId", BinaryUuids.toBytes(toolId))
                .update();
        return toolId;
    }

    /**
     * 生成短随机后缀，避免账号重复。
     *
     * @return 8 位后缀
     */
    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * 版本数据库行。
     *
     * @param id 版本 ID
     * @param versionNo 版本号
     * @param graphJson 规范化图 JSON
     * @param graphSha256 图哈希
     * @param runScope 运行范围
     * @param departmentId 部门范围 ID
     * @param nodeCount 节点数
     * @param publishedBy 发布人
     * @param publishedAt 发布时间
     */
    protected record VersionRow(
            UUID id,
            int versionNo,
            String graphJson,
            String graphSha256,
            String runScope,
            UUID departmentId,
            int nodeCount,
            UUID publishedBy,
            Instant publishedAt) {

        /**
         * 计算规范化图哈希，用于确认发布快照与草稿算法一致。
         *
         * @return 小写 hex SHA-256
         */
        public String computedGraphSha256() {
            return WorkflowGraphJson.sha256(WorkflowGraphJson.parse(graphJson));
        }
    }

    /**
     * 测试夹具。
     *
     * @param tenant 租户代码
     * @param platform 平台管理员会话
     * @param tenantAdmin 租户管理员会话
     * @param owner 流程所有者（WORKFLOW_EDITOR）
     * @param otherEditor 同租户其他编辑者（WORKFLOW_EDITOR）
     * @param member 普通员工（MEMBER）
     * @param tenantAdminId 租户管理员账号
     * @param ownerId 所有者账号
     * @param otherEditorId 其他编辑者账号
     * @param memberId 普通员工账号
     * @param toolId 本租户已授权工具
     */
    protected record Fixture(
            String tenant,
            LoginSession platform,
            LoginSession tenantAdmin,
            LoginSession owner,
            LoginSession otherEditor,
            LoginSession member,
            UUID tenantAdminId,
            UUID ownerId,
            UUID otherEditorId,
            UUID memberId,
            UUID toolId) {}
}
