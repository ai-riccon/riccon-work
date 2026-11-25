package com.riccon.worker.workflow.queue.support;

import com.riccon.worker.common.UuidV7;
import com.riccon.worker.persistence.UuidBinary;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 工作流运行队列集成测试夹具。
 *
 * <p>直接写入 V15 的 {@code workflow_definition/workflow_version/workflow_run} 与 {@code audit_event}；
 * 每个测试使用唯一租户隔离，不清理全局表。非法租户夹具故意使用带空格的 tenant id：
 * {@code tenant} 表允许该字符串（仅 CHECK 非空），而 {@code TenantId} 会拒绝，用于验证 fail-closed。
 */
public final class WorkflowQueueTestSupport {

    /** 测试图：START → MODEL；结束节点不参与队列测试。 */
    private static final String GRAPH_JSON = """
            {"nodes":[{"id":"start","type":"START","name":"开始","config":{}},\
            {"id":"model","type":"MODEL","name":"生成草稿","config":{"promptTemplate":"x","outputKind":"TEXT"}}],\
            "edges":[{"from":"start","to":"model"}]}
            """;

    /** 当前节点 key。 */
    public static final String CURRENT_NODE_KEY = "model";

    /** JDBC 客户端。 */
    private final JdbcClient jdbcClient;

    /**
     * 创建夹具。
     *
     * @param jdbcClient JDBC 客户端
     */
    public WorkflowQueueTestSupport(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 把残留的可领取或执行中运行（PENDING/RUNNING）置为远期租约。
     *
     * <p>{@code workflow_run} 是跨租户持久队列（TM-85），无法用租户过滤隔离；每个集成测试在创建
     * 自己的夹具前调用本方法，避免测试类/方法之间通过全局队列互相“抢单”。覆盖 PENDING 与
     * RUNNING：心跳/租约负向用例会故意留下短租约或抢占行，即使当前未过期，也可能在后续用例执行
     * 期间到期变成可领取；统一改为 1 天租约即可保证本轮测试只看到自己创建的行。与既有
     * {@code clearOutbox()} 同类，只在确实领取队列的测试类中使用。
     */
    public void drainPendingRuns() {
        jdbcClient.sql("""
                        UPDATE workflow_run
                           SET status = 'RUNNING',
                               lease_owner = 'test-drain',
                               lease_until = DATE_ADD(NOW(6), INTERVAL 1 DAY),
                               started_at = COALESCE(started_at, NOW(6)),
                               updated_at = NOW(6)
                         WHERE status IN ('PENDING', 'RUNNING')
                        """).update();
    }

    /**
     * 创建租户（允许非法字符，用于负向测试）。
     *
     * @param tenantId 租户 ID
     */
    public void createTenant(String tenantId) {
        jdbcClient
                .sql("""
                        INSERT INTO tenant (id, name, status, plan, retention_days)
                        VALUES (:tenantId, :name, 'ACTIVE', 'standard', 180)
                        """)
                .param("tenantId", tenantId)
                .param("name", "Test " + tenantId)
                .update();
    }

    /**
     * 创建租户内账号。
     *
     * @param tenantId 租户
     * @return 账号 ID
     */
    public UUID createUser(String tenantId) {
        UUID userId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO app_user (tenant_id, id, username, display_name, status, auth_source)
                        VALUES (:tenantId, :id, :username, 'Queue User', 'ACTIVE', 'LOCAL')
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(userId))
                .param("username", "queue-" + userId.toString().substring(0, 8))
                .update();
        return userId;
    }

