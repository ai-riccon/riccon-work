package com.riccon.worker.workflow.approval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.workflow.approval.WorkflowApprovalTestSupport;
import com.riccon.worker.workflow.approval.WorkflowApprovalTestSupport.ApprovalRow;
import com.riccon.worker.workflow.approval.WorkflowApprovalTestSupport.AuditRow;
import com.riccon.worker.workflow.config.WorkflowRuntimeProperties;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.MutableClock;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.RunRow;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.Seed;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphNodeConfig;
import com.riccon.worker.workflow.engine.repository.WorkflowEngineRepository;
import com.riccon.worker.workflow.engine.service.WorkflowEngine;
import com.riccon.worker.workflow.node.domain.WorkflowNodeFailureException;
import com.riccon.worker.workflow.node.service.WorkflowResultAssembler;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * IT-WF-108 / SEC-WF-001：审批创建、审批人解析、无审批人阻断、参数加密与计划绑定。
 *
 * <p>使用真实 MySQL、真实 {@link WorkflowApprovalExecutor} 与 T06b 串行引擎：RUNNING 运行从
 * START 推进到 APPROVAL 后必须停靠为 {@code WAITING_APPROVAL}（不是 SUCCEEDED/FAILED），
 * 审批人只包含本租户 ACTIVE 且非发起人的账号；无可用审批人时运行保持等待并写
 * {@code WF_NO_APPROVER}，不自动放行。工具参数落库为密文 + 哈希 + 脱敏预览，AAD 绑定审批行。
 */
@Tag("integration")
@TestPropertySource(properties = "riccon.workflow.claim-enabled=false")
@DisplayName("IT-WF-108/SEC-WF-001 审批执行器")
class WorkflowApprovalExecutorIntegrationTest extends WorkerIntegrationTestBase {

    /** 真实审批执行器（Spring 代理，含事务）。 */
    @Autowired
    private WorkflowApprovalExecutor approvalExecutor;

    /** 审批参数服务（解密校验）。 */
    @Autowired
    private WorkflowApprovalParamsService paramsService;

    /** 计划哈希。 */
    @Autowired
    private WorkflowApprovalPlanHasher planHasher;

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
    private WorkflowApprovalTestSupport support;

    /** 固定时钟。 */
    private MutableClock clock;

    /** 每个测试重建夹具并关闭自动领取。 */
    @BeforeEach
    void setUp() {
        support = new WorkflowApprovalTestSupport(jdbcClient, objectMapper);
        support.drainPendingRuns();
        clock = new MutableClock(Instant.now());
    }

