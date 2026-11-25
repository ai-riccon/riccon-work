package com.riccon.worker.workflow.tool;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.worker.common.UuidV7;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.support.TestMcpCommands;
import com.riccon.worker.workflow.approval.WorkflowApprovalTestSupport;
import com.riccon.worker.workflow.approval.WorkflowApprovalTestSupport.ApprovalRow;
import com.riccon.worker.workflow.approval.WorkflowApprovalTestSupport.AuditRow;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.MutableClock;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.NodeRow;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.RunRow;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.Seed;
import com.riccon.worker.workflow.engine.domain.WorkflowGraph;
import com.riccon.worker.workflow.engine.repository.WorkflowEngineRepository;
import com.riccon.worker.workflow.engine.service.WorkflowEngine;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecutor;
import com.riccon.worker.workflow.node.service.WorkflowResultAssembler;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 工作流工具执行集成测试夹具（T07b）。
 *
 * <p>复用 T07a 的租户/账号/流程/审批夹具，并补充全局 MCP 目录（stdio mock server + echo 工具）与
 * {@code workflow_tool_invocation} 读写。每个测试使用唯一租户；全局目录由测试类在每个用例前
 * 通过 {@code WorkerIntegrationTestBase.resetMcpCatalog()} 恢复，避免固定 server code 冲突。
 */
public final class WorkflowToolTestSupport {

    /** 允许列表内的 server code。 */
    public static final String SERVER_CODE = "riccon-builtin";

    /** mock server 暴露的工具名。 */
    public static final String TOOL_NAME = "echo";

    /** 审批夹具。 */
    private final WorkflowApprovalTestSupport approval;

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
    public WorkflowToolTestSupport(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.approval = new WorkflowApprovalTestSupport(jdbcClient, objectMapper);
        this.engine = new WorkflowEngineTestSupport(jdbcClient, objectMapper);
    }

    /** 把其他测试遗留的可领取运行置为远期租约。 */
    public void drainPendingRuns() {
        approval.drainPendingRuns();
    }

    /**
     * 创建租户。
     *
     * @param tenant 租户
     */
    public void createTenant(String tenant) {
        approval.createTenant(tenant);
    }

    /**
     * 创建 ACTIVE 账号。
     *
     * @param tenant 租户
     * @return 账号 ID
     */
    public UUID createUser(String tenant) {
        return approval.createUser(tenant);
    }

