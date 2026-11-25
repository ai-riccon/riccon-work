package com.riccon.worker.workflow.tool.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowApprovalDecision;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.workflow.approval.WorkflowApprovalTestSupport.ApprovalRow;
import com.riccon.worker.workflow.approval.WorkflowApprovalTestSupport.AuditRow;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalDecisionCommand;
import com.riccon.worker.workflow.approval.service.WorkflowApprovalDecisionService;
import com.riccon.worker.workflow.approval.service.WorkflowApprovalExecutor;
import com.riccon.worker.workflow.config.WorkflowRuntimeProperties;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.MutableClock;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.RunRow;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.Seed;
import com.riccon.worker.workflow.engine.repository.WorkflowEngineRepository;
import com.riccon.worker.workflow.engine.service.WorkflowEngine;
import com.riccon.worker.workflow.node.service.WorkflowResultAssembler;
import com.riccon.worker.workflow.tool.WorkflowToolTestSupport;
import com.riccon.worker.workflow.tool.WorkflowToolTestSupport.InvocationRow;
import com.riccon.worker.workflow.tool.WorkflowToolTestSupport.ToolSeed;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * IT-WF-104 / IT-HITL-001 / SEC-WF-004 / MCP-007：工作流工具执行策略、plan 绑定、重试重审批与未知副作用。
 *
 * <p>使用真实 MySQL、真实 T07a 审批执行器/决定服务、真实 stdio MCP mock server 与真实 T07b 工具执行器：
 *
 * <ul>
 *   <li>READ/WRITE 工具都必须在 {@code workflow_approval} APPROVED 且 {@code plan_sha256} 重算匹配后
 *       才允许调用；J2 的 {@code READ_ONLY} 拒绝 WRITE 由 {@code McpGatewayIntegrationTest} 与
 *       {@code McpCallExecutorPolicyTest} 回归守住；
 *   <li>一个审批最多执行一次；STARTED 无终态按 {@code WF_SIDE_EFFECT_UNKNOWN} fail-closed；
 *   <li>工具重试必须重新走审批节点；旧审批复用被拒绝；
 *   <li>plan/参数哈希漂移、未授权工具、缺审批都不发起外部调用。
 * </ul>
 */
@Tag("integration")
@TestPropertySource(properties = "riccon.workflow.claim-enabled=false")
@DisplayName("IT-WF-104/SEC-WF-004 工作流工具执行")
class WorkflowToolNodeExecutorIntegrationTest extends WorkerIntegrationTestBase {

    /** 真实审批执行器（T07a）。 */
    @Autowired
    private WorkflowApprovalExecutor approvalExecutor;

    /** 真实审批决定服务（T07a）。 */
    @Autowired
    private WorkflowApprovalDecisionService decisionService;

    /** 真实工具执行器（T07b）。 */
    @Autowired
    private WorkflowToolNodeExecutor toolExecutor;

    /** 引擎仓储。 */
    @Autowired
    private WorkflowEngineRepository engineRepository;

    /** 结果组装。 */
    @Autowired
    private WorkflowResultAssembler resultAssembler;

    /** 运行配置。 */
    @Autowired
    private WorkflowRuntimeProperties properties;

    /** JSON。 */
    @Autowired
    private ObjectMapper objectMapper;

    /** 测试夹具。 */
    private WorkflowToolTestSupport support;

    /** 固定时钟。 */
    private MutableClock clock;

    /** 本测试类创建的租户；用于清理 workflow_tool_invocation 外键引用。 */
    private final Set<String> tenants = new LinkedHashSet<>();

    /** 每个测试恢复全局 MCP 目录并重建夹具。 */
    @BeforeEach
    void setUp() {
        resetMcpCatalog();
        support = new WorkflowToolTestSupport(jdbcClient, objectMapper);
        support.drainPendingRuns();
        clock = new MutableClock(Instant.now());
    }

    /**
     * 清理本测试类产生的工具调用记录。
     *
     * <p>{@code workflow_tool_invocation.mcp_tool_id} 外键指向全局 {@code mcp_tool}；共享基类的
     * {@code resetMcpCatalog()} 会先删工具，若本类留下的调用记录仍在就会阻塞后续测试类。按本类
     * 唯一租户精确删除，不触碰其他测试的数据。
     */
    @AfterEach
    void cleanupInvocationReferences() {
        for (String tenant : tenants) {
            jdbcClient
                    .sql("DELETE FROM workflow_tool_invocation WHERE tenant_id = :tenantId")
                    .param("tenantId", tenant)
                    .update();
        }
        tenants.clear();
    }

