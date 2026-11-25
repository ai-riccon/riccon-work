package com.riccon.api.workflow.approval;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.workflow.run.WorkflowRunTestSupport;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 审批 API 集成测试基座。
 *
 * <p>复用真实 MySQL（Testcontainers）、Flyway V15、真实会话/CSRF/权限链与 HTTP；流程通过真实发布接口
 * 生成版本，运行/节点/审批夹具使用直接 SQL 构造“运行已停在审批节点”的测试目标。鉴权、状态机、审计与
 * 响应仍走真实服务与端点，不复用 Worker T07a 的测试作为 API 证据。
 */
public abstract class WorkflowApprovalTestSupport extends WorkflowRunTestSupport {

    /** 审批未分配/不可见统一 404。 */
    protected static final String APPROVAL_404 = TENANT_404;

    /** 默认审批节点 key，与 {@code defaultGraph} 一致。 */
    protected static final String APPROVAL_NODE = "approval";

    /** 默认审批目标工具节点 key，与 {@code defaultGraph} 一致。 */
    protected static final String APPROVAL_TARGET_NODE = "tool";

    /** 合法的 64 位 plan 哈希。 */
    protected static final String PLAN_SHA256 = "a".repeat(64);

    /**
     * 发布一个使用默认图（角色审批 WORKFLOW_EDITOR）的流程。
     *
     * @param fixture 测试夹具
     * @param name 流程名
     * @return 流程 ID
     */
    protected String publishApprovalWorkflow(Fixture fixture, String name) {
        String workflowId = createDraft(fixture.owner(), name).path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        publishOk(fixture.tenantAdmin(), workflowId, "TENANT", null, "publish-" + UUID.randomUUID());
        return workflowId;
    }

    /**
     * 构造停在审批节点的运行与审批夹具。
     *
     * @param fixture 测试夹具
     * @param workflowId 流程 ID
     * @param requesterUserId 运行发起人
     * @param assigneeType USER 或 ROLE
     * @param assigneeRole 角色码；用户审批为空
     * @param assigneeUserIds 指定用户审批人；角色审批为空
     * @param createdAt 审批创建时间
     * @return 审批 ID
     */
    protected UUID seedAwaitingApproval(
            Fixture fixture,
            String workflowId,
            UUID requesterUserId,
            String assigneeType,
            String assigneeRole,
            List<UUID> assigneeUserIds,
            Instant createdAt) {
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID runId = insertActiveRun(
                fixture.tenant(), workflowId, versionId, 1, requesterUserId, "WAITING_APPROVAL", APPROVAL_NODE);
        insertNodeRun(fixture.tenant(), runId, APPROVAL_NODE, "APPROVAL", "审批取数", 1, "WAITING_APPROVAL");
        return insertApproval(
                fixture.tenant(),
                runId,
                workflowId,
                APPROVAL_NODE,
                assigneeType,
                assigneeRole,
                assigneeUserIds,
                requesterUserId,
                createdAt);
    }