    /**
     * 创建流程定义与发布版本。
     *
     * @param tenantId 租户
     * @param ownerUserId 所有者
     * @return 定义与版本 ID
     */
    public WorkflowSeed createWorkflow(String tenantId, UUID ownerUserId) {
        UUID workflowId = UuidV7.generate();
        UUID versionId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO workflow_definition (
                            tenant_id, id, name, description, owner_user_id, draft_version, draft_graph,
                            status, created_by, updated_by
                        ) VALUES (
                            :tenantId, :id, 'Queue Test Workflow', NULL, :owner, 1, :graph,
                            'ACTIVE', :owner, :owner
                        )
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(workflowId))
                .param("owner", UuidBinary.toBytes(ownerUserId))
                .param("graph", GRAPH_JSON)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO workflow_version (
                            tenant_id, id, workflow_id, version_no, graph_json, graph_sha256,
                            node_count, run_scope, published_by, published_at, created_at
                        ) VALUES (
                            :tenantId, :id, :workflowId, 1, :graph, REPEAT('a', 64),
                            2, 'TENANT', :owner, NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(versionId))
                .param("workflowId", UuidBinary.toBytes(workflowId))
                .param("graph", GRAPH_JSON)
                .param("owner", UuidBinary.toBytes(ownerUserId))
                .update();
        return new WorkflowSeed(workflowId, versionId);
    }

    /**
     * 插入运行。
     *
     * @param tenantId 租户
     * @param seed 流程与版本
     * @param requester 发起人
     * @param status 状态
     * @param claimAttempts 领取次数
     * @param leaseOwner 租约持有者；可为空
     * @param leaseUntil 租约到期；可为空
     * @param currentNodeKey 当前节点；可为空
     * @return 运行 ID
     */
    public UUID insertRun(
            String tenantId,
            WorkflowSeed seed,
            UUID requester,
            String status,
            int claimAttempts,
            String leaseOwner,
            Instant leaseUntil,
            String currentNodeKey) {
        UUID runId = UuidV7.generate();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        params.put("id", UuidBinary.toBytes(runId));
        params.put("workflowId", UuidBinary.toBytes(seed.workflowId()));
        params.put("versionId", UuidBinary.toBytes(seed.versionId()));
        params.put("requester", UuidBinary.toBytes(requester));
        params.put("status", status);
        params.put("nodeKey", currentNodeKey);
        params.put("claimAttempts", claimAttempts);
        params.put("leaseOwner", leaseOwner);
        params.put("leaseUntil", leaseUntil == null ? null : LocalDateTime.ofInstant(leaseUntil, ZoneOffset.UTC));
        params.put("key", "seed-" + runId);
        jdbcClient.sql("""
                        INSERT INTO workflow_run (
                            tenant_id, id, workflow_id, workflow_version_id, version_no, requester_user_id,
                            status, inputs_json, result_status, result_size_bytes, current_node_key,
                            execution_ms, approval_wait_ms, claim_attempts, idempotency_key,
                            lease_owner, lease_until, retention_expires_at, created_at, updated_at, started_at
                        ) VALUES (
                            :tenantId, :id, :workflowId, :versionId, 1, :requester,
                            :status, '{}', 'NONE', 0, :nodeKey,
                            0, 0, :claimAttempts, :key,
                            :leaseOwner, :leaseUntil, DATE_ADD(NOW(6), INTERVAL 180 DAY),
                            NOW(6), NOW(6), CASE WHEN :status = 'RUNNING' THEN NOW(6) ELSE NULL END
                        )
                        """).params(params).update();
        return runId;
    }

    /**
     * 插入节点运行。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param nodeType 节点类型
     * @param attempt 尝试号
     * @param status 状态
     */
    public void insertNodeRun(
            String tenantId, UUID runId, String nodeKey, String nodeType, int attempt, String status) {
        jdbcClient
                .sql("""
                        INSERT INTO workflow_node_run (
                            tenant_id, id, workflow_run_id, node_key, node_type, node_name, attempt, status,
                            started_at, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, :runId, :nodeKey, :nodeType, 'Node', :attempt, :status,
                            CASE WHEN :status IN ('RUNNING', 'FAILED') THEN NOW(6) ELSE NULL END,
                            NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(UuidV7.generate()))
                .param("runId", UuidBinary.toBytes(runId))
                .param("nodeKey", nodeKey)
                .param("nodeType", nodeType)
                .param("attempt", attempt)
                .param("status", status)
                .update();
    }

    /**
     * 把运行置为 RUNNING 且租约已过期，模拟崩溃实例遗留。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param claimAttempts 领取次数
     * @param owner 旧租约持有者
     * @param nodeKey 当前节点
     */
    public void expireLease(String tenantId, UUID runId, int claimAttempts, String owner, String nodeKey) {
        jdbcClient
                .sql("""
                        UPDATE workflow_run
                           SET status = 'RUNNING',
                               claim_attempts = :claimAttempts,
                               lease_owner = :owner,
                               lease_until = DATE_SUB(NOW(6), INTERVAL 1 SECOND),
                               current_node_key = :nodeKey,
                               started_at = COALESCE(started_at, NOW(6)),
                               updated_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("tenantId", tenantId)
                .param("runId", UuidBinary.toBytes(runId))
                .param("claimAttempts", claimAttempts)
                .param("owner", owner)
                .param("nodeKey", nodeKey)
                .update();
    }

    /**
     * 读取运行行。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @return 运行行
     */
    public RunRow readRun(String tenantId, UUID runId) {
        return jdbcClient
                .sql("""
                        SELECT status, error_code, claim_attempts, lease_owner, lease_until, current_node_key
                          FROM workflow_run
                         WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("tenantId", tenantId)
                .param("runId", UuidBinary.toBytes(runId))
                .query((rs, rowNum) -> new RunRow(
                        rs.getString("status"),
                        rs.getString("error_code"),
                        rs.getInt("claim_attempts"),
                        rs.getString("lease_owner"),
                        rs.getObject("lease_until", LocalDateTime.class),
                        rs.getString("current_node_key")))
                .single();
    }

    /**
     * 读取指定节点的全部尝试。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @return 尝试行
     */
    public List<NodeRow> readNodes(String tenantId, UUID runId, String nodeKey) {
        return jdbcClient
                .sql("""
                        SELECT attempt, status, error_code, node_type, node_name
                          FROM workflow_node_run
                         WHERE tenant_id = :tenantId AND workflow_run_id = :runId AND node_key = :nodeKey
                         ORDER BY attempt
                        """)
                .param("tenantId", tenantId)
                .param("runId", UuidBinary.toBytes(runId))
                .param("nodeKey", nodeKey)
                .query((rs, rowNum) -> new NodeRow(
                        rs.getInt("attempt"),
                        rs.getString("status"),
                        rs.getString("error_code"),
                        rs.getString("node_type"),
                        rs.getString("node_name")))
                .list();
    }

    /**
     * 统计审计事件。
     *
     * @param tenantId 审计租户
     * @param action 动作
     * @param traceId trace
     * @return 事件数
     */
    public long countAudit(String tenantId, String action, String traceId) {
        Long count = jdbcClient
                .sql("""
                        SELECT COUNT(*) FROM audit_event
                         WHERE tenant_id = :tenantId AND action = :action AND trace_id = :traceId
                        """)
                .param("tenantId", tenantId)
                .param("action", action)
                .param("traceId", traceId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    /**
     * 读取审计 details JSON。
     *
     * @param tenantId 审计租户
     * @param action 动作
     * @param traceId trace
     * @return details 字符串
     */
    public String readAuditDetails(String tenantId, String action, String traceId) {
        return jdbcClient
                .sql("""
                        SELECT details FROM audit_event
                         WHERE tenant_id = :tenantId AND action = :action AND trace_id = :traceId
                         ORDER BY occurred_at DESC
                         LIMIT 1
                        """)
                .param("tenantId", tenantId)
                .param("action", action)
                .param("traceId", traceId)
                .query(String.class)
                .single();
    }

    /**
     * 流程与版本 ID。
     *
     * @param workflowId 流程 ID
     * @param versionId 版本 ID
     */
    public record WorkflowSeed(UUID workflowId, UUID versionId) {}

    /**
     * 运行行快照。
     *
     * @param status 状态
     * @param errorCode 错误码
     * @param claimAttempts 领取次数
     * @param leaseOwner 租约持有者
     * @param leaseUntil 租约到期
     * @param currentNodeKey 当前节点
     */
    public record RunRow(
            String status,
            String errorCode,
            int claimAttempts,
            String leaseOwner,
            LocalDateTime leaseUntil,
            String currentNodeKey) {}

    /**
     * 节点尝试行。
     *
     * @param attempt 尝试号
     * @param status 状态
     * @param errorCode 错误码
     * @param nodeType 节点类型
     * @param nodeName 节点名
     */
    public record NodeRow(int attempt, String status, String errorCode, String nodeType, String nodeName) {}
}