    /** READ 工具审批通过后调用一次，写调用记录、节点绑定与带 workflowRunId 的审计。 */
    @Test
    @DisplayName("IT-HITL-001/MCP-007 READ 工具审批后执行一次并审计")
    void readTool_approved_executesOnce() {
        String tenant = newTenant("wf-tool-read");
        support.createTenant(tenant);
        UUID requester = support.createUser(tenant);
        UUID approver = support.createUser(tenant);
        ToolSeed tool = support.createEchoTool(tenant, "READ");
        Seed seed = support.createWorkflow(
                tenant,
                requester,
                WorkflowToolTestSupport.toolApprovalGraph(
                        tool.toolId(), requester, approver, "{\"text\":\"{{input.topic}}\"}"));
        UUID runId = support.insertRunningRun(tenant, seed, requester, "{\"topic\":\"市场\"}");

        runToApproval(tenant, runId);
        ApprovalRow approval = approve(tenant, runId, approver);
        resumeToEnd(tenant, runId);

        RunRow run = support.readRun(tenant, runId);
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.errorCode()).isNull();
        assertThat(run.resultJson()).contains("echo: 市场").contains("SUCCEEDED");

        InvocationRow invocation = support.readInvocation(tenant, approval.id()).orElseThrow();
        assertThat(invocation.status()).isEqualTo("SUCCEEDED");
        assertThat(invocation.toolName()).isEqualTo(WorkflowToolTestSupport.TOOL_NAME);
        assertThat(invocation.paramsSha256()).isEqualTo(approval.paramsSha256());
        assertThat(invocation.resultSha256()).matches("^[0-9a-f]{64}$");
        assertThat(invocation.resultSizeBytes()).isNotNull().isPositive();
        assertThat(invocation.durationMs()).isNotNull();
        assertThat(invocation.errorCode()).isNull();
        assertThat(support.readNodes(tenant, runId, "tool")).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("SUCCEEDED");
            assertThat(row.outputJson()).contains("\"resultSha256\"");
        });

        List<AuditRow> audits =
                support.readAudit(tenant, "mcp.tool.invoked", tool.toolId().toString());
        assertThat(audits).anySatisfy(row -> {
            JsonNode details = readJson(row.detailsJson());
            if ("SUCCESS".equals(row.outcome())) {
                assertThat(details.path("workflowRunId").asString()).isEqualTo(runId.toString());
                assertThat(details.path("workflowNodeId").asString()).isEqualTo("tool");
                assertThat(details.path("approvalId").asString())
                        .isEqualTo(approval.id().toString());
                assertThat(details.path("resultSha256").asString()).isEqualTo(invocation.resultSha256());
            }
        });
        assertThat(audits).anyMatch(row -> "SUCCESS".equals(row.outcome()));
        assertThat(audits).noneMatch(row -> "DENIED".equals(row.outcome()));
    }

    /** WRITE 工具只在审批绑定后允许执行（M06 APPROVAL_BOUND_WRITE）。 */
    @Test
    @DisplayName("IT-WF-104/SEC-WF-004 WRITE 工具审批后执行")
    void writeTool_approved_executes() {
        String tenant = newTenant("wf-tool-write");
        support.createTenant(tenant);
        UUID requester = support.createUser(tenant);
        UUID approver = support.createUser(tenant);
        ToolSeed tool = support.createEchoTool(tenant, "WRITE");
        Seed seed = support.createWorkflow(
                tenant,
                requester,
                WorkflowToolTestSupport.toolApprovalGraph(
                        tool.toolId(), requester, approver, "{\"text\":\"{{input.topic}}\"}"));
        UUID runId = support.insertRunningRun(tenant, seed, requester, "{\"topic\":\"报告\"}");

        runToApproval(tenant, runId);
        ApprovalRow approval = approve(tenant, runId, approver);
        resumeToEnd(tenant, runId);

        assertThat(support.readRun(tenant, runId).status()).isEqualTo("SUCCEEDED");
        assertThat(support.readInvocation(tenant, approval.id()).orElseThrow().status())
                .isEqualTo("SUCCEEDED");
        assertThat(support.invocationCount(tenant, runId)).isEqualTo(1);
    }

    /** plan_sha256 漂移：不发起调用，写 DENIED 审计。 */
    @Test
    @DisplayName("SEC-WF-004 plan 漂移失败关闭且不调用工具")
    void planDrift_failsClosedWithoutInvocation() {
        String tenant = newTenant("wf-tool-plan-drift");
        support.createTenant(tenant);
        UUID requester = support.createUser(tenant);
        UUID approver = support.createUser(tenant);
        ToolSeed tool = support.createEchoTool(tenant, "WRITE");
        Seed seed = support.createWorkflow(
                tenant,
                requester,
                WorkflowToolTestSupport.toolApprovalGraph(
                        tool.toolId(), requester, approver, "{\"text\":\"{{input.topic}}\"}"));
        UUID runId = support.insertRunningRun(tenant, seed, requester, "{\"topic\":\"市场\"}");

        runToApproval(tenant, runId);
        ApprovalRow approval = approve(tenant, runId, approver);
        jdbcClient
                .sql(
                        "UPDATE workflow_approval SET plan_sha256 = REPEAT('0', 64) WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenant)
                .param("id", com.riccon.worker.persistence.UuidBinary.toBytes(approval.id()))
                .update();

        resumeToEnd(tenant, runId);

        RunRow run = support.readRun(tenant, runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_PLAN_DRIFT");
        assertThat(support.invocationCount(tenant, runId)).isZero();
        assertThat(support.readNodes(tenant, runId, "tool")).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("FAILED");
            assertThat(row.errorCode()).isEqualTo("WF_PLAN_DRIFT");
        });
        assertThat(support.readAudit(tenant, "mcp.tool.invoked", tool.toolId().toString()))
                .anyMatch(row -> "DENIED".equals(row.outcome()));
    }

    /** 参数哈希漂移：密文不可重放，不发起调用。 */
    @Test
    @DisplayName("SEC-WF-004 参数哈希漂移失败关闭")
    void paramsDrift_failsClosedWithoutInvocation() {
        String tenant = newTenant("wf-tool-params-drift");
        support.createTenant(tenant);
        UUID requester = support.createUser(tenant);
        UUID approver = support.createUser(tenant);
        ToolSeed tool = support.createEchoTool(tenant, "READ");
        Seed seed = support.createWorkflow(
                tenant,
                requester,
                WorkflowToolTestSupport.toolApprovalGraph(
                        tool.toolId(), requester, approver, "{\"text\":\"{{input.topic}}\"}"));
        UUID runId = support.insertRunningRun(tenant, seed, requester, "{\"topic\":\"市场\"}");

        runToApproval(tenant, runId);
        ApprovalRow approval = approve(tenant, runId, approver);
        jdbcClient
                .sql(
                        "UPDATE workflow_approval SET params_sha256 = REPEAT('1', 64) WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenant)
                .param("id", com.riccon.worker.persistence.UuidBinary.toBytes(approval.id()))
                .update();

        resumeToEnd(tenant, runId);

        assertThat(support.readRun(tenant, runId).errorCode()).isEqualTo("WF_PLAN_DRIFT");
        assertThat(support.invocationCount(tenant, runId)).isZero();
    }

    /** 工具节点没有前置审批：失败关闭，不调用工具。 */
    @Test
    @DisplayName("SEC-WF-004 缺少工具审批失败关闭")
    void missingApproval_failsClosed() {
        String tenant = newTenant("wf-tool-no-approval");
        support.createTenant(tenant);
        UUID requester = support.createUser(tenant);
        UUID approver = support.createUser(tenant);
        ToolSeed tool = support.createEchoTool(tenant, "READ");
        Seed seed = support.createWorkflow(
                tenant,
                requester,
                WorkflowToolTestSupport.toolApprovalGraph(
                        tool.toolId(), requester, approver, "{\"text\":\"{{input.topic}}\"}"));
        UUID runId = support.insertRunningRun(tenant, seed, requester, "{\"topic\":\"市场\"}");
        support.setCurrentNode(tenant, runId, "tool");

        support.execute(engine(), tenant, runId);

        RunRow run = support.readRun(tenant, runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_APPROVAL_REQUIRED");
        assertThat(support.invocationCount(tenant, runId)).isZero();
        assertThat(support.readNodes(tenant, runId, "tool")).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("FAILED");
            assertThat(row.errorCode()).isEqualTo("WF_APPROVAL_REQUIRED");
        });
    }

    /** 未授权/停用工具：不发起调用，写 DENIED 审计。 */
    @Test
    @DisplayName("SEC-WF-004 工具租户授权在执行时复查")
    void unauthorizedTool_failsClosedWithoutInvocation() {
        String tenant = newTenant("wf-tool-unauthorized");
        support.createTenant(tenant);
        UUID requester = support.createUser(tenant);
        UUID approver = support.createUser(tenant);
        ToolSeed tool = support.createEchoTool(tenant, "READ");
        Seed seed = support.createWorkflow(
                tenant,
                requester,
                WorkflowToolTestSupport.toolApprovalGraph(
                        tool.toolId(), requester, approver, "{\"text\":\"{{input.topic}}\"}"));
        UUID runId = support.insertRunningRun(tenant, seed, requester, "{\"topic\":\"市场\"}");

        runToApproval(tenant, runId);
        approve(tenant, runId, approver);
        support.revoke(tenant, tool.toolId());

        resumeToEnd(tenant, runId);

        RunRow run = support.readRun(tenant, runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_TOOL_NOT_AUTHORIZED");
        assertThat(support.invocationCount(tenant, runId)).isZero();
        assertThat(support.readAudit(tenant, "mcp.tool.invoked", tool.toolId().toString()))
                .anyMatch(row -> "DENIED".equals(row.outcome()));
    }

    /** STARTED 无终态：外部副作用未知，fail-closed，不重放。 */
    @Test
    @DisplayName("IT-WF-104 未知副作用失败关闭")
    void startedInvocation_failsUnknown() {
        String tenant = newTenant("wf-tool-unknown");
        support.createTenant(tenant);
        UUID requester = support.createUser(tenant);
        UUID approver = support.createUser(tenant);
        ToolSeed tool = support.createEchoTool(tenant, "WRITE");
        Seed seed = support.createWorkflow(
                tenant,
                requester,
                WorkflowToolTestSupport.toolApprovalGraph(
                        tool.toolId(), requester, approver, "{\"text\":\"{{input.topic}}\"}"));
        UUID runId = support.insertRunningRun(tenant, seed, requester, "{\"topic\":\"市场\"}");

        runToApproval(tenant, runId);
        ApprovalRow approval = approve(tenant, runId, approver);
        support.insertStartedInvocation(
                tenant,
                runId,
                approval.id(),
                tool.toolId(),
                WorkflowToolTestSupport.TOOL_NAME,
                approval.paramsSha256());

        resumeToEnd(tenant, runId);

        RunRow run = support.readRun(tenant, runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_SIDE_EFFECT_UNKNOWN");
        assertThat(support.readInvocation(tenant, approval.id()).orElseThrow().status())
                .isEqualTo("STARTED");
        assertThat(support.invocationCount(tenant, runId)).isEqualTo(1);
    }

    /** 同一审批已有终态调用：不可复用，必须重新审批。 */
    @Test
    @DisplayName("IT-WF-104 审批最多执行一次")
    void terminalInvocation_requiresNewApproval() {
        String tenant = newTenant("wf-tool-consumed");
        support.createTenant(tenant);
        UUID requester = support.createUser(tenant);
        UUID approver = support.createUser(tenant);
        ToolSeed tool = support.createEchoTool(tenant, "READ");
        Seed seed = support.createWorkflow(
                tenant,
                requester,
                WorkflowToolTestSupport.toolApprovalGraph(
                        tool.toolId(), requester, approver, "{\"text\":\"{{input.topic}}\"}"));
        UUID runId = support.insertRunningRun(tenant, seed, requester, "{\"topic\":\"市场\"}");

        runToApproval(tenant, runId);
        ApprovalRow approval = approve(tenant, runId, approver);
        support.insertStartedInvocation(
                tenant,
                runId,
                approval.id(),
                tool.toolId(),
                WorkflowToolTestSupport.TOOL_NAME,
                approval.paramsSha256());
        support.markInvocationTerminal(tenant, approval.id(), "SUCCEEDED");

        resumeToEnd(tenant, runId);

        RunRow run = support.readRun(tenant, runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_APPROVAL_REQUIRED");
        assertThat(support.invocationCount(tenant, runId)).isEqualTo(1);
    }

    /**
     * AC-08：写工具重试必须重新审批。
     *
     * <p>第一次执行因授权撤销失败；原地重试（新 attempt）复用旧审批被拒绝；回到审批节点重新审批后
     * 再次执行成功。整个过程最多一次外部调用。
     */
    @Test
    @DisplayName("IT-WF-104 写工具重试重新审批")
    void retry_requiresFreshApproval() {
        String tenant = newTenant("wf-tool-retry");
        support.createTenant(tenant);
        UUID requester = support.createUser(tenant);
        UUID approver = support.createUser(tenant);
        ToolSeed tool = support.createEchoTool(tenant, "WRITE");
        Seed seed = support.createWorkflow(
                tenant,
                requester,
                WorkflowToolTestSupport.toolApprovalGraph(
                        tool.toolId(), requester, approver, "{\"text\":\"{{input.topic}}\"}"));
        UUID runId = support.insertRunningRun(tenant, seed, requester, "{\"topic\":\"市场\"}");

        runToApproval(tenant, runId);
        ApprovalRow firstApproval = approve(tenant, runId, approver);

        // 第一次尝试：授权撤销，失败在调用前，不产生调用记录。
        support.revoke(tenant, tool.toolId());
        resumeToEnd(tenant, runId);
        assertThat(support.readRun(tenant, runId).errorCode()).isEqualTo("WF_TOOL_NOT_AUTHORIZED");
        assertThat(support.invocationCount(tenant, runId)).isZero();

        // 原地重试：旧审批创建时间早于上一次工具尝试，必须重新审批。
        support.grant(tenant, tool.toolId());
        syncClock();
        support.prepareRetry(tenant, runId, "tool", WorkflowNodeType.MCP_TOOL, "工具");
        support.execute(engine(), tenant, runId);
        RunRow staleRetry = support.readRun(tenant, runId);
        assertThat(staleRetry.status()).isEqualTo("FAILED");
        assertThat(staleRetry.errorCode()).isEqualTo("WF_APPROVAL_REQUIRED");
        assertThat(support.invocationCount(tenant, runId)).isZero();

        // 重新走审批节点：新审批创建时间晚于上一次尝试，允许继续。
        syncClock();
        support.prepareRetry(tenant, runId, "approval", WorkflowNodeType.APPROVAL, "审批");
        support.execute(engine(), tenant, runId);
        assertThat(support.readRun(tenant, runId).status()).isEqualTo("WAITING_APPROVAL");
        ApprovalRow secondApproval = approve(tenant, runId, approver);
        assertThat(secondApproval.id()).isNotEqualTo(firstApproval.id());

        syncClock();
        resumeToEnd(tenant, runId);

        RunRow run = support.readRun(tenant, runId);
        assertThat(run.status()).as("run=%s", run).isEqualTo("SUCCEEDED");
        assertThat(support.invocationCount(tenant, runId)).isEqualTo(1);
        assertThat(support.readInvocation(tenant, secondApproval.id())
                        .orElseThrow()
                        .status())
                .isEqualTo("SUCCEEDED");
    }

    /**
     * 把测试时钟推进到当前真实时间。
     *
     * <p>T07a 审批执行器是真实 Spring Bean，用系统 Clock 写审批创建时间；引擎使用测试
     * {@link MutableClock}。为了让“新审批晚于上一次工具尝试”的判定在测试中成立，每次执行引擎前把
     * 测试时钟同步到真实时间；生产环境两者是同一个 Clock，不需要这个桥接。
     */
    private void syncClock() {
        Duration elapsed = Duration.between(clock.instant(), Instant.now());
        if (!elapsed.isNegative() && !elapsed.isZero()) {
            clock.advance(elapsed);
        }
    }

    /**
     * 生成并登记唯一租户，供 AfterEach 精确清理调用记录。
     *
     * @param prefix 可读前缀
     * @return 唯一租户
     */
    private String newTenant(String prefix) {
        String tenant = uniqueTenant(prefix);
        tenants.add(tenant);
        return tenant;
    }

    /**
     * 执行串行引擎到审批停车。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     */
    private void runToApproval(String tenant, UUID runId) {
        support.execute(engine(), tenant, runId);
    }

    /**
     * 批准运行最新审批并返回审批行。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param approver 审批人
     * @return 审批行
     */
    private ApprovalRow approve(String tenant, UUID runId, UUID approver) {
        ApprovalRow approval = support.readApproval(tenant, runId);
        TenantContext.callWithTenant(
                TenantId.of(tenant),
                () -> decisionService.decide(new WorkflowApprovalDecisionCommand(
                        TenantId.of(tenant),
                        approval.id(),
                        approver,
                        WorkflowApprovalDecision.APPROVE,
                        null,
                        "trace-approve")));
        return approval;
    }

    /**
     * 领取 PENDING 运行并执行到结束。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     */
    private void resumeToEnd(String tenant, UUID runId) {
        support.claimRun(tenant, runId);
        syncClock();
        support.execute(engine(), tenant, runId);
    }

    /**
     * 构造带真实审批与工具执行器的串行引擎。
     *
     * @return 引擎
     */
    private WorkflowEngine engine() {
        return support.engine(
                engineRepository,
                resultAssembler,
                properties,
                clock,
                support.executors(approvalExecutor, toolExecutor));
    }

    /**
     * 解析审计 details。
     *
     * @param json JSON
     * @return 树
     */
    private JsonNode readJson(String json) {
        return objectMapper.readTree(json);
    }
}
