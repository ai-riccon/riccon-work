package com.riccon.worker.workflow.approval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowApprovalDecision;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.workflow.approval.WorkflowApprovalTestSupport;
import com.riccon.worker.workflow.approval.WorkflowApprovalTestSupport.ApprovalRow;
import com.riccon.worker.workflow.approval.WorkflowApprovalTestSupport.AuditRow;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalConflictException;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalDecisionCommand;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalDecisionResult;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalForbiddenException;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalInvalidRequestException;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalNotFoundException;
import com.riccon.worker.workflow.config.WorkflowRuntimeProperties;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.MutableClock;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.NodeRow;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.RunRow;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.Seed;
import com.riccon.worker.workflow.engine.repository.WorkflowEngineRepository;
import com.riccon.worker.workflow.engine.service.WorkflowEngine;
import com.riccon.worker.workflow.node.service.WorkflowResultAssembler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * IT-WF-108 / SEC-WF-001：审批决定状态机、禁自批、范围校验、幂等/并发与审计。
 *
 * <p>使用真实 MySQL 与 Spring 事务代理：批准在一个事务里完成审批→节点→运行→审计；拒绝额外把
 * 下游节点写 SKIPPED。自批/范围外/停用账号只写 denied 审计并保持运行等待；重复/并发决定由审批
 * 行的条件更新兜底，一个成功、另一个 409 语义冲突。
 */
@Tag("integration")
@TestPropertySource(properties = "riccon.workflow.claim-enabled=false")
@DisplayName("IT-WF-108/SEC-WF-001 审批决定")
class WorkflowApprovalDecisionServiceIntegrationTest extends WorkerIntegrationTestBase {

    /** 真实决定服务（Spring 代理，含事务）。 */
    @Autowired
    private WorkflowApprovalDecisionService decisionService;

    /** 真实审批执行器（用于创建审批）。 */
    @Autowired
    private WorkflowApprovalExecutor approvalExecutor;

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

    /** 夹具。 */
    private WorkflowApprovalTestSupport support;

    /** 固定时钟。 */
    private MutableClock clock;

    /** 每个测试重建夹具。 */
    @BeforeEach
    void setUp() {
        support = new WorkflowApprovalTestSupport(jdbcClient, objectMapper);
        support.drainPendingRuns();
        clock = new MutableClock(Instant.now());
    }

    /** 批准：节点成功、运行恢复为目标节点并可继续执行到结果。 */
    @Test
    @DisplayName("IT-WF-108 批准恢复运行并写决定审计")
    void approve_resumesRunAndAudits() {
        Fixture fixture = parkUserApproval("wf-approval-approve");

        WorkflowApprovalDecisionResult result = decide(
                fixture.tenant(), fixture.approvalId(), fixture.approver(), WorkflowApprovalDecision.APPROVE, "同意");

        assertThat(result.approvalStatus().name()).isEqualTo("APPROVED");
        assertThat(result.runStatus().name()).isEqualTo("PENDING");
        assertThat(result.resumeNodeKey()).isEqualTo("end");
        ApprovalRow approval = support.readApproval(fixture.tenant(), fixture.runId());
        assertThat(approval.status()).isEqualTo("APPROVED");
        assertThat(approval.decidedBy()).isEqualTo(fixture.approver());
        assertThat(approval.decidedAt()).isNotNull();
        assertThat(approval.decisionComment()).isEqualTo("同意");
        NodeRow node =
                support.readNodes(fixture.tenant(), fixture.runId(), "approval").getFirst();
        assertThat(node.status()).isEqualTo("SUCCEEDED");
        assertThat(support.json(node.outputJson()).path("decision").asString()).isEqualTo("APPROVED");
        RunRow run = support.readRun(fixture.tenant(), fixture.runId());
        assertThat(run.status()).isEqualTo("PENDING");
        assertThat(run.currentNodeKey()).isEqualTo("end");
        assertThat(run.errorCode()).isNull();
        assertThat(run.leaseOwner()).isNull();
        assertThat(auditDecided(fixture.tenant(), fixture.runId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.outcome()).isEqualTo("SUCCESS");
                    assertThat(support.json(row.detailsJson()).path("decision").asString())
                            .isEqualTo("APPROVE");
                    assertThat(support.json(row.detailsJson())
                                    .path("decisionComment")
                                    .asString())
                            .isEqualTo("同意");
                });

