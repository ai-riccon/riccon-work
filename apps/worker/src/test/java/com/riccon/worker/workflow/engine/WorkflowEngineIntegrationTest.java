package com.riccon.worker.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.MutableClock;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.NodeRow;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.RunRow;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.Seed;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.StubExecutor;
import com.riccon.worker.workflow.engine.WorkflowEngineTestSupport.TestLease;
import com.riccon.worker.workflow.config.WorkflowRuntimeProperties;
import com.riccon.worker.workflow.engine.repository.WorkflowEngineRepository;
import com.riccon.worker.workflow.engine.service.WorkflowEngine;
import com.riccon.worker.workflow.node.domain.WorkflowNodeAbortedException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecution;
import com.riccon.worker.workflow.node.domain.WorkflowNodeFailureException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeResult;
import com.riccon.worker.workflow.node.domain.WorkflowNodeTimeoutException;
import com.riccon.worker.workflow.node.service.ConditionNodeExecutor;
import com.riccon.worker.workflow.node.service.EndNodeExecutor;
import com.riccon.worker.workflow.node.service.StartNodeExecutor;
import com.riccon.worker.workflow.node.service.WorkflowConditionEvaluator;
import com.riccon.worker.workflow.node.service.WorkflowNodeDispatcher;
import com.riccon.worker.workflow.node.service.WorkflowResultAssembler;
import com.riccon.worker.workflow.node.service.WorkflowTemplateRenderer;
import com.riccon.worker.workflow.queue.domain.ClaimedWorkflowRun;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * IT-WF-105/109：串行引擎与节点状态机（真实 MySQL + 可编程节点执行器）。
 *
 * <p>使用真实仓储/START/END/CONDITION 执行器与桩 RETRIEVAL/MODEL 执行器，覆盖串行成功、
 * 条件分支、失败/跳过、重试不重跑上游、运行超时、取消、租约丢失、单节点/结果大小上限、
 * 步数上限、非法图与 END 崩溃恢复。检索 ACL 与模型路由由单元测试覆盖，避免测试访问外部模型。
 *
 * <p>测试类显式关闭自动领取，避免共享 Worker 上下文中的调度槽位与手工 {@code execute} 竞争。
 */
@Tag("integration")
@TestPropertySource(properties = "riccon.workflow.claim-enabled=false")
@DisplayName("IT-WF-105/109 串行引擎与节点状态机")
class WorkflowEngineIntegrationTest extends WorkerIntegrationTestBase {

    /** 引擎仓储。 */
    @Autowired
    private WorkflowEngineRepository repository;

    /** JSON 序列化。 */
    @Autowired
    private ObjectMapper objectMapper;

    /** 测试夹具。 */
    private WorkflowEngineTestSupport support;

    /** 可推进时钟。 */
    private MutableClock clock;

    /** 检索桩。 */
    private StubExecutor retrieval;

    /** 模型桩。 */
    private StubExecutor model;

    /** 结果组装。 */
    private WorkflowResultAssembler assembler;

    /** 节点分派。 */
    private WorkflowNodeDispatcher dispatcher;

    /** 每个测试重建夹具、桩与分派器。 */
    @BeforeEach
    void setUp() {
        support = new WorkflowEngineTestSupport(jdbcClient, objectMapper);
        support.drainPendingRuns();
        clock = new MutableClock(Instant.now());
        WorkflowTemplateRenderer renderer = new WorkflowTemplateRenderer();
        assembler = new WorkflowResultAssembler(objectMapper);
        retrieval = new StubExecutor(
                WorkflowNodeType.RETRIEVAL,
                execution -> WorkflowNodeResult.of(
                        "检索查询：" + execution.inputs().path("topic").asString(""),
                        "检索命中 2 条",
                        numberOutput("hitCount", 2),
                        WorkflowEngineTestSupport.citations(2)));
        model = new StubExecutor(
                WorkflowNodeType.MODEL,
                execution -> modelSuccess("模型输出"));
        dispatcher = new WorkflowNodeDispatcher(List.of(
                new StartNodeExecutor(renderer),
                new EndNodeExecutor(assembler),
                new ConditionNodeExecutor(new WorkflowConditionEvaluator()),
                retrieval,
                model));
    }

    /** 串行 START → RETRIEVAL → MODEL → END：状态、时间线、摘要、引用与结果全部落库。 */
    @Test
    @DisplayName("IT-WF-105 串行执行写入节点时间线、引用与最终结果")
    void serialRun_persistsTimelineCitationsAndResult() {
        String tenant = uniqueTenant("wf-engine-serial");
        Fixture fixture = fixture(tenant, serialGraph());
        TestLease lease = new TestLease(fixture.runId, tenant);

        execute(tenant, fixture.runId, lease);

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.errorCode()).isNull();
        assertThat(run.currentNodeKey()).isEqualTo("e");
        assertThat(run.resultStatus()).isEqualTo("SAVED");
        assertThat(run.resultSizeBytes()).isPositive();
        assertThat(run.resultJson()).isNotNull();
        assertThat(run.leaseOwner()).isNull();
        assertThat(run.finishedAt()).isNotNull();
        JsonNode result = support.json(run.resultJson());
        assertThat(result.path("format").asString()).isEqualTo("TEXT");
        assertThat(result.path("text").asString()).isEqualTo("模型输出");
        assertThat(result.path("sizeBytes").asInt()).isPositive();
        assertThat(result.path("generatedAt").asString()).contains("+08:00");

