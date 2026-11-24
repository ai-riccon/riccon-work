package com.riccon.api.workflow.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.workflow.version.WorkflowVersionTestSupport;
import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 运行准入/创建/取消/重试集成测试基座。
 *
 * <p>复用真实 MySQL（Testcontainers）、Flyway V15、真实会话/CSRF/权限链与 HTTP；租户、账号、部门与发布
 * 版本通过 {@link WorkflowVersionTestSupport} 的真实接口准备。运行/节点/审批夹具使用直接 SQL，仅用于
 * 构造“已存在活跃运行/失败节点”等测试目标不是创建端点的场景，鉴权与状态机断言仍走真实服务。
 */
public abstract class WorkflowRunTestSupport extends WorkflowVersionTestSupport {

    /** 运行并发超限。 */
    protected static final String WF_429 = "RICCON-WF-429";

    /** 编排关闭。 */
    protected static final String WF_503 = "RICCON-WF-503";

    /** M04 配额超限。 */
    protected static final String QUOTA_429 = "RICCON-QUOTA-429";

    /** 默认运行输入。 */
    protected static final String DEFAULT_INPUTS = "{\"topic\":\"市场调研\"}";

    /**
     * 调用创建运行端点并返回原始响应。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @param versionId 版本 ID；为空时使用最近发布版本
     * @param inputsJson 输入 JSON
     * @param idempotencyKey 幂等键；为空时不带请求头
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse createRun(
            LoginSession session, String workflowId, UUID versionId, String inputsJson, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (versionId != null) {
            body.put("versionId", versionId.toString());
        }
        body.put("inputs", JSON.readTree(inputsJson));
        Map<String, String> headers =
                idempotencyKey == null ? Map.of() : Map.of("Idempotency-Key", idempotencyKey);
        return client.post(
                "/api/v1/workflows/" + workflowId + "/runs",
                JSON.writeValueAsString(body),
                session.sessionCookie(),
                session.csrfToken(),
                headers);
    }

    /**
     * 创建运行并断言 201。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @param versionId 版本 ID；可空
     * @param idempotencyKey 幂等键
     * @return 运行响应
     */
    protected JsonNode createRunOk(
            LoginSession session, String workflowId, UUID versionId, String idempotencyKey) {
        ApiTestClient.ApiResponse response =
                createRun(session, workflowId, versionId, DEFAULT_INPUTS, idempotencyKey);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }

