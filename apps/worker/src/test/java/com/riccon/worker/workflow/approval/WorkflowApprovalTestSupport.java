package com.riccon.worker.workflow.approval;

import com.riccon.worker.common.UuidV7;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.MutableClock;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.NodeRow;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.RunRow;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.Seed;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.TestLease;
import com.riccon.worker.workflow.engine.domain.WorkflowEngineLimits;
import com.riccon.worker.workflow.engine.domain.WorkflowGraph;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphJson;
import com.riccon.worker.workflow.engine.repository.WorkflowEngineRepository;
import com.riccon.worker.workflow.engine.service.WorkflowEngine;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecutor;
import com.riccon.worker.workflow.node.service.WorkflowNodeDispatcher;
import com.riccon.worker.workflow.node.service.WorkflowResultAssembler;
import com.riccon.worker.workflow.node.service.WorkflowTemplateRenderer;
import com.riccon.worker.workflow.queue.domain.ClaimedWorkflowRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 审批集成测试夹具。
 *
 * <p>复用 {@link WorkflowEngineTestSupport} 的租户/账号/流程/运行/节点夹具，额外提供角色授权、
 * 审批行、assignee、审计与全局工具目录读写。每个测试使用唯一租户隔离；{@code audit_event} 是
 * append-only 分区表，只能按本测试租户/目标 ID 断言，不做清理。
 */
public final class WorkflowApprovalTestSupport {

    /** 引擎夹具。 */
    private final WorkflowEngineTestSupport engine;

    /** 测试 JDBC。 */
    private final JdbcClient jdbcClient;

    /** JSON 解析。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建夹具。
     *
     * @param jdbcClient JDBC
     * @param objectMapper JSON 解析
     */
    public WorkflowApprovalTestSupport(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.engine = new WorkflowEngineTestSupport(jdbcClient, objectMapper);
    }

    /**
     * 清理可领取运行，避免共享上下文后台槽位竞争。
     */
    public void drainPendingRuns() {
        engine.drainPendingRuns();
    }

    /**
     * 创建租户。
     *
     * @param tenant 租户
     */
    public void createTenant(String tenant) {
        engine.createTenant(tenant);
    }

    /**
     * 创建 ACTIVE 账号。
     *
     * @param tenant 租户
     * @return 账号 ID
     */
    public UUID createUser(String tenant) {
        return createUser(tenant, "ACTIVE");
    }