    /** 用户审批：解析只保留本租户 ACTIVE 且非发起人，运行停靠等待且写请求审计。 */
    @Test
    @DisplayName("IT-WF-108 用户审批解析排除发起人与停用账号")
    void userApproval_resolvesActiveNonRequesterAssignees() {
        String tenant = uniqueTenant("wf-approval-user");
        support.createTenant(tenant);
        UUID requester = support.createUser(tenant);
        UUID approver = support.createUser(tenant);
        UUID disabled = support.createUser(tenant, "DISABLED");
        Seed seed = support.createWorkflow(
                tenant,
                requester,
                approvalGraph(userApprovalConfig(List.of(requester, approver, disabled), "CONTINUE", "end")));
        UUID runId = support.insertRunningRun(tenant, seed, requester, "{\"topic\":\"市场\"}");

        execute(tenant, runId);

        RunRow run = support.readRun(tenant, runId);
        assertThat(run.status()).isEqualTo("WAITING_APPROVAL");
        assertThat(run.errorCode()).isNull();
        assertThat(run.currentNodeKey()).isEqualTo("approval");
        assertThat(run.leaseOwner()).isNull();

        ApprovalRow approval = support.readApproval(tenant, runId);
        assertThat(approval.status()).isEqualTo("PENDING");
        assertThat(approval.assigneeType()).isEqualTo("USER");
        assertThat(approval.actionKind()).isEqualTo("CONTINUE");
        assertThat(approval.targetNodeKey()).isEqualTo("end");
        assertThat(approval.paramsCiphertext()).isNull();
        assertThat(approval.planSha256()).matches("^[0-9a-f]{64}$");
        assertThat(support.readAssignees(tenant, approval.id())).containsExactly(approver);
        assertThat(support.readNodes(tenant, runId, "approval")).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("WAITING_APPROVAL");
            assertThat(row.errorCode()).isNull();
        });
        assertThat(auditRequested(tenant, runId)).singleElement().satisfies(row -> {
            assertThat(row.outcome()).isEqualTo("SUCCESS");
            assertThat(support.json(row.detailsJson()).path("assigneeCount").asInt())
                    .isEqualTo(1);
            assertThat(support.json(row.detailsJson()).path("nodeId").asString())
                    .isEqualTo("approval");
        });
    }

    /** 无可用审批人：运行保持等待、写 WF_NO_APPROVER，不自动放行、不跳过下游。 */
    @Test
    @DisplayName("SEC-WF-001 无可用审批人保持等待且不自动放行")
    void noApprover_keepsRunWaiting() {
        String tenant = uniqueTenant("wf-approval-no-approver");
        support.createTenant(tenant);
        UUID requester = support.createUser(tenant);
        support.assignRole(tenant, requester, "TENANT_ADMIN");
        Seed seed = support.createWorkflow(
                tenant, requester, approvalGraph(roleApprovalConfig("TENANT_ADMIN", "CONTINUE", "end")));
        UUID runId = support.insertRunningRun(tenant, seed, requester, "{\"topic\":\"市场\"}");

        execute(tenant, runId);

        RunRow run = support.readRun(tenant, runId);
        assertThat(run.status()).isEqualTo("WAITING_APPROVAL");
        assertThat(run.errorCode()).isEqualTo("WF_NO_APPROVER");
        assertThat(run.errorMessage()).isEqualTo(WorkflowApprovalExecutor.NO_APPROVER_MESSAGE);
        ApprovalRow approval = support.readApproval(tenant, runId);
        assertThat(approval.status()).isEqualTo("PENDING");
        assertThat(approval.assigneeType()).isEqualTo("ROLE");
        assertThat(approval.assigneeRoleCode()).isEqualTo("TENANT_ADMIN");
        assertThat(support.readAssignees(tenant, approval.id())).isEmpty();
        assertThat(support.readAllNodes(tenant, runId))
                .noneMatch(row -> row.status().equals(WorkflowNodeStatus.SKIPPED.name()));
        assertThat(support.readNodes(tenant, runId, "approval"))
                .singleElement()
                .extracting(row -> row.status())
                .isEqualTo("WAITING_APPROVAL");
        assertThat(auditRequested(tenant, runId)).singleElement().satisfies(row -> {
            assertThat(support.json(row.detailsJson()).path("assigneeCount").asInt())
                    .isZero();
            assertThat(support.json(row.detailsJson()).path("actionKind").asString())
                    .isEqualTo("CONTINUE");
        });
    }

    /** 角色审批：第二名管理员作为审批人，不写 assignee 表但审计计数为 1。 */
    @Test
    @DisplayName("IT-WF-108 角色审批解析非发起人管理员")
    void roleApproval_resolvesSecondAdmin() {
        String tenant = uniqueTenant("wf-approval-role");
        support.createTenant(tenant);
        UUID requester = support.createUser(tenant);
        UUID secondAdmin = support.createUser(tenant);
        support.assignRole(tenant, requester, "TENANT_ADMIN");
        support.assignRole(tenant, secondAdmin, "TENANT_ADMIN");
        Seed seed = support.createWorkflow(
                tenant, requester, approvalGraph(roleApprovalConfig("TENANT_ADMIN", "CONTINUE", "end")));
        UUID runId = support.insertRunningRun(tenant, seed, requester, "{\"topic\":\"市场\"}");

        execute(tenant, runId);

        RunRow run = support.readRun(tenant, runId);
        assertThat(run.status()).isEqualTo("WAITING_APPROVAL");
        assertThat(run.errorCode()).isNull();
        ApprovalRow approval = support.readApproval(tenant, runId);
        assertThat(support.readAssignees(tenant, approval.id())).isEmpty();
        assertThat(auditRequested(tenant, runId))
                .singleElement()
                .satisfies(row -> assertThat(support.json(row.detailsJson())
                                .path("assigneeCount")
                                .asInt())
                        .isEqualTo(1));
    }

    /** 工具审批：参数密文可用审批 ID 解密，错误 AAD 失败关闭，预览脱敏，计划哈希可重算。 */
    @Test
    @DisplayName("SEC-WF-001 工具审批参数加密、脱敏预览与计划绑定")
    void toolApproval_encryptsParamsAndBindsPlan() {
        String tenant = uniqueTenant("wf-approval-tool");
        support.createTenant(tenant);
        UUID requester = support.createUser(tenant);
        UUID approver = support.createUser(tenant);
        UUID toolId = support.createMcpTool();
        Seed seed = support.createWorkflow(tenant, requester, toolApprovalGraph(toolId, requester, approver));
        UUID runId = support.insertRunningRun(tenant, seed, requester, "{\"topic\":\"市场\"}");

        execute(tenant, runId);

        RunRow parked = support.readRun(tenant, runId);
        assertThat(parked.status()).as("run=%s", parked).isEqualTo("WAITING_APPROVAL");
        ApprovalRow approval = support.readApproval(tenant, runId);
        assertThat(approval.actionKind()).isEqualTo("MCP_TOOL");
        assertThat(approval.targetNodeKey()).isEqualTo("tool");
        assertThat(approval.targetToolId()).isEqualTo(toolId);
        assertThat(approval.targetToolName()).startsWith("approval_tool_");
        assertThat(approval.paramsCiphertext()).isNotNull().isNotEmpty();
        assertThat(approval.paramsKeyId()).isNotBlank();
        assertThat(approval.paramsSha256()).matches("^[0-9a-f]{64}$");
        assertThat(approval.planSha256()).matches("^[0-9a-f]{64}$");

        // 预览只显示渲染后的非敏感值，敏感形态的值被 redacted。
        tools.jackson.databind.JsonNode preview = support.json(approval.paramsPreview());
        assertThat(preview.path("query").asString()).isEqualTo("市场");
        assertThat(preview.path("limit").asInt()).isEqualTo(5);
        assertThat(preview.path("note").asString()).isEqualTo("[redacted]");
        assertThat(approval.paramsPreview()).doesNotContain("token-value");

        Map<String, Object> opened = com.riccon.platform.common.tenant.TenantContext.callWithTenant(
                com.riccon.platform.common.tenant.TenantId.of(tenant),
                () -> paramsService.openToolParams(
                        com.riccon.platform.common.tenant.TenantId.of(tenant),
                        approval.id(),
                        approval.paramsKeyId(),
                        approval.paramsCiphertext(),
                        approval.paramsSha256()));
        assertThat(opened).containsEntry("query", "市场");
        assertThat(opened).containsEntry("note", "Bearer token-value");
        assertThatThrownBy(() -> com.riccon.platform.common.tenant.TenantContext.callWithTenant(
                        com.riccon.platform.common.tenant.TenantId.of(tenant),
                        () -> paramsService.openToolParams(
                                com.riccon.platform.common.tenant.TenantId.of(tenant),
                                UUID.randomUUID(),
                                approval.paramsKeyId(),
                                approval.paramsCiphertext(),
                                approval.paramsSha256())))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .satisfies(exception -> assertThat(((WorkflowNodeFailureException) exception)
                                .errorCode()
                                .name())
                        .isEqualTo("WF_PLAN_DRIFT"));

        // T07b 执行前重算：同一版本/配置/上游摘要得到同一哈希。
        WorkflowGraphNodeConfig approvalConfig = support.parseGraph(toolApprovalGraph(toolId, requester, approver))
                .findNode("approval")
                .orElseThrow()
                .config();
        String recomputed = planHasher.hash(new com.riccon.worker.workflow.approval.domain.WorkflowApprovalPlanInput(
                approval.workflowId(),
                seed.versionId(),
                "approval",
                approvalConfig.actionKind(),
                com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType.USER,
                null,
                approvalConfig.assigneeUserIds(),
                "tool",
                toolId,
                approval.targetToolName(),
                Map.of("s", "运行开始")));
        assertThat(recomputed).isEqualTo(approval.planSha256());
    }

    /** 工具参数含敏感键：审批不创建、节点/运行失败且下游跳过。 */
    @Test
    @DisplayName("SEC-WF-001 敏感工具参数失败关闭")
    void toolApproval_sensitiveKeyFailsClosed() {
        String tenant = uniqueTenant("wf-approval-sensitive");
        support.createTenant(tenant);
        UUID requester = support.createUser(tenant);
        UUID approver = support.createUser(tenant);
        UUID toolId = support.createMcpTool();
        Seed seed = support.createWorkflow(
                tenant, requester, toolApprovalGraphWithSensitiveKey(toolId, requester, approver));
        UUID runId = support.insertRunningRun(tenant, seed, requester, "{\"topic\":\"市场\"}");

        execute(tenant, runId);

        RunRow run = support.readRun(tenant, runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_TOOL_NOT_AUTHORIZED");
        assertThat(support.readNodes(tenant, runId, "approval")).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("FAILED");
            assertThat(row.errorCode()).isEqualTo("WF_TOOL_NOT_AUTHORIZED");
        });
        assertThat(support.readNodes(tenant, runId, "tool"))
                .singleElement()
                .extracting(row -> row.status())
                .isEqualTo("SKIPPED");
        assertThatThrownBy(() -> support.readApproval(tenant, runId))
                .isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
        assertThat(auditRequested(tenant, runId)).isEmpty();
    }

    /** 未授权/停用工具：审批节点失败关闭，不创建审批记录。 */
    @Test
    @DisplayName("SEC-WF-001 未授权工具审批失败关闭")
    void toolApproval_unknownToolFailsClosed() {
        String tenant = uniqueTenant("wf-approval-tool-missing");
        support.createTenant(tenant);
        UUID requester = support.createUser(tenant);
        UUID approver = support.createUser(tenant);
        UUID missingTool = UUID.randomUUID();
        Seed seed = support.createWorkflow(tenant, requester, toolApprovalGraph(missingTool, requester, approver));
        UUID runId = support.insertRunningRun(tenant, seed, requester, "{\"topic\":\"市场\"}");

        execute(tenant, runId);

        RunRow run = support.readRun(tenant, runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_TOOL_NOT_AUTHORIZED");
        assertThat(support.readAllNodes(tenant, runId))
                .noneMatch(
                        row -> row.nodeKey().equals("tool") && !row.status().equals(WorkflowNodeStatus.SKIPPED.name()));
    }

    /**
     * 执行串行引擎。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     */
    private void execute(String tenant, UUID runId) {
        WorkflowEngine engine = support.engine(
                engineRepository, resultAssembler, properties, clock, support.defaultExecutors(approvalExecutor));
        support.execute(engine, tenant, runId);
    }

    /**
     * 读取审批请求审计。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @return 审计行
     */
    private List<AuditRow> auditRequested(String tenant, UUID runId) {
        return support.readAudit(tenant, "workflow.approval.requested", runId.toString());
    }

    /**
     * 构造 CONTINUE 审批图。
     *
     * @param approvalConfig 审批配置
     * @return 图 JSON
     */
    private static String approvalGraph(String approvalConfig) {
        return WorkflowApprovalTestSupport.graph(
                List.of(
                        WorkflowApprovalTestSupport.node("s", "START", "开始", "{}"),
                        WorkflowApprovalTestSupport.node("approval", "APPROVAL", "审批", approvalConfig),
                        WorkflowApprovalTestSupport.node("end", "END", "结束", "{\"resultFrom\":\"approval\"}")),
                List.of(
                        WorkflowApprovalTestSupport.edge("e1", "s", "approval"),
                        WorkflowApprovalTestSupport.edge("e2", "approval", "end")));
    }

    /**
     * 构造工具审批图。
     *
     * @param toolId 工具 ID
     * @param requester 发起人
     * @param approver 审批人
     * @return 图 JSON
     */
    private static String toolApprovalGraph(UUID toolId, UUID requester, UUID approver) {
        return toolApprovalGraph(
                toolId,
                requester,
                approver,
                "{\"query\":\"{{input.topic}}\",\"limit\":5," + "\"note\":\"Bearer token-value\"}");
    }

    /**
     * 构造含敏感键的工具审批图。
     *
     * @param toolId 工具 ID
     * @param requester 发起人
     * @param approver 审批人
     * @return 图 JSON
     */
    private static String toolApprovalGraphWithSensitiveKey(UUID toolId, UUID requester, UUID approver) {
        return toolApprovalGraph(toolId, requester, approver, "{\"query\":\"{{input.topic}}\",\"apiKey\":\"x\"}");
    }

    /**
     * 构造工具审批图（自定义参数模板）。
     *
     * @param toolId 工具 ID
     * @param requester 发起人
     * @param approver 审批人
     * @param argsTemplate 参数模板
     * @return 图 JSON
     */
    private static String toolApprovalGraph(UUID toolId, UUID requester, UUID approver, String argsTemplate) {
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
     * 构造用户审批配置。
     *
     * @param userIds 用户
     * @param actionKind 动作
     * @param targetNodeKey 目标
     * @return 配置 JSON
     */
    private static String userApprovalConfig(List<UUID> userIds, String actionKind, String targetNodeKey) {
        String ids = userIds.stream()
                .map(id -> "\"" + id + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return "{\"assigneeType\":\"USER\",\"assigneeUserIds\":[" + ids + "],\"actionKind\":\"" + actionKind
                + "\",\"targetNodeKey\":\"" + targetNodeKey + "\"}";
    }

    /**
     * 构造角色审批配置。
     *
     * @param roleCode 角色码
     * @param actionKind 动作
     * @param targetNodeKey 目标
     * @return 配置 JSON
     */
    private static String roleApprovalConfig(String roleCode, String actionKind, String targetNodeKey) {
        return "{\"assigneeType\":\"ROLE\",\"assigneeRole\":\"" + roleCode + "\",\"actionKind\":\"" + actionKind
                + "\",\"targetNodeKey\":\"" + targetNodeKey + "\"}";
    }
}