    /**
     * 直接插入 PENDING 审批与用户指派。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param workflowId 流程 ID
     * @param nodeKey 审批节点 key
     * @param assigneeType USER 或 ROLE
     * @param assigneeRole 角色码；用户审批为空
     * @param assigneeUserIds 指定用户审批人；角色审批为空
     * @param requestedBy 发起人
     * @param createdAt 创建时间
     * @return 审批 ID
     */
    protected UUID insertApproval(
            String tenant,
            UUID runId,
            String workflowId,
            String nodeKey,
            String assigneeType,
            String assigneeRole,
            List<UUID> assigneeUserIds,
            UUID requestedBy,
            Instant createdAt) {
        UUID approvalId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO workflow_approval (
                            tenant_id, id, workflow_run_id, workflow_id, node_key, attempt, status,
                            assignee_type, assignee_role_code, action_kind, target_node_key, target_tool_name,
                            params_preview, plan_sha256, requested_by, created_at, updated_at
                        ) VALUES (
                            :tenant, :id, :runId, :workflowId, :nodeKey, 1, 'PENDING',
                            :assigneeType, :assigneeRole, 'MCP_TOOL', :targetNodeKey, 'search-mock',
                            :paramsPreview, :planSha256, :requestedBy, :createdAt, :createdAt
                        )
                        """)
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(approvalId))
                .param("runId", BinaryUuids.toBytes(runId))
                .param("workflowId", BinaryUuids.toBytes(UUID.fromString(workflowId)))
                .param("nodeKey", nodeKey)
                .param("assigneeType", assigneeType)
                .param("assigneeRole", assigneeRole)
                .param("targetNodeKey", APPROVAL_TARGET_NODE)
                .param("paramsPreview", "{\"query\":\"已脱敏参数\",\"limit\":5}")
                .param("planSha256", PLAN_SHA256)
                .param("requestedBy", BinaryUuids.toBytes(requestedBy))
                .param("createdAt", SqlTimestamps.toDatabase(createdAt))
                .update();
        if (assigneeUserIds != null) {
            for (UUID userId : assigneeUserIds) {
                jdbcClient
                        .sql("""
                                INSERT INTO workflow_approval_assignee (tenant_id, approval_id, user_id, created_at)
                                VALUES (:tenant, :approvalId, :userId, NOW(6))
                                """)
                        .param("tenant", tenant)
                        .param("approvalId", BinaryUuids.toBytes(approvalId))
                        .param("userId", BinaryUuids.toBytes(userId))
                        .update();
            }
        }
        return approvalId;
    }

    /**
     * 为运行插入一条带引用的已成功上游节点。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param citationsJson 引用 JSON
     * @param finishedAt 节点完成时间；必须早于审批创建时间
     */
    protected void insertSucceededCitations(String tenant, UUID runId, String citationsJson, Instant finishedAt) {
        UUID nodeRunId = insertNodeRun(tenant, runId, "retrieval", "RETRIEVAL", "检索知识库", 1, "SUCCEEDED");
        jdbcClient
                .sql("""
                        UPDATE workflow_node_run
                           SET citations_json = :citations,
                               started_at = :startedAt,
                               finished_at = :finishedAt,
                               duration_ms = 1000
                         WHERE tenant_id = :tenant AND id = :id
                        """)
                .param("citations", citationsJson)
                .param("startedAt", SqlTimestamps.toDatabase(finishedAt.minusSeconds(1)))
                .param("finishedAt", SqlTimestamps.toDatabase(finishedAt))
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(nodeRunId))
                .update();
    }

    /**
     * 调用待办端点。
     *
     * @param session 会话
     * @param query 查询串；可空
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse listApprovals(LoginSession session, String query) {
        String suffix = query == null || query.isBlank() ? "" : "?" + query;
        return client.get("/api/v1/workflow-approvals" + suffix, session.sessionCookie(), Map.of());
    }

    /**
     * 调用审批详情端点。
     *
     * @param session 会话
     * @param approvalId 审批 ID
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse getApproval(LoginSession session, UUID approvalId) {
        return client.get("/api/v1/workflow-approvals/" + approvalId, session.sessionCookie(), Map.of());
    }

    /**
     * 调用审批决定端点。
     *
     * @param session 会话
     * @param approvalId 审批 ID
     * @param decision APPROVE 或 REJECT
     * @param comment 理由；可空
     * @param idempotencyKey 幂等键；为空时不带请求头
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse decideApproval(
            LoginSession session, UUID approvalId, String decision, String comment, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", decision);
        if (comment != null) {
            body.put("comment", comment);
        }
        Map<String, String> headers = idempotencyKey == null ? Map.of() : Map.of("Idempotency-Key", idempotencyKey);
        return client.post(
                "/api/v1/workflow-approvals/" + approvalId + "/decision",
                JSON.writeValueAsString(body),
                session.sessionCookie(),
                session.csrfToken(),
                headers);
    }

    /**
     * 读取审批行状态。
     *
     * @param tenant 租户
     * @param approvalId 审批 ID
     * @return 审批数据库行
     */
    protected ApprovalRow approvalRow(String tenant, UUID approvalId) {
        return jdbcClient
                .sql("""
                        SELECT status, decided_by, decided_at, decision_comment, created_at
                          FROM workflow_approval
                         WHERE tenant_id = :tenant AND id = :id
                        """)
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(approvalId))
                .query((rs, rowNum) -> new ApprovalRow(
                        rs.getString("status"),
                        rs.getBytes("decided_by") == null ? null : BinaryUuids.fromBytes(rs.getBytes("decided_by")),
                        SqlTimestamps.toInstant(rs.getObject("decided_at", java.time.LocalDateTime.class)),
                        rs.getString("decision_comment"),
                        SqlTimestamps.toInstant(rs.getObject("created_at", java.time.LocalDateTime.class))))
                .single();
    }

    /**
     * 读取审批所属运行 ID。
     *
     * @param tenant 租户
     * @param approvalId 审批 ID
     * @return 运行 ID
     */
    protected UUID approvalRunId(String tenant, UUID approvalId) {
        return BinaryUuids.fromBytes(jdbcClient
                .sql("SELECT workflow_run_id FROM workflow_approval WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(approvalId))
                .query(byte[].class)
                .single());
    }

    /**
     * 读取运行结果保存状态。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @return 结果状态
     */
    protected String runResultStatus(String tenant, UUID runId) {
        return jdbcClient
                .sql("SELECT result_status FROM workflow_run WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(runId))
                .query(String.class)
                .single();
    }

    /**
     * 读取审批行原始 {@code planSha256}，用于断言响应字段来自持久化快照。
     *
     * @param tenant 租户
     * @param approvalId 审批 ID
     * @return plan 哈希
     */
    protected String approvalPlanSha256(String tenant, UUID approvalId) {
        return jdbcClient
                .sql("SELECT plan_sha256 FROM workflow_approval WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(approvalId))
                .query(String.class)
                .single();
    }

    /**
     * 读取审批节点输出 JSON。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @return 输出 JSON
     */
    protected JsonNode nodeRunOutput(String tenant, UUID runId, String nodeKey) {
        String json = jdbcClient
                .sql("""
                        SELECT output_json
                          FROM workflow_node_run
                         WHERE tenant_id = :tenant AND workflow_run_id = :runId AND node_key = :nodeKey
                         ORDER BY attempt DESC
                         LIMIT 1
                        """)
                .param("tenant", tenant)
                .param("runId", BinaryUuids.toBytes(runId))
                .param("nodeKey", nodeKey)
                .query(String.class)
                .single();
        return JSON.readTree(json);
    }

    /**
     * 读取审批节点错误码。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @return 错误码；无则 {@code null}
     */
    protected String nodeRunErrorCode(String tenant, UUID runId, String nodeKey) {
        return jdbcClient
                .sql("""
                        SELECT error_code
                          FROM workflow_node_run
                         WHERE tenant_id = :tenant AND workflow_run_id = :runId AND node_key = :nodeKey
                         ORDER BY attempt DESC
                         LIMIT 1
                        """)
                .param("tenant", tenant)
                .param("runId", BinaryUuids.toBytes(runId))
                .param("nodeKey", nodeKey)
                .query((rs, rowNum) -> rs.getString("error_code"))
                .optional()
                .orElse(null);
    }

    /**
     * 植入一个无可用审批人的审批：角色为 TENANT_ADMIN，但发起人就是唯一管理员。
     *
     * @param fixture 测试夹具
     * @param workflowId 流程 ID
     * @return 审批 ID
     */
    protected UUID seedNoApproverApproval(Fixture fixture, String workflowId) {
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID runId = insertActiveRun(
                fixture.tenant(), workflowId, versionId, 1, fixture.tenantAdminId(), "WAITING_APPROVAL", APPROVAL_NODE);
        jdbcClient
                .sql("""
                        UPDATE workflow_run
                           SET error_code = 'WF_NO_APPROVER',
                               error_message = '需先配置非发起人审批人'
                         WHERE tenant_id = :tenant AND id = :runId
                        """)
                .param("tenant", fixture.tenant())
                .param("runId", BinaryUuids.toBytes(runId))
                .update();
        insertNodeRun(fixture.tenant(), runId, APPROVAL_NODE, "APPROVAL", "审批取数", 1, "WAITING_APPROVAL");
        return insertApproval(
                fixture.tenant(),
                runId,
                workflowId,
                APPROVAL_NODE,
                "ROLE",
                "TENANT_ADMIN",
                null,
                fixture.tenantAdminId(),
                Instant.now().minusSeconds(10));
    }

    /**
     * 审批数据库行。
     *
     * @param status 审批状态
     * @param decidedBy 审批人
     * @param decidedAt 决定时间
     * @param decisionComment 决定理由
     * @param createdAt 创建时间
     */
    protected record ApprovalRow(
            String status, UUID decidedBy, Instant decidedAt, String decisionComment, Instant createdAt) {}
}