    /**
     * 创建指定状态账号。
     *
     * @param tenant 租户
     * @param status ACTIVE 或 DISABLED
     * @return 账号 ID
     */
    public UUID createUser(String tenant, String status) {
        UUID userId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO app_user (tenant_id, id, username, display_name, status, auth_source)
                        VALUES (:tenantId, :id, :username, 'Approval User', :status, 'LOCAL')
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(userId))
                .param("username", "approval-" + UUID.randomUUID())
                .param("status", status)
                .update();
        return userId;
    }

    /**
     * 授予角色；角色行不存在时按租户创建。
     *
     * @param tenant 租户
     * @param userId 账号
     * @param roleCode 角色码
     */
    public void assignRole(String tenant, UUID userId, String roleCode) {
        UUID roleId = jdbcClient
                .sql("""
                        SELECT id FROM role WHERE tenant_id = :tenantId AND code = :code
                        """)
                .param("tenantId", tenant)
                .param("code", roleCode)
                .query((rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes("id")))
                .optional()
                .orElseGet(() -> {
                    UUID created = UuidV7.generate();
                    jdbcClient
                            .sql("""
                                    INSERT INTO role (tenant_id, id, code, name, is_system)
                                    VALUES (:tenantId, :id, :code, :name, TRUE)
                                    """)
                            .param("tenantId", tenant)
                            .param("id", UuidBinary.toBytes(created))
                            .param("code", roleCode)
                            .param("name", "Test " + roleCode)
                            .update();
                    return created;
                });
        jdbcClient
                .sql("""
                        INSERT INTO user_role (tenant_id, user_id, role_id, granted_by)
                        VALUES (:tenantId, :userId, :roleId, NULL)
                        """)
                .param("tenantId", tenant)
                .param("userId", UuidBinary.toBytes(userId))
                .param("roleId", UuidBinary.toBytes(roleId))
                .update();
    }

    /**
     * 创建流程定义与发布版本。
     *
     * @param tenant 租户
     * @param owner 所有者
     * @param graphJson 图 JSON
     * @return 种子
     */
    public Seed createWorkflow(String tenant, UUID owner, String graphJson) {
        int nodeCount = parseGraph(graphJson).nodes().size();
        return engine.createWorkflow(tenant, owner, graphJson, nodeCount);
    }

    /**
     * 插入 RUNNING 运行。
     *
     * @param tenant 租户
     * @param seed 流程种子
     * @param requester 发起人
     * @param inputsJson 输入
     * @return 运行 ID
     */
    public UUID insertRunningRun(String tenant, Seed seed, UUID requester, String inputsJson) {
        return engine.insertRun(tenant, seed, requester, inputsJson, "RUNNING", "test-lease", "s", 0L);
    }

    /**
     * 读取运行行。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @return 运行行
     */
    public RunRow readRun(String tenant, UUID runId) {
        return engine.readRun(tenant, runId);
    }

    /**
     * 读取节点尝试。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @return 尝试列表
     */
    public List<NodeRow> readNodes(String tenant, UUID runId, String nodeKey) {
        return engine.readNodes(tenant, runId, nodeKey);
    }

    /**
     * 读取全部节点尝试。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @return 尝试列表
     */
    public List<NodeRow> readAllNodes(String tenant, UUID runId) {
        return engine.readAllNodes(tenant, runId);
    }

    /**
     * 读取运行最新审批。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @return 审批行
     */
    public ApprovalRow readApproval(String tenant, UUID runId) {
        return jdbcClient
                .sql("""
                        SELECT id, workflow_run_id, workflow_id, node_key, attempt, status,
                               assignee_type, assignee_role_code, action_kind, target_node_key,
                               target_tool_id, target_tool_name, params_preview, params_ciphertext,
                               params_key_id, params_sha256, plan_sha256,
                               requested_by, decided_by, decided_at, decision_comment, created_at
                          FROM workflow_approval
                         WHERE tenant_id = :tenantId AND workflow_run_id = :runId
                         ORDER BY attempt DESC, created_at DESC
                         LIMIT 1
                        """)
                .param("tenantId", tenant)
                .param("runId", UuidBinary.toBytes(runId))
                .query(WorkflowApprovalTestSupport::mapApproval)
                .single();
    }

    /**
     * 读取审批 assignee。
     *
     * @param tenant 租户
     * @param approvalId 审批 ID
     * @return 用户 ID 列表
     */
    public List<UUID> readAssignees(String tenant, UUID approvalId) {
        return jdbcClient
                .sql("""
                        SELECT user_id FROM workflow_approval_assignee
                         WHERE tenant_id = :tenantId AND approval_id = :approvalId
                         ORDER BY user_id
                        """)
                .param("tenantId", tenant)
                .param("approvalId", UuidBinary.toBytes(approvalId))
                .query((rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes("user_id")))
                .list();
    }

    /**
     * 读取指定动作/目标的审计事件。
     *
     * @param tenant 租户
     * @param action 动作
     * @param targetId 目标 ID
     * @return 审计行
     */
    public List<AuditRow> readAudit(String tenant, String action, String targetId) {
        return jdbcClient
                .sql("""
                        SELECT action, outcome, actor_type, actor_id, target_type, target_id,
                               trace_id, details
                          FROM audit_event
                         WHERE tenant_id = :tenantId AND action = :action AND target_id = :targetId
                         ORDER BY occurred_at ASC
                        """)
                .param("tenantId", tenant)
                .param("action", action)
                .param("targetId", targetId)
                .query((rs, rowNum) -> new AuditRow(
                        rs.getString("action"),
                        rs.getString("outcome"),
                        rs.getString("actor_type"),
                        rs.getString("actor_id"),
                        rs.getString("target_type"),
                        rs.getString("target_id"),
                        rs.getString("trace_id"),
                        rs.getString("details")))
                .list();
    }

    /**
     * 创建全局 ACTIVE MCP 工具。
     *
     * @return 工具 ID
     */
    public UUID createMcpTool() {
        UUID serverId = UuidV7.generate();
        UUID toolId = UuidV7.generate();
        String suffix = UUID.randomUUID().toString();
        jdbcClient
                .sql("""
                        INSERT INTO mcp_server (id, code, name, transport, endpoint, status)
                        VALUES (:id, :code, 'Approval Test Server', 'HTTP', 'http://127.0.0.1:19090/mcp', 'ACTIVE')
                        """)
                .param("id", UuidBinary.toBytes(serverId))
                .param("code", "approval-server-" + suffix)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO mcp_tool (id, server_id, name, description, input_schema, risk_level,
                                              requires_approval, status)
                        VALUES (:id, :serverId, :name, NULL, '{"type":"object"}', 'WRITE', TRUE, 'ACTIVE')
                        """)
                .param("id", UuidBinary.toBytes(toolId))
                .param("serverId", UuidBinary.toBytes(serverId))
                .param("name", "approval_tool_" + suffix)
                .update();
        return toolId;
    }

    /**
     * 执行串行引擎直到审批停车或运行结束。
     *
     * @param engine 引擎
     * @param tenant 租户
     * @param runId 运行 ID
     */
    public void execute(WorkflowEngine engine, String tenant, UUID runId) {
        TestLease lease = new TestLease(runId, tenant);
        ClaimedWorkflowRun claim = new ClaimedWorkflowRun(
                runId,
                tenant,
                com.riccon.platform.domain.workflow.WorkflowRunStatus.PENDING,
                Instant.now().plusSeconds(90),
                "test-lease");
        com.riccon.platform.common.tenant.TenantContext.runWithTenant(
                com.riccon.platform.common.tenant.TenantId.of(tenant), () -> engine.execute(claim, lease));
    }

    /**
     * 模拟运行队列领取：把 PENDING 运行置 RUNNING 并写入测试租约。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     */
    public void claimRun(String tenant, UUID runId) {
        jdbcClient
                .sql("""
                        UPDATE workflow_run
                           SET status = 'RUNNING',
                               lease_owner = 'test-lease',
                               lease_until = DATE_ADD(NOW(6), INTERVAL 90 SECOND),
                               updated_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :runId AND status = 'PENDING'
                        """)
                .param("tenantId", tenant)
                .param("runId", UuidBinary.toBytes(runId))
                .update();
    }

    /**
     * 构造串行引擎。
     *
     * @param repository 引擎仓储
     * @param assembler 结果组装
     * @param properties 运行配置
     * @param clock 时钟
     * @param executors 节点执行器
     * @return 引擎
     */
    public WorkflowEngine engine(
            WorkflowEngineRepository repository,
            WorkflowResultAssembler assembler,
            com.riccon.worker.workflow.config.WorkflowRuntimeProperties properties,
            MutableClock clock,
            List<WorkflowNodeExecutor> executors) {
        WorkflowNodeDispatcher dispatcher = new WorkflowNodeDispatcher(executors);
        return new WorkflowEngine(repository, dispatcher, assembler, properties, objectMapper, clock);
    }

    /**
     * 构造 START/END 真实执行器与给定审批执行器的分派列表。
     *
     * @param approvalExecutor 审批执行器
     * @return 执行器列表
     */
    public List<WorkflowNodeExecutor> defaultExecutors(WorkflowNodeExecutor approvalExecutor) {
        WorkflowTemplateRenderer renderer = new WorkflowTemplateRenderer();
        return List.of(
                new com.riccon.worker.workflow.node.service.StartNodeExecutor(renderer),
                new com.riccon.worker.workflow.node.service.EndNodeExecutor(new WorkflowResultAssembler(objectMapper)),
                approvalExecutor);
    }

    /**
     * 解析图。
     *
     * @param json 图 JSON
     * @return 图
     */
    public WorkflowGraph parseGraph(String json) {
        return WorkflowGraphJson.parse(json, WorkflowEngineLimits.MAX_GRAPH_NODES);
    }

    /**
     * 节点 JSON。
     *
     * @param id key
     * @param type 类型
     * @param name 名称
     * @param config 配置 JSON
     * @return 节点 JSON
     */
    public static String node(String id, String type, String name, String config) {
        return WorkflowEngineTestSupport.node(id, type, name, config);
    }

    /**
     * 边 JSON。
     *
     * @param id 边 ID
     * @param source 源
     * @param target 目标
     * @return 边 JSON
     */
    public static String edge(String id, String source, String target) {
        return WorkflowEngineTestSupport.edge(id, source, target, null);
    }

    /**
     * 图 JSON。
     *
     * @param nodes 节点
     * @param edges 边
     * @return 图 JSON
     */
    public static String graph(List<String> nodes, List<String> edges) {
        return WorkflowEngineTestSupport.graph(nodes, edges);
    }

    /**
     * 解析 JSON。
     *
     * @param json JSON
     * @return JSON 树
     */
    public tools.jackson.databind.JsonNode json(String json) {
        return objectMapper.readTree(json);
    }

    /**
     * 映射审批行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 审批行
     * @throws SQLException 读取失败
     */
    private static ApprovalRow mapApproval(ResultSet rs, int rowNum) throws SQLException {
        return new ApprovalRow(
                UuidBinary.fromBytes(rs.getBytes("id")),
                UuidBinary.fromBytes(rs.getBytes("workflow_run_id")),
                UuidBinary.fromBytes(rs.getBytes("workflow_id")),
                rs.getString("node_key"),
                rs.getInt("attempt"),
                rs.getString("status"),
                rs.getString("assignee_type"),
                rs.getString("assignee_role_code"),
                rs.getString("action_kind"),
                rs.getString("target_node_key"),
                rs.getBytes("target_tool_id") == null ? null : UuidBinary.fromBytes(rs.getBytes("target_tool_id")),
                rs.getString("target_tool_name"),
                rs.getString("params_preview"),
                rs.getBytes("params_ciphertext"),
                rs.getString("params_key_id"),
                rs.getString("params_sha256"),
                rs.getString("plan_sha256"),
                UuidBinary.fromBytes(rs.getBytes("requested_by")),
                rs.getBytes("decided_by") == null ? null : UuidBinary.fromBytes(rs.getBytes("decided_by")),
                rs.getObject("decided_at", LocalDateTime.class),
                rs.getString("decision_comment"),
                rs.getObject("created_at", LocalDateTime.class));
    }

    /**
     * 审批行。
     *
     * @param id 审批 ID
     * @param runId 运行 ID
     * @param workflowId 流程 ID
     * @param nodeKey 节点 key
     * @param attempt 尝试号
     * @param status 状态
     * @param assigneeType 审批人类型
     * @param assigneeRoleCode 角色码
     * @param actionKind 动作
     * @param targetNodeKey 目标节点
     * @param targetToolId 目标工具
     * @param targetToolName 工具名
     * @param paramsPreview 预览
     * @param paramsCiphertext 密文
     * @param paramsKeyId key id
     * @param paramsSha256 参数哈希
     * @param planSha256 计划哈希
     * @param requestedBy 发起人
     * @param decidedBy 审批人
     * @param decidedAt 决定时间
     * @param decisionComment 理由
     * @param createdAt 创建时间
     */
    public record ApprovalRow(
            UUID id,
            UUID runId,
            UUID workflowId,
            String nodeKey,
            int attempt,
            String status,
            String assigneeType,
            String assigneeRoleCode,
            String actionKind,
            String targetNodeKey,
            UUID targetToolId,
            String targetToolName,
            String paramsPreview,
            byte[] paramsCiphertext,
            String paramsKeyId,
            String paramsSha256,
            String planSha256,
            UUID requestedBy,
            UUID decidedBy,
            LocalDateTime decidedAt,
            String decisionComment,
            LocalDateTime createdAt) {}

    /**
     * 审计行。
     *
     * @param action 动作
     * @param outcome 结果
     * @param actorType 操作者类型
     * @param actorId 操作者
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param traceId 追踪
     * @param detailsJson details
     */
    public record AuditRow(
            String action,
            String outcome,
            String actorType,
            String actorId,
            String targetType,
            String targetId,
            String traceId,
            String detailsJson) {}
}