    /**
     * 创建全局 stdio MCP server 与 echo 工具并授权给租户。
     *
     * @param tenant 租户
     * @param riskLevel READ 或 WRITE
     * @return 工具种子
     */
    public ToolSeed createEchoTool(String tenant, String riskLevel) {
        UUID serverId = UuidV7.generate();
        UUID toolId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO mcp_server (id, code, name, transport, command, status)
                        VALUES (:id, :code, 'Tool Test Server', 'STDIO', :command, 'ACTIVE')
                        """)
                .param("id", UuidBinary.toBytes(serverId))
                .param("code", SERVER_CODE)
                .param("command", TestMcpCommands.mockServer())
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO mcp_tool (id, server_id, name, description, input_schema, risk_level,
                                              requires_approval, status)
                        VALUES (:id, :serverId, :name, 'Echo tool', '{"type":"object"}', :risk, TRUE, 'ACTIVE')
                        """)
                .param("id", UuidBinary.toBytes(toolId))
                .param("serverId", UuidBinary.toBytes(serverId))
                .param("name", TOOL_NAME)
                .param("risk", riskLevel)
                .update();
        grant(tenant, toolId);
        return new ToolSeed(serverId, toolId);
    }

    /**
     * 授权工具给租户。
     *
     * @param tenant 租户
     * @param toolId 工具 ID
     */
    public void grant(String tenant, UUID toolId) {
        jdbcClient
                .sql("""
                        INSERT INTO mcp_tool_grant (tenant_id, mcp_tool_id, enabled)
                        VALUES (:tenantId, :toolId, TRUE)
                        """)
                .param("tenantId", tenant)
                .param("toolId", UuidBinary.toBytes(toolId))
                .update();
    }

    /**
     * 撤销租户授权。
     *
     * @param tenant 租户
     * @param toolId 工具 ID
     */
    public void revoke(String tenant, UUID toolId) {
        jdbcClient
                .sql("DELETE FROM mcp_tool_grant WHERE tenant_id = :tenantId AND mcp_tool_id = :toolId")
                .param("tenantId", tenant)
                .param("toolId", UuidBinary.toBytes(toolId))
                .update();
    }

    /**
     * 创建 START→APPROVAL→MCP_TOOL→END 图。
     *
     * @param toolId 工具 ID
     * @param requester 发起人
     * @param approver 审批人
     * @param argsTemplate 参数模板 JSON
     * @return 图 JSON
     */
    public static String toolApprovalGraph(UUID toolId, UUID requester, UUID approver, String argsTemplate) {
        String approvalConfig = "{\"assigneeType\":\"USER\",\"assigneeUserIds\":[\"" + requester + "\",\"" + approver
                + "\"],\"actionKind\":\"MCP_TOOL\",\"targetNodeKey\":\"tool\"}";
        String toolConfig = "{\"toolId\":\"" + toolId + "\",\"argsTemplate\":" + argsTemplate + "}";
        return WorkflowApprovalTestSupport.graph(
                List.of(
                        WorkflowApprovalTestSupport.node("s", "START", "开始", "{}"),
                        WorkflowApprovalTestSupport.node("approval", "APPROVAL", "审批", approvalConfig),
                        WorkflowApprovalTestSupport.node("tool", "MCP_TOOL", "工具", toolConfig),
                        WorkflowApprovalTestSupport.node("end", "END", "结束", "{\"resultFrom\":\"tool\"}")),
                List.of(
                        WorkflowApprovalTestSupport.edge("e1", "s", "approval"),
                        WorkflowApprovalTestSupport.edge("e2", "approval", "tool"),
                        WorkflowApprovalTestSupport.edge("e3", "tool", "end")));
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
        return approval.createWorkflow(tenant, owner, graphJson);
    }

    /**
     * 插入 RUNNING 运行，当前节点为 START。
     *
     * @param tenant 租户
     * @param seed 流程种子
     * @param requester 发起人
     * @param inputsJson 输入
     * @return 运行 ID
     */
    public UUID insertRunningRun(String tenant, Seed seed, UUID requester, String inputsJson) {
        return approval.insertRunningRun(tenant, seed, requester, inputsJson);
    }

    /**
     * 把运行当前节点改为指定节点（保持 RUNNING）。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     */
    public void setCurrentNode(String tenant, UUID runId, String nodeKey) {
        jdbcClient
                .sql("""
                        UPDATE workflow_run SET current_node_key = :nodeKey, updated_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("tenantId", tenant)
                .param("runId", UuidBinary.toBytes(runId))
                .param("nodeKey", nodeKey)
                .update();
    }

    /**
     * 模拟运行队列领取：把 PENDING 运行置 RUNNING 并写测试租约。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     */
    public void claimRun(String tenant, UUID runId) {
        approval.claimRun(tenant, runId);
    }

    /**
     * 模拟 API 重试：运行回到指定失败节点，新增 PENDING 尝试。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param nodeKey 失败节点
     * @param nodeType 节点类型
     * @param nodeName 节点名
     * @return 新尝试号
     */
    public int prepareRetry(String tenant, UUID runId, String nodeKey, WorkflowNodeType nodeType, String nodeName) {
        return engine.prepareRetry(tenant, runId, nodeKey, nodeType, nodeName, "test-lease");
    }

    /**
     * 执行串行引擎直到停车或结束。
     *
     * @param engine 引擎
     * @param tenant 租户
     * @param runId 运行 ID
     */
    public void execute(WorkflowEngine engine, String tenant, UUID runId) {
        approval.execute(engine, tenant, runId);
    }

    /**
     * 构造串行引擎。
     *
     * @param repository 引擎仓储
     * @param assembler 结果组装
     * @param properties 运行配置
     * @param clock 时钟
     * @param executors 执行器
     * @return 引擎
     */
    public WorkflowEngine engine(
            WorkflowEngineRepository repository,
            WorkflowResultAssembler assembler,
            com.riccon.worker.workflow.config.WorkflowRuntimeProperties properties,
            MutableClock clock,
            List<WorkflowNodeExecutor> executors) {
        return approval.engine(repository, assembler, properties, clock, executors);
    }

    /**
     * 构造 START/END/APPROVAL/MCP_TOOL 执行器分派列表。
     *
     * @param approvalExecutor 审批执行器
     * @param toolExecutor 工具执行器
     * @return 执行器列表
     */
    public List<WorkflowNodeExecutor> executors(
            WorkflowNodeExecutor approvalExecutor, WorkflowNodeExecutor toolExecutor) {
        List<WorkflowNodeExecutor> base = approval.defaultExecutors(approvalExecutor);
        List<WorkflowNodeExecutor> executors = new ArrayList<>(base);
        executors.add(toolExecutor);
        return executors;
    }

    /**
     * 读取运行行。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @return 运行行
     */
    public RunRow readRun(String tenant, UUID runId) {
        return approval.readRun(tenant, runId);
    }

    /**
     * 读取指定节点尝试。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @return 尝试列表
     */
    public List<NodeRow> readNodes(String tenant, UUID runId, String nodeKey) {
        return approval.readNodes(tenant, runId, nodeKey);
    }

    /**
     * 读取全部节点尝试。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @return 尝试列表
     */
    public List<NodeRow> readAllNodes(String tenant, UUID runId) {
        return approval.readAllNodes(tenant, runId);
    }

    /**
     * 读取最新审批。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @return 审批行
     */
    public ApprovalRow readApproval(String tenant, UUID runId) {
        return approval.readApproval(tenant, runId);
    }

    /**
     * 读取指定动作/目标的审计。
     *
     * @param tenant 租户
     * @param action 动作
     * @param targetId 目标 ID
     * @return 审计行
     */
    public List<AuditRow> readAudit(String tenant, String action, String targetId) {
        return approval.readAudit(tenant, action, targetId);
    }

    /**
     * 读取审批对应的工具调用记录。
     *
     * @param tenant 租户
     * @param approvalId 审批 ID
     * @return 调用行；不存在为空
     */
    public Optional<InvocationRow> readInvocation(String tenant, UUID approvalId) {
        return jdbcClient
                .sql("""
                        SELECT id, workflow_approval_id, tool_name, status, params_sha256,
                               result_sha256, result_size_bytes, error_code, duration_ms, started_at
                          FROM workflow_tool_invocation
                         WHERE tenant_id = :tenantId AND workflow_approval_id = :approvalId
                        """)
                .param("tenantId", tenant)
                .param("approvalId", UuidBinary.toBytes(approvalId))
                .query(WorkflowToolTestSupport::mapInvocation)
                .optional();
    }

    /**
     * 统计运行的调用记录数。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @return 数量
     */
    public int invocationCount(String tenant, UUID runId) {
        return jdbcClient
                .sql("""
                        SELECT COUNT(*) FROM workflow_tool_invocation
                         WHERE tenant_id = :tenantId AND workflow_run_id = :runId
                        """)
                .param("tenantId", tenant)
                .param("runId", UuidBinary.toBytes(runId))
                .query(Integer.class)
                .single();
    }

    /**
     * 直接插入 STARTED 调用记录（模拟插入后崩溃）。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param approvalId 审批 ID
     * @param toolId 工具 ID
     * @param toolName 工具名
     * @param paramsSha256 参数摘要
     */
    public void insertStartedInvocation(
            String tenant, UUID runId, UUID approvalId, UUID toolId, String toolName, String paramsSha256) {
        jdbcClient
                .sql("""
                        INSERT INTO workflow_tool_invocation (
                            tenant_id, id, workflow_run_id, workflow_approval_id, node_key, attempt,
                            mcp_tool_id, tool_name, status, params_sha256, params_preview,
                            started_at, created_at
                        ) VALUES (
                            :tenantId, :id, :runId, :approvalId, 'tool', 1,
                            :toolId, :toolName, 'STARTED', :paramsSha256, '{}',
                            NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(UuidV7.generate()))
                .param("runId", UuidBinary.toBytes(runId))
                .param("approvalId", UuidBinary.toBytes(approvalId))
                .param("toolId", UuidBinary.toBytes(toolId))
                .param("toolName", toolName)
                .param("paramsSha256", paramsSha256)
                .update();
    }

    /**
     * 直接把审批置为 APPROVED（用于并发/重复场景；正常路径应走决定服务）。
     *
     * @param tenant 租户
     * @param approvalId 审批 ID
     * @param approver 审批人
     */
    public void markApproved(String tenant, UUID approvalId, UUID approver) {
        jdbcClient
                .sql("""
                        UPDATE workflow_approval
                           SET status = 'APPROVED', decided_by = :approver, decided_at = NOW(6), updated_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :approvalId AND status = 'PENDING'
                        """)
                .param("tenantId", tenant)
                .param("approvalId", UuidBinary.toBytes(approvalId))
                .param("approver", UuidBinary.toBytes(approver))
                .update();
    }

    /**
     * 把调用记录改为终态（模拟已完成/已失败的历史调用）。
     *
     * @param tenant 租户
     * @param approvalId 审批 ID
     * @param status SUCCEEDED 或 FAILED
     */
    public void markInvocationTerminal(String tenant, UUID approvalId, String status) {
        jdbcClient
                .sql("""
                        UPDATE workflow_tool_invocation
                           SET status = :status, finished_at = NOW(6)
                         WHERE tenant_id = :tenantId AND workflow_approval_id = :approvalId
                        """)
                .param("tenantId", tenant)
                .param("approvalId", UuidBinary.toBytes(approvalId))
                .param("status", status)
                .update();
    }

    /**
     * 解析图。
     *
     * @param json 图 JSON
     * @return 图
     */
    public WorkflowGraph parseGraph(String json) {
        return approval.parseGraph(json);
    }

    /**
     * 在指定租户上下文内执行动作。
     *
     * @param action 动作
     * @param tenant 租户
     */
    public static void runInTenant(Runnable action, String tenant) {
        TenantContext.runWithTenant(TenantId.of(tenant), action);
    }

    /**
     * 映射调用行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 调用行
     * @throws SQLException 读取失败
     */
    private static InvocationRow mapInvocation(ResultSet rs, int rowNum) throws SQLException {
        LocalDateTime startedAt = rs.getObject("started_at", LocalDateTime.class);
        return new InvocationRow(
                UuidBinary.fromBytes(rs.getBytes("id")),
                UuidBinary.fromBytes(rs.getBytes("workflow_approval_id")),
                rs.getString("tool_name"),
                rs.getString("status"),
                rs.getString("params_sha256"),
                rs.getString("result_sha256"),
                rs.getObject("result_size_bytes", Long.class),
                rs.getString("error_code"),
                rs.getObject("duration_ms", Long.class),
                startedAt == null ? null : startedAt.toInstant(ZoneOffset.UTC));
    }

    /**
     * 工具种子。
     *
     * @param serverId server ID
     * @param toolId 工具 ID
     */
    public record ToolSeed(UUID serverId, UUID toolId) {}

    /**
     * 调用记录行。
     *
     * @param id 调用 ID
     * @param approvalId 审批 ID
     * @param toolName 工具名
     * @param status 状态
     * @param paramsSha256 参数摘要
     * @param resultSha256 结果摘要
     * @param resultSizeBytes 结果大小
     * @param errorCode 错误码
     * @param durationMs 耗时
     * @param startedAt 开始时间
     */
    public record InvocationRow(
            UUID id,
            UUID approvalId,
            String toolName,
            String status,
            String paramsSha256,
            String resultSha256,
            Long resultSizeBytes,
            String errorCode,
            Long durationMs,
            Instant startedAt) {}
}