    /**
     * 调用取消端点。
     *
     * @param session 会话
     * @param runId 运行 ID
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse cancelRun(LoginSession session, String runId) {
        return client.post(
                "/api/v1/workflow-runs/" + runId + "/cancel",
                null,
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
    }

    /**
     * 调用重试端点。
     *
     * @param session 会话
     * @param runId 运行 ID
     * @param nodeId 失败节点 key
     * @param idempotencyKey 幂等键；为空时不带请求头
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse retryRun(
            LoginSession session, String runId, String nodeId, String idempotencyKey) {
        Map<String, String> headers =
                idempotencyKey == null ? Map.of() : Map.of("Idempotency-Key", idempotencyKey);
        return client.post(
                "/api/v1/workflow-runs/" + runId + "/retry",
                client.jsonBody("nodeId", nodeId),
                session.sessionCookie(),
                session.csrfToken(),
                headers);
    }

    /**
     * 统计租户内运行数。
     *
     * @param tenant 租户
     * @return 运行数
     */
    protected long runCount(String tenant) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM workflow_run WHERE tenant_id = :tenant")
                .param("tenant", tenant)
                .query(Long.class)
                .single();
    }

    /**
     * 统计指定流程的运行数。
     *
     * @param tenant 租户
     * @param workflowId 流程 ID
     * @return 运行数
     */
    protected long runCount(String tenant, String workflowId) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM workflow_run WHERE tenant_id = :tenant AND workflow_id = :workflowId")
                .param("tenant", tenant)
                .param("workflowId", BinaryUuids.toBytes(UUID.fromString(workflowId)))
                .query(Long.class)
                .single();
    }

    /**
     * 读取运行行（用于断言绑定版本与状态机副作用）。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @return 运行行
     */
    protected RunRow runRow(String tenant, UUID runId) {
        return jdbcClient
                .sql("""
                        SELECT id, workflow_id, workflow_version_id, version_no, requester_user_id, status,
                               current_node_key, error_code, error_message, idempotency_key,
                               claim_attempts, created_at, updated_at, started_at, finished_at,
                               retention_expires_at
                          FROM workflow_run
                         WHERE tenant_id = :tenant AND id = :runId
                        """)
                .param("tenant", tenant)
                .param("runId", BinaryUuids.toBytes(runId))
                .query((rs, rowNum) -> new RunRow(
                        BinaryUuids.fromBytes(rs.getBytes("id")),
                        BinaryUuids.fromBytes(rs.getBytes("workflow_id")),
                        BinaryUuids.fromBytes(rs.getBytes("workflow_version_id")),
                        rs.getInt("version_no"),
                        BinaryUuids.fromBytes(rs.getBytes("requester_user_id")),
                        rs.getString("status"),
                        rs.getString("current_node_key"),
                        rs.getString("error_code"),
                        rs.getString("error_message"),
                        rs.getString("idempotency_key"),
                        rs.getInt("claim_attempts"),
                        instant(rs.getObject("created_at", LocalDateTime.class)),
                        instant(rs.getObject("updated_at", LocalDateTime.class)),
                        instant(rs.getObject("started_at", LocalDateTime.class)),
                        instant(rs.getObject("finished_at", LocalDateTime.class)),
                        instant(rs.getObject("retention_expires_at", LocalDateTime.class))))
                .single();
    }

    /**
     * 直接插入活跃运行（用于并发上限与历史列表夹具）。
     *
     * @param tenant 租户
     * @param workflowId 流程 ID
     * @param versionId 绑定版本
     * @param versionNo 版本号
     * @param requesterUserId 发起人
     * @param status 状态；必须是 PENDING/RUNNING/WAITING_APPROVAL
     * @param nodeKey 当前节点
     * @return 运行 ID
     */
    protected UUID insertActiveRun(
            String tenant,
            String workflowId,
            UUID versionId,
            int versionNo,
            UUID requesterUserId,
            String status,
            String nodeKey) {
        UUID runId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO workflow_run (
                            tenant_id, id, workflow_id, workflow_version_id, version_no, requester_user_id,
                            status, inputs_json, result_status, result_size_bytes, current_node_key,
                            execution_ms, approval_wait_ms, claim_attempts, idempotency_key,
                            retention_expires_at, created_at, updated_at
                        ) VALUES (
                            :tenant, :id, :workflowId, :versionId, :versionNo, :requester,
                            :status, '{}', 'NONE', 0, :nodeKey,
                            0, 0, 0, :key,
                            DATE_ADD(NOW(6), INTERVAL 180 DAY), NOW(6), NOW(6)
                        )
                        """)
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(runId))
                .param("workflowId", BinaryUuids.toBytes(UUID.fromString(workflowId)))
                .param("versionId", BinaryUuids.toBytes(versionId))
                .param("versionNo", versionNo)
                .param("requester", BinaryUuids.toBytes(requesterUserId))
                .param("status", status)
                .param("nodeKey", nodeKey)
                .param("key", "seed-run-" + runId)
                .update();
        return runId;
    }

    /**
     * 直接插入失败运行与失败节点（用于重试夹具）。
     *
     * @param tenant 租户
     * @param workflowId 流程 ID
     * @param versionId 绑定版本
     * @param versionNo 版本号
     * @param requesterUserId 发起人
     * @param failedNodeKey 失败节点 key
     * @return 运行 ID
     */
    protected UUID insertFailedRun(
            String tenant,
            String workflowId,
            UUID versionId,
            int versionNo,
            UUID requesterUserId,
            String failedNodeKey) {
        UUID runId = insertActiveRun(
                tenant, workflowId, versionId, versionNo, requesterUserId, "FAILED", failedNodeKey);
        jdbcClient
                .sql("""
                        UPDATE workflow_run
                           SET error_code = 'WF_MODEL_UNAVAILABLE',
                               error_message = 'model unavailable',
                               started_at = NOW(6),
                               finished_at = NOW(6)
                         WHERE tenant_id = :tenant AND id = :runId
                        """)
                .param("tenant", tenant)
                .param("runId", BinaryUuids.toBytes(runId))
                .update();
        insertNodeRun(tenant, runId, failedNodeKey, "MODEL", "生成草稿", 1, "FAILED");
        return runId;
    }

    /**
     * 直接插入节点运行。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param nodeType 节点类型
     * @param nodeName 节点名
     * @param attempt 尝试号
     * @param status 状态
     * @return 节点运行 ID
     */
    protected UUID insertNodeRun(
            String tenant,
            UUID runId,
            String nodeKey,
            String nodeType,
            String nodeName,
            int attempt,
            String status) {
        UUID id = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO workflow_node_run (
                            tenant_id, id, workflow_run_id, node_key, node_type, node_name, attempt, status,
                            created_at, updated_at
                        ) VALUES (
                            :tenant, :id, :runId, :nodeKey, :nodeType, :nodeName, :attempt, :status,
                            NOW(6), NOW(6)
                        )
                        """)
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(id))
                .param("runId", BinaryUuids.toBytes(runId))
                .param("nodeKey", nodeKey)
                .param("nodeType", nodeType)
                .param("nodeName", nodeName)
                .param("attempt", attempt)
                .param("status", status)
                .update();
        return id;
    }

    /**
     * 直接插入待审批项。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param workflowId 流程 ID
     * @param nodeKey 审批节点 key
     * @param requestedBy 发起人
     * @return 审批 ID
     */
    protected UUID insertPendingApproval(
            String tenant, UUID runId, String workflowId, String nodeKey, UUID requestedBy) {
        UUID approvalId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO workflow_approval (
                            tenant_id, id, workflow_run_id, workflow_id, node_key, attempt, status,
                            assignee_type, assignee_role_code, action_kind, params_preview, plan_sha256,
                            requested_by, created_at, updated_at
                        ) VALUES (
                            :tenant, :id, :runId, :workflowId, :nodeKey, 1, 'PENDING',
                            'ROLE', 'TENANT_ADMIN', 'CONTINUE', '{}', REPEAT('a', 64),
                            :requestedBy, NOW(6), NOW(6)
                        )
                        """)
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(approvalId))
                .param("runId", BinaryUuids.toBytes(runId))
                .param("workflowId", BinaryUuids.toBytes(UUID.fromString(workflowId)))
                .param("nodeKey", nodeKey)
                .param("requestedBy", BinaryUuids.toBytes(requestedBy))
                .update();
        return approvalId;
    }

    /**
     * 读取节点尝试列表（按 attempt 升序）。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @return 尝试列表
     */
    protected List<NodeRow> nodeRows(String tenant, UUID runId, String nodeKey) {
        return jdbcClient
                .sql("""
                        SELECT id, node_key, node_type, node_name, attempt, status
                          FROM workflow_node_run
                         WHERE tenant_id = :tenant AND workflow_run_id = :runId AND node_key = :nodeKey
                         ORDER BY attempt ASC
                        """)
                .param("tenant", tenant)
                .param("runId", BinaryUuids.toBytes(runId))
                .param("nodeKey", nodeKey)
                .query((rs, rowNum) -> new NodeRow(
                        BinaryUuids.fromBytes(rs.getBytes("id")),
                        rs.getString("node_key"),
                        WorkflowNodeType.valueOf(rs.getString("node_type")),
                        rs.getString("node_name"),
                        rs.getInt("attempt"),
                        WorkflowNodeStatus.valueOf(rs.getString("status"))))
                .list();
    }

    /**
     * 统计某节点所有尝试数。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @return 尝试数
     */
    protected long nodeAttemptCount(String tenant, UUID runId, String nodeKey) {
        return nodeRows(tenant, runId, nodeKey).size();
    }

    /**
     * 读取运行下指定状态的节点 key。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param status 节点状态
     * @return 节点 key 列表
     */
    protected List<String> nodeKeysByStatus(String tenant, UUID runId, String status) {
        return jdbcClient
                .sql("""
                        SELECT node_key FROM workflow_node_run
                         WHERE tenant_id = :tenant AND workflow_run_id = :runId AND status = :status
                         ORDER BY node_key
                        """)
                .param("tenant", tenant)
                .param("runId", BinaryUuids.toBytes(runId))
                .param("status", status)
                .query(String.class)
                .list();
    }

    /**
     * 按租户与动作读取审计行。
     *
     * @param tenant 租户
     * @param action 动作
     * @return 审计行
     */
    protected List<AuditRow> tenantAuditRows(String tenant, String action) {
        List<AuditRow> rows = new ArrayList<>();
        for (AuditRow row : auditRows(action)) {
            if (tenant.equals(row.tenantId())) {
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * 解析审计 details。
     *
     * @param row 审计行
     * @return details JSON
     */
    protected JsonNode auditDetails(AuditRow row) {
        return JSON.readTree(row.details());
    }

    /**
     * 把账号移到指定部门或移出部门。
     *
     * @param tenant 租户
     * @param userId 账号
     * @param departmentId 部门 ID；{@code null} 表示移出
     */
    protected void moveUserDepartment(String tenant, UUID userId, UUID departmentId) {
        int updated = jdbcClient
                .sql("UPDATE app_user SET department_id = :departmentId WHERE tenant_id = :tenant AND id = :userId")
                .param("tenant", tenant)
                .param("userId", BinaryUuids.toBytes(userId))
                .param("departmentId", departmentId == null ? null : BinaryUuids.toBytes(departmentId))
                .update();
        assertThat(updated).isEqualTo(1);
    }

    /**
     * 直接停用部门，模拟“下一次请求按新范围判定”。
     *
     * @param tenant 租户
     * @param departmentId 部门 ID
     */
    protected void disableDepartment(String tenant, UUID departmentId) {
        int updated = jdbcClient
                .sql("""
                        UPDATE department
                           SET status = 'DISABLED', disabled_at = NOW(6)
                         WHERE tenant_id = :tenant AND id = :departmentId
                        """)
                .param("tenant", tenant)
                .param("departmentId", BinaryUuids.toBytes(departmentId))
                .update();
        assertThat(updated).isEqualTo(1);
    }

    /**
     * 通过平台管理员设置租户配额并断言 200。
     *
     * @param platform 平台管理员
     * @param tenant 租户
     * @param daily 日限额
     * @param monthly 月限额
     */
    protected void setTenantQuota(LoginSession platform, String tenant, Integer daily, Integer monthly) {
        ApiTestClient.ApiResponse response = client.put(
                "/api/v1/admin/tenants/" + tenant + "/quotas",
                "{\"limits\":{\"daily\":" + daily + ",\"monthly\":" + monthly + "}}",
                platform.sessionCookie(),
                platform.csrfToken());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
    }

    /**
     * 通过平台管理员设置用户配额并断言 200。
     *
     * @param platform 平台管理员
     * @param tenant 租户
     * @param userId 账号
     * @param daily 日限额
     * @param monthly 月限额
     */
    protected void setUserQuota(LoginSession platform, String tenant, UUID userId, Integer daily, Integer monthly) {
        ApiTestClient.ApiResponse response = client.put(
                "/api/v1/admin/tenants/" + tenant + "/users/" + userId + "/quotas",
                "{\"limits\":{\"daily\":" + daily + ",\"monthly\":" + monthly + "}}",
                platform.sessionCookie(),
                platform.csrfToken());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
    }

    /**
     * 设置全局配额执行开关；用于验证“按运行 1 单位准入”。
     *
     * @param enabled 是否执行
     */
    protected void setQuotaEnforcement(boolean enabled) {
        jdbcClient
                .sql("""
                        UPDATE model_gateway_switch
                           SET switch_value = :value, updated_at = NOW(6)
                         WHERE switch_key = 'quotaEnforcementEnabled'
                        """)
                .param("value", Boolean.toString(enabled))
                .update();
    }

    /**
     * 读取配额窗口已用量。
     *
     * @param tenant 租户
     * @param scopeType 维度
     * @param scopeId 维度标识
     * @param window 窗口
     * @return 已用量
     */
    protected long quotaUsed(String tenant, String scopeType, String scopeId, String window) {
        List<Integer> values = jdbcClient
                .sql("""
                        SELECT used_count FROM quota_usage
                         WHERE tenant_id = :tenant AND scope_type = :scopeType
                           AND scope_id = :scopeId AND window_kind = :window
                         ORDER BY window_start DESC
                         LIMIT 1
                        """)
                .param("tenant", tenant)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("window", window)
                .query(Integer.class)
                .list();
        return values.isEmpty() ? 0L : values.getFirst();
    }

    /**
     * UTC 时间转换。
     *
     * @param value 数据库时间
     * @return UTC 时间
     */
    private static Instant instant(LocalDateTime value) {
        return SqlTimestamps.toInstant(value);
    }

    /**
     * 运行数据库行。
     *
     * @param id 运行 ID
     * @param workflowId 流程 ID
     * @param workflowVersionId 版本 ID
     * @param versionNo 版本号
     * @param requesterUserId 发起人
     * @param status 状态
     * @param currentNodeKey 当前节点
     * @param errorCode 错误码
     * @param errorMessage 错误说明
     * @param idempotencyKey 幂等键
     * @param claimAttempts 领取次数
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param startedAt 开始时间
     * @param finishedAt 结束时间
     * @param retentionExpiresAt 保留到期
     */
    protected record RunRow(
            UUID id,
            UUID workflowId,
            UUID workflowVersionId,
            int versionNo,
            UUID requesterUserId,
            String status,
            String currentNodeKey,
            String errorCode,
            String errorMessage,
            String idempotencyKey,
            int claimAttempts,
            Instant createdAt,
            Instant updatedAt,
            Instant startedAt,
            Instant finishedAt,
            Instant retentionExpiresAt) {}

    /**
     * 节点尝试数据库行。
     *
     * @param id 节点运行 ID
     * @param nodeKey 节点 key
     * @param nodeType 节点类型
     * @param nodeName 节点名
     * @param attempt 尝试号
     * @param status 状态
     */
    protected record NodeRow(
            UUID id,
            String nodeKey,
            WorkflowNodeType nodeType,
            String nodeName,
            int attempt,
            WorkflowNodeStatus status) {}
}