        List<NodeRow> start = support.readNodes(tenant, fixture.runId, "s");
        List<NodeRow> retrievalRows = support.readNodes(tenant, fixture.runId, "r");
        List<NodeRow> modelRows = support.readNodes(tenant, fixture.runId, "m");
        List<NodeRow> end = support.readNodes(tenant, fixture.runId, "e");
        assertThat(start).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("SUCCEEDED");
            assertThat(row.attempt()).isEqualTo(1);
            assertThat(row.startedAt()).isNotNull();
            assertThat(row.finishedAt()).isNotNull();
        });
        assertThat(retrievalRows).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("SUCCEEDED");
            assertThat(row.inputSummary()).contains("检索查询");
            assertThat(row.outputSummary()).isEqualTo("检索命中 2 条");
            assertThat(support.json(row.citationsJson()).size()).isEqualTo(2);
        });
        assertThat(modelRows).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("SUCCEEDED");
            assertThat(row.outputSummary()).isEqualTo("模型输出");
            assertThat(row.inputSummary()).contains("模型提示");
        });
        assertThat(end).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("SUCCEEDED");
            assertThat(row.outputSummary()).contains("字节");
            assertThat(support.json(row.citationsJson()).size()).isEqualTo(2);
        });
        assertThat(retrieval.invocations()).isEqualTo(1);
        assertThat(model.invocations()).isEqualTo(1);
        WorkflowNodeExecution retrievalExecution = retrieval.executions().getFirst();
        assertThat(retrievalExecution.tenantId().value()).isEqualTo(tenant);
        assertThat(retrievalExecution.node().id()).isEqualTo("r");
        assertThat(retrievalExecution.inputs().path("topic").asString()).isEqualTo("市场");
    }

    /** 条件命中 TRUE：只执行 TRUE 分支，FALSE 分支整棵子树 SKIPPED。 */
    @Test
    @DisplayName("IT-WF-105 条件 TRUE 分支只执行一条分支并跳过另一支")
    void condition_trueBranch_skipsFalseSubtree() {
        String tenant = uniqueTenant("wf-engine-cond-true");
        Fixture fixture = fixture(tenant, conditionGraph());
        model.behavior(execution -> modelSuccess(execution.node().id()));

        execute(tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.currentNodeKey()).isEqualTo("et");
        assertThat(support.readNodes(tenant, fixture.runId, "t"))
                .singleElement()
                .extracting(NodeRow::status)
                .isEqualTo("SUCCEEDED");
        assertThat(support.readNodes(tenant, fixture.runId, "f"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo("SKIPPED");
                    assertThat(row.outputSummary()).contains("未执行");
                });
        assertThat(support.readNodes(tenant, fixture.runId, "ef"))
                .singleElement()
                .extracting(NodeRow::status)
                .isEqualTo("SKIPPED");
        assertThat(model.invocations()).isEqualTo(1);
        assertThat(model.executions().getFirst().node().id()).isEqualTo("t");
        assertThat(support.json(support.readRun(tenant, fixture.runId).resultJson())
                        .path("text")
                        .asString())
                .isEqualTo("t");
    }

    /** 条件不命中 TRUE：走 FALSE 分支。 */
    @Test
    @DisplayName("IT-WF-105 条件 FALSE 分支只执行一条分支")
    void condition_falseBranch_executesFalseSubtree() {
        String tenant = uniqueTenant("wf-engine-cond-false");
        Fixture fixture = fixture(tenant, conditionGraph());
        retrieval.behavior(execution -> WorkflowNodeResult.of(
                "检索查询", "检索命中 0 条", numberOutput("hitCount", 0), List.of()));
        model.behavior(execution -> modelSuccess(execution.node().id()));

        execute(tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.currentNodeKey()).isEqualTo("ef");
        assertThat(support.readNodes(tenant, fixture.runId, "t"))
                .singleElement()
                .extracting(NodeRow::status)
                .isEqualTo("SKIPPED");
        assertThat(support.readNodes(tenant, fixture.runId, "f"))
                .singleElement()
                .extracting(NodeRow::status)
                .isEqualTo("SUCCEEDED");
        assertThat(model.executions().getFirst().node().id()).isEqualTo("f");
    }

    /** 条件无法求值：节点 WF_CONDITION_INVALID、两个分支都跳过、运行失败，不默认分支。 */
    @Test
    @DisplayName("IT-WF-105 条件类型不符失败且不默认走分支")
    void condition_invalid_failsWithoutDefaultBranch() {
        String tenant = uniqueTenant("wf-engine-cond-invalid");
        Fixture fixture = fixture(tenant, conditionGraphWithStringHitCount());

        execute(tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_CONDITION_INVALID");
        assertThat(run.resultJson()).isNull();
        assertThat(support.readNodes(tenant, fixture.runId, "c"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo("FAILED");
                    assertThat(row.errorCode()).isEqualTo("WF_CONDITION_INVALID");
                });
        assertThat(support.readNodes(tenant, fixture.runId, "t"))
                .singleElement()
                .extracting(NodeRow::status)
                .isEqualTo("SKIPPED");
        assertThat(support.readNodes(tenant, fixture.runId, "f"))
                .singleElement()
                .extracting(NodeRow::status)
                .isEqualTo("SKIPPED");
        assertThat(model.invocations()).isZero();
    }

    /** 失败保留上游、跳过下游；重试从失败节点继续，上游成功节点不重跑。 */
    @Test
    @DisplayName("IT-WF-105 失败/重试只重跑失败节点及其下游")
    void failureThenRetry_doesNotRerunUpstream() {
        String tenant = uniqueTenant("wf-engine-retry");
        Fixture fixture = fixture(tenant, linearModelGraph());
        AtomicBoolean failB = new AtomicBoolean(true);
        model.behavior(execution -> {
            if (execution.node().id().equals("b") && failB.get()) {
                throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_MODEL_UNAVAILABLE, "模型不可用");
            }
            return modelSuccess(execution.node().id());
        });

        execute(tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        RunRow failed = support.readRun(tenant, fixture.runId);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("WF_MODEL_UNAVAILABLE");
        assertThat(failed.resultStatus()).isEqualTo("NONE");
        assertThat(support.readNodes(tenant, fixture.runId, "a"))
                .singleElement()
                .extracting(NodeRow::status)
                .isEqualTo("SUCCEEDED");
        assertThat(support.readNodes(tenant, fixture.runId, "b"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo("FAILED");
                    assertThat(row.errorCode()).isEqualTo("WF_MODEL_UNAVAILABLE");
                });
        assertThat(support.readNodes(tenant, fixture.runId, "c"))
                .singleElement()
                .extracting(NodeRow::status)
                .isEqualTo("SKIPPED");
        assertThat(support.readNodes(tenant, fixture.runId, "e"))
                .singleElement()
                .extracting(NodeRow::status)
                .isEqualTo("SKIPPED");

        failB.set(false);
        support.prepareRetry(
                tenant, fixture.runId, "b", WorkflowNodeType.MODEL, "模型B", "test-lease");
        execute(tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        RunRow retried = support.readRun(tenant, fixture.runId);
        assertThat(retried.status()).isEqualTo("SUCCEEDED");
        assertThat(retried.currentNodeKey()).isEqualTo("e");
        assertThat(support.readNodes(tenant, fixture.runId, "a")).hasSize(1);
        assertThat(support.readNodes(tenant, fixture.runId, "b")).hasSize(2);
        assertThat(support.readNodes(tenant, fixture.runId, "b").stream()
                        .map(NodeRow::status)
                        .toList())
                .containsExactly("FAILED", "SUCCEEDED");
        assertThat(support.readNodes(tenant, fixture.runId, "c"))
                .singleElement()
                .extracting(NodeRow::status)
                .isEqualTo("SUCCEEDED");
        assertThat(support.json(retried.resultJson()).path("text").asString()).isEqualTo("c");
        assertThat(model.invocations()).isEqualTo(4);
    }

    /** 运行超时（累计执行预算耗尽）：当前节点 CANCELLED、下游 SKIPPED、运行 FAILED。 */
    @Test
    @DisplayName("IT-WF-109 运行超时取消当前节点并保留已完成节点")
    void runTimeout_cancelsCurrentNode() {
        String tenant = uniqueTenant("wf-engine-timeout");
        Fixture fixture = fixture(tenant, twoModelGraph());
        support.insertPendingNode(tenant, fixture.runId, "b", WorkflowNodeType.MODEL, "模型B");
        model.behavior(execution -> {
            if (execution.node().id().equals("a")) {
                // 不 sleep：推进注入时钟到运行预算之外，模拟模型长调用耗尽 30 分钟预算。
                clock.advance(Duration.ofMinutes(31));
            }
            return modelSuccess(execution.node().id());
        });

        execute(tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_RUN_TIMEOUT");
        assertThat(support.readNodes(tenant, fixture.runId, "a"))
                .singleElement()
                .extracting(NodeRow::status)
                .isEqualTo("SUCCEEDED");
        assertThat(support.readNodes(tenant, fixture.runId, "b"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo("CANCELLED");
                    assertThat(row.errorCode()).isEqualTo("WF_RUN_TIMEOUT");
                });
        assertThat(support.readNodes(tenant, fixture.runId, "e"))
                .singleElement()
                .extracting(NodeRow::status)
                .isEqualTo("SKIPPED");
    }

    /** 节点执行中取消：当前节点 CANCELLED，后续节点不再执行。 */
    @Test
    @DisplayName("IT-WF-105 取消停止后续节点并保留已完成节点")
    void cancelDuringNode_stopsRemainingNodes() {
        String tenant = uniqueTenant("wf-engine-cancel");
        Fixture fixture = fixture(tenant, twoModelGraph());
        TestLease lease = new TestLease(fixture.runId, tenant);
        model.behavior(execution -> {
            if (execution.node().id().equals("b")) {
                support.cancelRun(tenant, fixture.runId);
                lease.markCancelled();
                throw new WorkflowNodeAbortedException(false);
            }
            return modelSuccess(execution.node().id());
        });

        execute(tenant, fixture.runId, lease);

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("CANCELLED");
        assertThat(support.readNodes(tenant, fixture.runId, "a"))
                .singleElement()
                .extracting(NodeRow::status)
                .isEqualTo("SUCCEEDED");
        assertThat(support.readNodes(tenant, fixture.runId, "b"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo("CANCELLED");
                    assertThat(row.errorCode()).isEqualTo("WF_CANCELLED");
                });
        assertThat(support.readNodes(tenant, fixture.runId, "e")).isEmpty();
        assertThat(model.invocations()).isEqualTo(2);
    }

    /** 租约丢失：丢弃结果、不写运行终态、不执行后续节点。 */
    @Test
    @DisplayName("IT-WF-105 租约丢失丢弃结果且不写终态")
    void leaseLost_discardsResult() {
        String tenant = uniqueTenant("wf-engine-lost");
        Fixture fixture = fixture(tenant, twoModelGraph());
        TestLease lease = new TestLease(fixture.runId, tenant);
        model.behavior(execution -> {
            if (execution.node().id().equals("a")) {
                lease.markLost();
            }
            return modelSuccess("A");
        });

        execute(tenant, fixture.runId, lease);

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("RUNNING");
        assertThat(run.resultJson()).isNull();
        assertThat(support.readNodes(tenant, fixture.runId, "a"))
                .singleElement()
                .extracting(NodeRow::status)
                .isEqualTo("RUNNING");
        assertThat(support.readNodes(tenant, fixture.runId, "b")).isEmpty();
        assertThat(model.invocations()).isEqualTo(1);
    }

    /** 最终结果超过 5 MiB 预算：END 失败、运行 OVER_LIMIT、不保存 result_json。 */
    @Test
    @DisplayName("IT-WF-109 结果超过大小上限不保存并标记 OVER_LIMIT")
    void resultOverLimit_marksOverLimit() {
        String tenant = uniqueTenant("wf-engine-result-limit");
        Fixture fixture = fixture(tenant, singleModelGraph());
        model.behavior(execution -> modelSuccess("x".repeat(200)));
        WorkflowEngine engine = engine(new WorkflowRuntimeProperties(true, "", 50, 200, 300, 120, 60, 30, 10, 5, 100, 180));

        execute(engine, tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_RESULT_LIMIT");
        assertThat(run.resultStatus()).isEqualTo("OVER_LIMIT");
        assertThat(run.resultSizeBytes()).isGreaterThan(100);
        assertThat(run.resultJson()).isNull();
        assertThat(support.readNodes(tenant, fixture.runId, "e"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo("FAILED");
                    assertThat(row.errorCode()).isEqualTo("WF_RESULT_LIMIT");
                });
    }

    /** 单节点输出超过 256 KiB：节点失败、下游跳过、运行 OVER_LIMIT。 */
    @Test
    @DisplayName("IT-WF-109 单节点输出超过 256 KiB 失败")
    void nodeOutputOverLimit_failsNode() {
        String tenant = uniqueTenant("wf-engine-node-limit");
        Fixture fixture = fixture(tenant, singleModelGraph());
        model.behavior(execution -> modelSuccess("x".repeat(300_000)));

        execute(tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_RESULT_LIMIT");
        assertThat(run.resultStatus()).isEqualTo("OVER_LIMIT");
        assertThat(support.readNodes(tenant, fixture.runId, "m"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo("FAILED");
                    assertThat(row.errorCode()).isEqualTo("WF_RESULT_LIMIT");
                });
        assertThat(support.readNodes(tenant, fixture.runId, "e"))
                .singleElement()
                .extracting(NodeRow::status)
                .isEqualTo("SKIPPED");
    }

    /** 步数上限：超过 3 步后运行失败 WF_STEP_LIMIT（含 PENDING 尝试关闭）。 */
    @Test
    @DisplayName("IT-WF-109 步数超限终止运行")
    void stepLimit_failsRun() {
        String tenant = uniqueTenant("wf-engine-step-limit");
        Fixture fixture = fixture(tenant, twoModelGraph());
        support.insertPendingNode(tenant, fixture.runId, "b", WorkflowNodeType.MODEL, "模型B");
        WorkflowEngine engine = engine(new WorkflowRuntimeProperties(true, "", 50, 3, 300, 120, 60, 30, 10, 5, 5_242_880, 180));

        execute(engine, tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_STEP_LIMIT");
        assertThat(support.readNodes(tenant, fixture.runId, "a"))
                .singleElement()
                .extracting(NodeRow::status)
                .isEqualTo("SUCCEEDED");
        assertThat(support.readNodes(tenant, fixture.runId, "b"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo("FAILED");
                    assertThat(row.errorCode()).isEqualTo("WF_STEP_LIMIT");
                });
        assertThat(support.readNodes(tenant, fixture.runId, "e"))
                .singleElement()
                .extracting(NodeRow::status)
                .isEqualTo("SKIPPED");
        assertThat(model.invocations()).isEqualTo(1);
    }

    /** 崩溃遗留 RUNNING 尝试：置 WF_INTERNAL 失败后以新尝试继续。 */
    @Test
    @DisplayName("IT-WF-105 崩溃遗留 RUNNING 尝试被替换")
    void staleRunningAttempt_isFailedAndRetried() {
        String tenant = uniqueTenant("wf-engine-stale");
        Fixture fixture = fixture(tenant, singleModelGraph());
        support.insertRunningNode(tenant, fixture.runId, "s", WorkflowNodeType.START, "开始");

        execute(tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(support.readNodes(tenant, fixture.runId, "s").stream()
                        .map(NodeRow::status)
                        .toList())
                .containsExactly("FAILED", "SUCCEEDED");
        assertThat(model.invocations()).isEqualTo(1);
    }

    /** 运行超时且当前节点为遗留 RUNNING：节点 CANCELLED。 */
    @Test
    @DisplayName("IT-WF-109 运行超时取消遗留 RUNNING 节点")
    void runTimeoutWithRunningNode_cancelsNode() {
        String tenant = uniqueTenant("wf-engine-timeout-running");
        Fixture fixture = fixture(tenant, singleModelGraph());
        support.insertRunningNode(tenant, fixture.runId, "s", WorkflowNodeType.START, "开始");
        support.setExecutionMs(tenant, fixture.runId, 30L * 60L * 1000L);

        execute(tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_RUN_TIMEOUT");
        assertThat(support.readNodes(tenant, fixture.runId, "s"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo("CANCELLED");
                    assertThat(row.errorCode()).isEqualTo("WF_RUN_TIMEOUT");
                });
    }

    /** 批量读取知识库文档 checksum。 */
    @Test
    @DisplayName("IT-WF-105 引用 checksum 批量读取")
    void findKnowledgeChecksums_returnsVersions() {
        String tenant = uniqueTenant("wf-engine-checksum");
        support.createTenant(tenant);
        UUID user = support.createUser(tenant);
        UUID first = support.createKnowledgeDocument(tenant, user, "checksum-a");
        UUID second = support.createKnowledgeDocument(tenant, user, "checksum-b");

        Map<UUID, String> checksums = TenantContext.callWithTenant(
                TenantId.of(tenant),
                () -> repository.findKnowledgeChecksums(TenantId.of(tenant), List.of(first, second)));

        assertThat(checksums).containsEntry(first, "checksum-a").containsEntry(second, "checksum-b");
    }

    /** 未知节点类型：运行入口拒绝执行（WF_INVALID_GRAPH），不产生节点尝试。 */
    @Test
    @DisplayName("IT-WF-109 未知节点类型在运行入口拒绝")
    void unknownNodeType_failsClosed() {
        String tenant = uniqueTenant("wf-engine-invalid");
        String graph = WorkflowEngineTestSupport.graph(
                List.of(
                        WorkflowEngineTestSupport.node("s", "START", "开始", "{}"),
                        WorkflowEngineTestSupport.node(
                                "x",
                                "SCRIPT",
                                "脚本",
                                "{\"promptTemplate\":\"x\"}")),
                List.of(WorkflowEngineTestSupport.edge("e1", "s", "x", null)));
        Fixture fixture = fixture(tenant, graph);

        execute(tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_INVALID_GRAPH");
        assertThat(support.readAllNodes(tenant, fixture.runId)).isEmpty();
    }

    /** END 已成功但运行未写终态的崩溃恢复：按存储输出重建结果并成功。 */
    @Test
    @DisplayName("IT-WF-105 END 崩溃恢复按存储输出重建结果")
    void endRecovery_rebuildsResult() {
        String tenant = uniqueTenant("wf-engine-recover");
        Fixture fixture = fixture(tenant, singleModelGraph());
        support.setCurrentNode(tenant, fixture.runId, "e");
        support.insertSucceededNode(tenant, fixture.runId, "s", WorkflowNodeType.START, "开始", "{}", "运行开始", null);
        support.insertSucceededNode(
                tenant,
                fixture.runId,
                "m",
                WorkflowNodeType.MODEL,
                "模型",
                "{\"text\":\"恢复结果\",\"classificationLabel\":null}",
                "恢复结果",
                null);
        support.insertSucceededNode(
                tenant,
                fixture.runId,
                "e",
                WorkflowNodeType.END,
                "结束",
                "{\"resultFrom\":\"m\",\"format\":\"TEXT\",\"sizeBytes\":12}",
                "已生成结果",
                null);

        execute(tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(support.json(run.resultJson()).path("text").asString()).isEqualTo("恢复结果");
        assertThat(model.invocations()).isZero();
    }

    /** 模型调用记录写入工作流运行/节点关联列。 */
    @Test
    @DisplayName("模型调用关联工作流运行与节点")
    void linkModelInvocation_setsWorkflowColumns() {
        String tenant = uniqueTenant("wf-engine-link");
        support.createTenant(tenant);
        UUID modelId = support.createDisabledModel();
        UUID invocationId = UUID.randomUUID();
        support.insertModelInvocation(tenant, invocationId, modelId);

        TenantContext.runWithTenant(TenantId.of(tenant), () -> repository.linkModelInvocation(
                TenantId.of(tenant), invocationId, UUID.fromString("01930000-0000-7000-8000-000000000001"),
                UUID.fromString("01930000-0000-7000-8000-000000000002")));

        WorkflowEngineTestSupport.ModelLink link = support.readModelLink(tenant, invocationId);
        assertThat(link.workflowRunId()).isEqualTo(UUID.fromString("01930000-0000-7000-8000-000000000001"));
        assertThat(link.workflowNodeRunId()).isEqualTo(UUID.fromString("01930000-0000-7000-8000-000000000002"));
    }

    /**
     * 执行引擎。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param lease 租约
     */
    private void execute(String tenant, UUID runId, TestLease lease) {
        execute(engine(defaultProperties()), tenant, runId, lease);
    }

    /**
     * 执行引擎（指定配置）。
     *
     * @param engine 引擎
     * @param tenant 租户
     * @param runId 运行 ID
     * @param lease 租约
     */
    private void execute(WorkflowEngine engine, String tenant, UUID runId, TestLease lease) {
        ClaimedWorkflowRun claim = new ClaimedWorkflowRun(
                runId, tenant, WorkflowRunStatus.PENDING, clock.instant().plusSeconds(90), "test-lease");
        TenantContext.runWithTenant(TenantId.of(tenant), () -> engine.execute(claim, lease));
    }

    /** 运行输入不是对象：运行入口 WF_INVALID_GRAPH，不执行节点。 */
    @Test
    @DisplayName("IT-WF-109 运行输入非对象在入口拒绝")
    void invalidInputs_failsClosed() {
        String tenant = uniqueTenant("wf-engine-bad-input");
        Fixture fixture = fixture(tenant, singleModelGraph(), "[]", 3);

        execute(tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_INVALID_GRAPH");
        assertThat(support.readAllNodes(tenant, fixture.runId)).isEmpty();
    }

    /** 版本节点数与图不一致：运行入口 fail-closed。 */
    @Test
    @DisplayName("IT-WF-109 版本节点数不一致拒绝执行")
    void versionNodeCountMismatch_failsClosed() {
        String tenant = uniqueTenant("wf-engine-node-count");
        Fixture fixture = fixture(tenant, singleModelGraph(), "{\"topic\":\"市场\"}", 4);

        execute(tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_INVALID_GRAPH");
        assertThat(support.readAllNodes(tenant, fixture.runId)).isEmpty();
    }

    /** 运行状态已不是 RUNNING：当前槽位没有执行权，直接放弃。 */
    @Test
    @DisplayName("IT-WF-105 非 RUNNING 运行不执行")
    void runNotRunning_skipsExecution() {
        String tenant = uniqueTenant("wf-engine-pending");
        Fixture fixture = fixture(tenant, singleModelGraph());
        support.requeue(tenant, fixture.runId, "test-lease", "s");

        execute(tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        assertThat(support.readRun(tenant, fixture.runId).status()).isEqualTo("PENDING");
        assertThat(support.readAllNodes(tenant, fixture.runId)).isEmpty();
        assertThat(model.invocations()).isZero();
    }

    /** 节点尝试不属于发布图：状态重建 fail-closed（WF_INTERNAL）。 */
    @Test
    @DisplayName("IT-WF-105 脏节点尝试拒绝恢复")
    void stateCorrupt_failsClosed() {
        String tenant = uniqueTenant("wf-engine-corrupt");
        Fixture fixture = fixture(tenant, singleModelGraph());
        support.insertSucceededNode(
                tenant, fixture.runId, "ghost", WorkflowNodeType.START, "幽灵", "{}", "摘要", null);

        execute(tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_INTERNAL");
    }

    /** 已成功条件节点输出缺少 branch：恢复时 WF_INVALID_GRAPH，不默认分支。 */
    @Test
    @DisplayName("IT-WF-105 条件输出缺 branch 拒绝恢复")
    void conditionOutputMissingBranch_failsClosed() {
        String tenant = uniqueTenant("wf-engine-cond-corrupt");
        Fixture fixture = fixture(tenant, conditionGraph());
        support.setCurrentNode(tenant, fixture.runId, "c");
        support.insertSucceededNode(tenant, fixture.runId, "s", WorkflowNodeType.START, "开始", "{}", "运行开始", null);
        support.insertSucceededNode(
                tenant, fixture.runId, "r", WorkflowNodeType.RETRIEVAL, "检索", "{\"hitCount\":2}", "命中两条", null);
        support.insertSucceededNode(
                tenant, fixture.runId, "c", WorkflowNodeType.CONDITION, "条件", "{}", "条件", null);

        execute(tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_INVALID_GRAPH");
        assertThat(model.invocations()).isZero();
    }

    /** 租约在节点前已丢失：不执行任何节点、不写终态。 */
    @Test
    @DisplayName("IT-WF-105 初始租约丢失不执行")
    void leaseLostBeforeStart_skipsExecution() {
        String tenant = uniqueTenant("wf-engine-lost-start");
        Fixture fixture = fixture(tenant, singleModelGraph());
        TestLease lease = new TestLease(fixture.runId, tenant);
        lease.markLost();

        execute(tenant, fixture.runId, lease);

        assertThat(support.readRun(tenant, fixture.runId).status()).isEqualTo("RUNNING");
        assertThat(support.readAllNodes(tenant, fixture.runId)).isEmpty();
        assertThat(model.invocations()).isZero();
    }

    /** 节点超时（未超过运行预算）：节点 FAILED WF_NODE_TIMEOUT，运行失败。 */
    @Test
    @DisplayName("IT-WF-109 节点超时映射 WF_NODE_TIMEOUT")
    void nodeTimeout_failsNode() {
        String tenant = uniqueTenant("wf-engine-node-timeout");
        Fixture fixture = fixture(tenant, singleModelGraph());
        model.behavior(execution -> {
            throw new WorkflowNodeTimeoutException("timeout");
        });

        execute(tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_NODE_TIMEOUT");
        assertThat(support.readNodes(tenant, fixture.runId, "m"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo("FAILED");
                    assertThat(row.errorCode()).isEqualTo("WF_NODE_TIMEOUT");
                });
        assertThat(support.readNodes(tenant, fixture.runId, "e"))
                .singleElement()
                .extracting(NodeRow::status)
                .isEqualTo("SKIPPED");
    }

    /** 节点内部异常：节点/运行 WF_INTERNAL，不泄露堆栈。 */
    @Test
    @DisplayName("IT-WF-105 节点内部异常映射 WF_INTERNAL")
    void nodeInternalError_failsRun() {
        String tenant = uniqueTenant("wf-engine-internal");
        Fixture fixture = fixture(tenant, singleModelGraph());
        model.behavior(execution -> {
            throw new IllegalStateException("boom");
        });

        execute(tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_INTERNAL");
        assertThat(run.errorMessage()).isEqualTo("节点执行发生内部错误");
        assertThat(support.readNodes(tenant, fixture.runId, "m"))
                .singleElement()
                .extracting(NodeRow::errorCode)
                .isEqualTo("WF_INTERNAL");
    }

    /** END 没有结果负载：节点/运行 WF_INTERNAL。 */
    @Test
    @DisplayName("IT-WF-105 END 缺少结果负载失败")
    void endWithoutPayload_failsRun() {
        String tenant = uniqueTenant("wf-engine-end-empty");
        Fixture fixture = fixture(tenant, singleModelGraph());
        WorkflowNodeDispatcher custom = new WorkflowNodeDispatcher(List.of(
                new StartNodeExecutor(new WorkflowTemplateRenderer()),
                new StubExecutor(WorkflowNodeType.END, execution -> WorkflowNodeResult.of("in", "out", Map.of(), List.of())),
                new ConditionNodeExecutor(new WorkflowConditionEvaluator()),
                model));
        WorkflowEngine engine = new WorkflowEngine(
                repository, custom, assembler, defaultProperties(), objectMapper, clock);

        execute(engine, tenant, fixture.runId, new TestLease(fixture.runId, tenant));

        RunRow run = support.readRun(tenant, fixture.runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_INTERNAL");
        assertThat(support.readNodes(tenant, fixture.runId, "e"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo("FAILED");
                    assertThat(row.errorCode()).isEqualTo("WF_INTERNAL");
                });
    }

    /**
     * 创建夹具：租户、账号、流程/版本、RUNNING 运行。
     *
     * @param tenant 租户
     * @param graph 图
     * @return 夹具
     */
    private Fixture fixture(String tenant, String graph) {
        return fixture(tenant, graph, "{\"topic\":\"市场\"}", nodeCount(graph));
    }

    /**
     * 创建夹具（自定义输入与版本节点数）。
     *
     * @param tenant 租户
     * @param graph 图
     * @param inputs 运行输入 JSON
     * @param versionNodeCount 版本节点数
     * @return 夹具
     */
    private Fixture fixture(String tenant, String graph, String inputs, int versionNodeCount) {
        support.createTenant(tenant);
        UUID user = support.createUser(tenant);
        Seed seed = support.createWorkflow(tenant, user, graph, versionNodeCount);
        UUID runId = support.insertRun(tenant, seed, user, inputs, "RUNNING", "test-lease", "s", 0L);
        return new Fixture(runId, user);
    }

    /**
     * 创建默认引擎。
     *
     * @param properties 配置
     * @return 引擎
     */
    private WorkflowEngine engine(WorkflowRuntimeProperties properties) {
        return new WorkflowEngine(repository, dispatcher, assembler, properties, objectMapper, clock);
    }

    /**
     * 默认配置。
     *
     * @return 配置
     */
    private static WorkflowRuntimeProperties defaultProperties() {
        return new WorkflowRuntimeProperties(true, "", 50, 200, 300, 120, 60, 30, 10, 5, 5_242_880, 180);
    }

    /**
     * 计算图节点数。
     *
     * @param graph 图 JSON
     * @return 节点数
     */
    private int nodeCount(String graph) {
        return support.json(graph).path("nodes").size();
    }

    /**
     * 模型成功结果。
     *
     * @param text 文本
     * @return 节点结果
     */
    private static WorkflowNodeResult modelSuccess(String text) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("text", text);
        output.put("classificationLabel", null);
        return WorkflowNodeResult.of("模型提示", text, output, List.of());
    }

    /**
     * 数字输出。
     *
     * @param field 字段
     * @param value 值
     * @return 输出
     */
    private static Map<String, Object> numberOutput(String field, int value) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put(field, value);
        return output;
    }

    /**
     * 串行图：START → RETRIEVAL → MODEL → END。
     *
     * @return 图 JSON
     */
    private static String serialGraph() {
        return WorkflowEngineTestSupport.graph(
                List.of(
                        WorkflowEngineTestSupport.node("s", "START", "开始", "{}"),
                        WorkflowEngineTestSupport.node(
                                "r", "RETRIEVAL", "检索", "{\"queryTemplate\":\"{{input.topic}}\",\"topK\":3}"),
                        WorkflowEngineTestSupport.node(
                                "m",
                                "MODEL",
                                "生成",
                                "{\"promptTemplate\":\"请总结：{{node.r.summary}}\",\"outputKind\":\"TEXT\"}"),
                        WorkflowEngineTestSupport.node("e", "END", "结束", "{\"resultFrom\":\"m\"}")),
                List.of(
                        WorkflowEngineTestSupport.edge("e1", "s", "r", null),
                        WorkflowEngineTestSupport.edge("e2", "r", "m", null),
                        WorkflowEngineTestSupport.edge("e3", "m", "e", null)));
    }

    /**
     * 条件图：START → RETRIEVAL → CONDITION →(TRUE/FALSE) 两个模型/END 分支。
     *
     * @return 图 JSON
     */
    private static String conditionGraph() {
        return conditionGraph("NUMBER");
    }

    /**
     * 条件图（hitCount 类型不符）。
     *
     * @return 图 JSON
     */
    private static String conditionGraphWithStringHitCount() {
        return conditionGraph("STRING");
    }

    /**
     * 构造条件图。
     *
     * @param hitCountType 左值类型
     * @return 图 JSON
     */
    private static String conditionGraph(String hitCountType) {
        String condition = "{\"left\":{\"source\":\"NODE_OUTPUT\",\"nodeId\":\"r\",\"path\":\"hitCount\","
                + "\"valueType\":\"" + hitCountType + "\"},\"operator\":\"GTE\","
                + "\"right\":{\"valueType\":\"NUMBER\",\"value\":1}}";
        return WorkflowEngineTestSupport.graph(
                List.of(
                        WorkflowEngineTestSupport.node("s", "START", "开始", "{}"),
                        WorkflowEngineTestSupport.node(
                                "r", "RETRIEVAL", "检索", "{\"queryTemplate\":\"{{input.topic}}\",\"topK\":3}"),
                        WorkflowEngineTestSupport.node(
                                "c", "CONDITION", "条件", "{\"condition\":" + condition + "}"),
                        WorkflowEngineTestSupport.node(
                                "t", "MODEL", "真分支", "{\"promptTemplate\":\"true\",\"outputKind\":\"TEXT\"}"),
                        WorkflowEngineTestSupport.node(
                                "f", "MODEL", "假分支", "{\"promptTemplate\":\"false\",\"outputKind\":\"TEXT\"}"),
                        WorkflowEngineTestSupport.node("et", "END", "真结束", "{\"resultFrom\":\"t\"}"),
                        WorkflowEngineTestSupport.node("ef", "END", "假结束", "{\"resultFrom\":\"f\"}")),
                List.of(
                        WorkflowEngineTestSupport.edge("e1", "s", "r", null),
                        WorkflowEngineTestSupport.edge("e2", "r", "c", null),
                        WorkflowEngineTestSupport.edge("e3", "c", "t", "TRUE"),
                        WorkflowEngineTestSupport.edge("e4", "c", "f", "FALSE"),
                        WorkflowEngineTestSupport.edge("e5", "t", "et", null),
                        WorkflowEngineTestSupport.edge("e6", "f", "ef", null)));
    }

    /**
     * 线性模型图：START → a → b → c → END。
     *
     * @return 图 JSON
     */
    private static String linearModelGraph() {
        return WorkflowEngineTestSupport.graph(
                List.of(
                        WorkflowEngineTestSupport.node("s", "START", "开始", "{}"),
                        WorkflowEngineTestSupport.node(
                                "a", "MODEL", "模型A", "{\"promptTemplate\":\"a\",\"outputKind\":\"TEXT\"}"),
                        WorkflowEngineTestSupport.node(
                                "b", "MODEL", "模型B", "{\"promptTemplate\":\"b\",\"outputKind\":\"TEXT\"}"),
                        WorkflowEngineTestSupport.node(
                                "c", "MODEL", "模型C", "{\"promptTemplate\":\"c\",\"outputKind\":\"TEXT\"}"),
                        WorkflowEngineTestSupport.node("e", "END", "结束", "{\"resultFrom\":\"c\"}")),
                List.of(
                        WorkflowEngineTestSupport.edge("e1", "s", "a", null),
                        WorkflowEngineTestSupport.edge("e2", "a", "b", null),
                        WorkflowEngineTestSupport.edge("e3", "b", "c", null),
                        WorkflowEngineTestSupport.edge("e4", "c", "e", null)));
    }

    /**
     * 两模型图：START → a → b → END。
     *
     * @return 图 JSON
     */
    private static String twoModelGraph() {
        return WorkflowEngineTestSupport.graph(
                List.of(
                        WorkflowEngineTestSupport.node("s", "START", "开始", "{}"),
                        WorkflowEngineTestSupport.node(
                                "a", "MODEL", "模型A", "{\"promptTemplate\":\"a\",\"outputKind\":\"TEXT\"}"),
                        WorkflowEngineTestSupport.node(
                                "b", "MODEL", "模型B", "{\"promptTemplate\":\"b\",\"outputKind\":\"TEXT\"}"),
                        WorkflowEngineTestSupport.node("e", "END", "结束", "{\"resultFrom\":\"b\"}")),
                List.of(
                        WorkflowEngineTestSupport.edge("e1", "s", "a", null),
                        WorkflowEngineTestSupport.edge("e2", "a", "b", null),
                        WorkflowEngineTestSupport.edge("e3", "b", "e", null)));
    }

    /**
     * 单模型图：START → MODEL → END。
     *
     * @return 图 JSON
     */
    private static String singleModelGraph() {
        return WorkflowEngineTestSupport.graph(
                List.of(
                        WorkflowEngineTestSupport.node("s", "START", "开始", "{}"),
                        WorkflowEngineTestSupport.node(
                                "m", "MODEL", "模型", "{\"promptTemplate\":\"m\",\"outputKind\":\"TEXT\"}"),
                        WorkflowEngineTestSupport.node("e", "END", "结束", "{\"resultFrom\":\"m\"}")),
                List.of(
                        WorkflowEngineTestSupport.edge("e1", "s", "m", null),
                        WorkflowEngineTestSupport.edge("e2", "m", "e", null)));
    }

    /**
     * 测试夹具。
     *
     * @param runId 运行 ID
     * @param user 发起人
     */
    private record Fixture(UUID runId, UUID user) {}
}