        // 重复决定：审批已 APPROVED，条件更新 0 行 -> 冲突。
        assertThatThrownBy(() -> decide(
                        fixture.tenant(),
                        fixture.approvalId(),
                        fixture.approver(),
                        WorkflowApprovalDecision.APPROVE,
                        "再次同意"))
                .isInstanceOf(WorkflowApprovalConflictException.class);

        // 运行恢复后可继续执行 END 并写出结果（证明 current_node_key 指向目标节点）。
        support.claimRun(fixture.tenant(), fixture.runId());
        execute(fixture.tenant(), fixture.runId());
        RunRow finished = support.readRun(fixture.tenant(), fixture.runId());
        assertThat(finished.status()).isEqualTo("SUCCEEDED");
        assertThat(finished.resultStatus()).isEqualTo("SAVED");
        assertThat(support.json(finished.resultJson()).path("text").asString()).contains("APPROVED");
    }

    /** 拒绝：理由必填；拒绝后运行失败、审批节点失败、下游全部 SKIPPED。 */
    @Test
    @DisplayName("SEC-WF-001 拒绝终止运行并跳过下游")
    void reject_failsRunAndSkipsDownstream() {
        Fixture fixture = parkUserApproval("wf-approval-reject");

        assertThatThrownBy(() -> decide(
                        fixture.tenant(),
                        fixture.approvalId(),
                        fixture.approver(),
                        WorkflowApprovalDecision.REJECT,
                        "  "))
                .isInstanceOf(WorkflowApprovalInvalidRequestException.class);
        assertThat(support.readApproval(fixture.tenant(), fixture.runId()).status())
                .isEqualTo("PENDING");
        assertThat(support.readRun(fixture.tenant(), fixture.runId()).status()).isEqualTo("WAITING_APPROVAL");

        WorkflowApprovalDecisionResult result = decide(
                fixture.tenant(), fixture.approvalId(), fixture.approver(), WorkflowApprovalDecision.REJECT, "不合规");
        assertThat(result.approvalStatus().name()).isEqualTo("REJECTED");
        assertThat(result.runStatus().name()).isEqualTo("FAILED");
        ApprovalRow approval = support.readApproval(fixture.tenant(), fixture.runId());
        assertThat(approval.status()).isEqualTo("REJECTED");
        assertThat(approval.decisionComment()).isEqualTo("不合规");
        assertThat(support.readNodes(fixture.tenant(), fixture.runId(), "approval"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo("FAILED");
                    assertThat(row.errorCode()).isEqualTo("WF_APPROVAL_REJECTED");
                });
        RunRow run = support.readRun(fixture.tenant(), fixture.runId());
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_APPROVAL_REJECTED");
        assertThat(run.resultStatus()).isEqualTo("NONE");
        assertThat(run.resultJson()).isNull();
        assertThat(support.readNodes(fixture.tenant(), fixture.runId(), "end"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo("SKIPPED");
                    assertThat(row.outputSummary()).isEqualTo(WorkflowApprovalDecisionService.REJECTED_SKIP_SUMMARY);
                });
        assertThat(auditDecided(fixture.tenant(), fixture.runId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.outcome()).isEqualTo("SUCCESS");
                    assertThat(support.json(row.detailsJson()).path("decision").asString())
                            .isEqualTo("REJECT");
                    assertThat(support.json(row.detailsJson())
                                    .path("decisionComment")
                                    .asString())
                            .isEqualTo("不合规");
                });

        // 拒绝后审批终态，再次决定冲突。
        assertThatThrownBy(() -> decide(
                        fixture.tenant(),
                        fixture.approvalId(),
                        fixture.approver(),
                        WorkflowApprovalDecision.REJECT,
                        "重复拒绝"))
                .isInstanceOf(WorkflowApprovalConflictException.class);
    }

    /** 自批：发起人即使拥有角色也不能批准，写 denied 审计且运行保持等待。 */
    @Test
    @DisplayName("SEC-WF-001 禁自批并写拒绝审计")
    void selfApproval_forbiddenAndAudited() {
        String tenant = uniqueTenant("wf-approval-self");
        support.createTenant(tenant);
        UUID requester = support.createUser(tenant);
        support.assignRole(tenant, requester, "TENANT_ADMIN");
        Seed seed = support.createWorkflow(
                tenant,
                requester,
                approvalGraph(
                        "{\"assigneeType\":\"ROLE\",\"assigneeRole\":\"TENANT_ADMIN\",\"actionKind\":\"CONTINUE\","
                                + "\"targetNodeKey\":\"end\"}"));
        UUID runId = support.insertRunningRun(tenant, seed, requester, "{\"topic\":\"市场\"}");
        execute(tenant, runId);
        ApprovalRow approval = support.readApproval(tenant, runId);

        assertThatThrownBy(() -> decide(tenant, approval.id(), requester, WorkflowApprovalDecision.APPROVE, "自批"))
                .isInstanceOf(WorkflowApprovalForbiddenException.class)
                .satisfies(exception -> assertThat(((WorkflowApprovalForbiddenException) exception)
                                .reason()
                                .name())
                        .isEqualTo("SELF_APPROVAL"));

        assertThat(support.readApproval(tenant, runId).status()).isEqualTo("PENDING");
        assertThat(support.readRun(tenant, runId).status()).isEqualTo("WAITING_APPROVAL");
        assertThat(auditDecided(tenant, runId)).singleElement().satisfies(row -> {
            assertThat(row.outcome()).isEqualTo("DENIED");
            assertThat(support.json(row.detailsJson()).path("approverUserId").asString())
                    .isEqualTo(requester.toString());
            assertThat(row.detailsJson()).doesNotContain("自批");
        });
    }

    /** 范围外：非 assignee 用户不能决定，写 denied 审计且不泄露审批内容。 */
    @Test
    @DisplayName("SEC-WF-001 范围外审批 403 并保持等待")
    void outOfScope_forbiddenAndAudited() {
        Fixture fixture = parkUserApproval("wf-approval-scope");
        UUID outsider = support.createUser(fixture.tenant());

        assertThatThrownBy(() -> decide(
                        fixture.tenant(), fixture.approvalId(), outsider, WorkflowApprovalDecision.APPROVE, "越权"))
                .isInstanceOf(WorkflowApprovalForbiddenException.class)
                .satisfies(exception -> assertThat(((WorkflowApprovalForbiddenException) exception)
                                .reason()
                                .name())
                        .isEqualTo("OUT_OF_SCOPE"));
        assertThat(support.readApproval(fixture.tenant(), fixture.runId()).status())
                .isEqualTo("PENDING");
        assertThat(support.readRun(fixture.tenant(), fixture.runId()).status()).isEqualTo("WAITING_APPROVAL");
        assertThat(auditDecided(fixture.tenant(), fixture.runId()))
                .singleElement()
                .satisfies(row -> assertThat(row.outcome()).isEqualTo("DENIED"));
    }

    /** 停用账号：不能决定，写 denied 审计。 */
    @Test
    @DisplayName("SEC-WF-001 停用审批人 403")
    void disabledApprover_forbidden() {
        String tenant = uniqueTenant("wf-approval-disabled");
        support.createTenant(tenant);
        UUID requester = support.createUser(tenant);
        UUID disabled = support.createUser(tenant, "DISABLED");
        Seed seed = support.createWorkflow(
                tenant,
                requester,
                approvalGraph("{\"assigneeType\":\"USER\",\"assigneeUserIds\":[\"" + requester + "\",\"" + disabled
                        + "\"],\"actionKind\":\"CONTINUE\",\"targetNodeKey\":\"end\"}"));
        UUID runId = support.insertRunningRun(tenant, seed, requester, "{\"topic\":\"市场\"}");
        execute(tenant, runId);
        ApprovalRow approval = support.readApproval(tenant, runId);

        assertThatThrownBy(() -> decide(tenant, approval.id(), disabled, WorkflowApprovalDecision.APPROVE, "停用"))
                .isInstanceOf(WorkflowApprovalForbiddenException.class)
                .satisfies(exception -> assertThat(((WorkflowApprovalForbiddenException) exception)
                                .reason()
                                .name())
                        .isEqualTo("APPROVER_INACTIVE"));
        assertThat(support.readRun(tenant, runId).status()).isEqualTo("WAITING_APPROVAL");
        assertThat(auditDecided(tenant, runId))
                .singleElement()
                .satisfies(row -> assertThat(row.outcome()).isEqualTo("DENIED"));
    }

    /** 并发决定：一个成功、一个冲突，审批只落一次决定与一条成功审计。 */
    @Test
    @DisplayName("IT-WF-108 并发决定只成功一次")
    void concurrentDecisions_onlyOneSucceeds() throws Exception {
        Fixture fixture = parkUserApproval("wf-approval-concurrent");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<String> task = () -> {
                start.await(5, TimeUnit.SECONDS);
                try {
                    decide(
                            fixture.tenant(),
                            fixture.approvalId(),
                            fixture.approver(),
                            WorkflowApprovalDecision.APPROVE,
                            "并发");
                    return "OK";
                } catch (WorkflowApprovalConflictException exception) {
                    return "CONFLICT";
                }
            };
            Future<String> first = pool.submit(task);
            Future<String> second = pool.submit(task);
            start.countDown();
            List<String> outcomes = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));

            assertThat(outcomes).containsExactlyInAnyOrder("OK", "CONFLICT");
        } finally {
            pool.shutdownNow();
        }
        assertThat(support.readApproval(fixture.tenant(), fixture.runId()).status())
                .isEqualTo("APPROVED");
        assertThat(auditDecided(fixture.tenant(), fixture.runId()))
                .singleElement()
                .satisfies(row -> assertThat(row.outcome()).isEqualTo("SUCCESS"));
    }

    /** 不存在的审批按不可见处理。 */
    @Test
    @DisplayName("SEC-WF-001 不存在审批按不可见处理")
    void missingApproval_notVisible() {
        String tenant = uniqueTenant("wf-approval-missing");
        support.createTenant(tenant);
        UUID user = support.createUser(tenant);

        assertThatThrownBy(() -> decide(tenant, UUID.randomUUID(), user, WorkflowApprovalDecision.APPROVE, "x"))
                .isInstanceOf(WorkflowApprovalNotFoundException.class);
    }

    /** 跨租户审批 ID 按不可见处理，不读取也不回显其他租户内容。 */
    @Test
    @DisplayName("SEC-WF-001 跨租户审批按不可见处理")
    void crossTenantApproval_notVisible() {
        Fixture fixture = parkUserApproval("wf-approval-cross-tenant");
        String otherTenant = uniqueTenant("wf-approval-other");
        support.createTenant(otherTenant);
        UUID outsider = support.createUser(otherTenant);

        assertThatThrownBy(() ->
                        decide(otherTenant, fixture.approvalId(), outsider, WorkflowApprovalDecision.APPROVE, "跨租户"))
                .isInstanceOf(WorkflowApprovalNotFoundException.class);
        assertThat(support.readApproval(fixture.tenant(), fixture.runId()).status())
                .isEqualTo("PENDING");
        assertThat(support.readRun(fixture.tenant(), fixture.runId()).status()).isEqualTo("WAITING_APPROVAL");
    }

    /**
     * 创建用户审批并停车。
     *
     * @param prefix 租户前缀
     * @return 夹具
     */
    private Fixture parkUserApproval(String prefix) {
        String tenant = uniqueTenant(prefix);
        support.createTenant(tenant);
        UUID requester = support.createUser(tenant);
        UUID approver = support.createUser(tenant);
        Seed seed = support.createWorkflow(
                tenant,
                requester,
                approvalGraph("{\"assigneeType\":\"USER\",\"assigneeUserIds\":[\"" + requester + "\",\"" + approver
                        + "\"],\"actionKind\":\"CONTINUE\",\"targetNodeKey\":\"end\"}"));
        UUID runId = support.insertRunningRun(tenant, seed, requester, "{\"topic\":\"市场\"}");
        execute(tenant, runId);
        ApprovalRow approval = support.readApproval(tenant, runId);
        return new Fixture(tenant, runId, approval.id(), approver);
    }

    /**
     * 执行决定并断言事务上下文。
     *
     * @param tenant 租户
     * @param approvalId 审批 ID
     * @param approver 审批人
     * @param decision 决定
     * @param comment 理由
     * @return 结果
     */
    private WorkflowApprovalDecisionResult decide(
            String tenant, UUID approvalId, UUID approver, WorkflowApprovalDecision decision, String comment) {
        return TenantContext.callWithTenant(
                TenantId.of(tenant),
                () -> decisionService.decide(new WorkflowApprovalDecisionCommand(
                        TenantId.of(tenant),
                        approvalId,
                        approver,
                        decision,
                        comment,
                        "approval-decision-" + approvalId)));
    }

    /**
     * 执行串行引擎（停车或完成）。
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
     * 读取决定审计。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @return 审计行
     */
    private List<AuditRow> auditDecided(String tenant, UUID runId) {
        return support.readAudit(tenant, "workflow.approval.decided", runId.toString());
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
     * 测试夹具。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param approvalId 审批 ID
     * @param approver 审批人
     */
    private record Fixture(String tenant, UUID runId, UUID approvalId, UUID approver) {}
}
