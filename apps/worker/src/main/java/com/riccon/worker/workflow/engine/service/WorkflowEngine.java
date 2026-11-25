package com.riccon.worker.workflow.engine.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowEdgeBranch;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.platform.domain.workflow.WorkflowResultStatus;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import com.riccon.worker.common.UuidV7;
import com.riccon.worker.workflow.config.WorkflowRuntimeProperties;
import com.riccon.worker.workflow.engine.domain.WorkflowEngineLimits;
import com.riccon.worker.workflow.engine.domain.WorkflowGraph;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphEdge;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphFormatException;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphJson;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphNode;
import com.riccon.worker.workflow.engine.domain.WorkflowNodeAttemptInsert;
import com.riccon.worker.workflow.engine.domain.WorkflowNodeFinish;
import com.riccon.worker.workflow.engine.domain.WorkflowRunGraphSnapshot;
import com.riccon.worker.workflow.engine.repository.WorkflowEngineRepository;
import com.riccon.worker.workflow.heartbeat.domain.WorkflowLease;
import com.riccon.worker.workflow.node.domain.WorkflowDeadline;
import com.riccon.worker.workflow.node.domain.WorkflowNodeAbortedException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecution;
import com.riccon.worker.workflow.node.domain.WorkflowNodeFailureException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeGuard;
import com.riccon.worker.workflow.node.domain.WorkflowNodeOutput;
import com.riccon.worker.workflow.node.domain.WorkflowNodeResult;
import com.riccon.worker.workflow.node.domain.WorkflowNodeTimeoutException;
import com.riccon.worker.workflow.node.domain.WorkflowResultPayload;
import com.riccon.worker.workflow.node.service.WorkflowNodeDispatcher;
import com.riccon.worker.workflow.node.service.WorkflowResultAssembler;
import com.riccon.worker.workflow.queue.domain.ClaimedWorkflowRun;
import com.riccon.worker.workflow.queue.domain.WorkflowRunExecutor;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M06 串行运行引擎（{@link WorkflowRunExecutor} 实现）。
 *
 * <p>由 T06a 运行队列在已打开租户上下文、已启动租约心跳后调用；本类只做串行节点推进，
 * 不负责领取、续期或领取次数兜底。每次节点尝试：
 * <ol>
 *   <li>从 {@code workflow_version.graph_json} 解析发布图并做运行入口结构校验；
 *   <li>用 {@code current_node_key} 继续（新建为 START，重试为失败节点），读取已有尝试；
 *   <li>创建/复用尝试，条件更新 PENDING→RUNNING，写当前节点；
 *   <li>按类型分派执行器（START/END/RETRIEVAL/MODEL/CONDITION）；
 *   <li>成功写输出/摘要/引用；条件只走一条分支并跳过另一支；END 组装 ≤5 MiB 结果；
 *   <li>失败/超时把节点置终态、下游 SKIPPED、运行 FAILED；取消停止后续节点；租约丢失直接丢弃结果。
 * </ol>
 *
 * <p>所有运行/节点终态更新都带租约条件；上游成功节点不因重试重跑（ADR-0024 §4.2/§4.4/§4.6）。
 * 引擎不解释脚本、表达式或 HTTP，也不在日志中输出提示词、工具参数、凭据或文档正文。
 *
 * <p>{@code @Profile("!test")}：Worker 集成测试共享上下文会启动真实调度槽位，若测试同时手工领取
 * 或构造 PENDING 运行，会与后台槽位竞争同一行。测试 profile 下不注册执行器（T06a 启动器保持
 * “executor_missing” 惰性），引擎逻辑由测试手动构造 {@code WorkflowEngine} 覆盖；槽位自动启动的
 * 装配逻辑由 T06a 的 {@code WorkflowRunSchedulerTest} 与生产 profile 覆盖。
 */
@Profile("!test")
@Component
public class WorkflowEngine implements WorkflowRunExecutor {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowEngine.class);

    /** 结果生成时间使用北京时间偏移（合同默认时区）。 */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    /** 跳过的下游节点摘要。 */
    private static final String SKIPPED_SUMMARY = "未执行：未走条件分支或上游节点失败";

    /** 取消后的节点摘要。 */
    private static final String CANCELLED_SUMMARY = "运行已取消";

    /** 引擎仓储。 */
    private final WorkflowEngineRepository repository;

    /** 节点分派。 */
    private final WorkflowNodeDispatcher dispatcher;

    /** 结果组装（END 崩溃恢复复用）。 */
    private final WorkflowResultAssembler resultAssembler;

    /** 执行期配置。 */
    private final WorkflowRuntimeProperties properties;

    /** JSON 序列化。 */
    private final ObjectMapper objectMapper;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建串行引擎。
     *
     * @param repository 引擎仓储
     * @param dispatcher 节点分派
     * @param resultAssembler 结果组装
     * @param properties 执行期配置
     * @param objectMapper JSON 序列化
     * @param clock UTC 时钟
     */
    public WorkflowEngine(
            WorkflowEngineRepository repository,
            WorkflowNodeDispatcher dispatcher,
            WorkflowResultAssembler resultAssembler,
            WorkflowRuntimeProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.resultAssembler = Objects.requireNonNull(resultAssembler, "resultAssembler must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public void execute(ClaimedWorkflowRun claim, WorkflowLease lease) {
        TenantId tenantId = TenantId.of(claim.tenantId());
        UUID runId = claim.runId();
        Instant segmentStart = clock.instant();
        WorkflowRunGraphSnapshot run = repository.findRunGraph(tenantId, runId).orElse(null);
        if (run == null) {
            LOG.warn("workflow.engine.run_missing runId={} tenantId={}", runId, tenantId.value());
            return;
        }
        if (run.status() != WorkflowRunStatus.RUNNING) {
            LOG.warn(
                    "workflow.engine.run_not_running runId={} tenantId={} status={}",
                    runId,
                    tenantId.value(),
                    run.status());
            return;
        }
        WorkflowGraph graph;
        try {
            graph = WorkflowGraphJson.parse(run.graphJson(), properties.maxNodes());
            graph.validateForRun();
            if (graph.nodes().size() != run.versionNodeCount()) {
                throw new WorkflowGraphFormatException("发布图节点数与版本快照不一致");
            }
        } catch (WorkflowGraphFormatException ex) {
            LOG.warn(
                    "workflow.engine.graph_invalid runId={} tenantId={} reason={}",
                    runId,
                    tenantId.value(),
                    ex.getMessage());
            failRun(
                    tenantId,
                    claim,
                    run.currentNodeKey(),
                    WorkflowErrorCode.WF_INVALID_GRAPH,
                    ex.getMessage(),
                    WorkflowResultStatus.NONE,
                    0L,
                    segmentStart);
            return;
        }
        JsonNode inputs;
        try {
            inputs = objectMapper.readTree(run.inputsJson());
        } catch (JacksonException ex) {
            failRun(
                    tenantId,
                    claim,
                    run.currentNodeKey(),
                    WorkflowErrorCode.WF_INVALID_GRAPH,
                    "运行输入不是合法 JSON",
                    WorkflowResultStatus.NONE,
                    0L,
                    segmentStart);
            return;
        }
        if (inputs == null || !inputs.isObject()) {
            failRun(
                    tenantId,
                    claim,
                    run.currentNodeKey(),
                    WorkflowErrorCode.WF_INVALID_GRAPH,
                    "运行输入必须是对象",
                    WorkflowResultStatus.NONE,
                    0L,
                    segmentStart);
            return;
        }
        WorkflowExecutionState state;
        try {
            state = WorkflowExecutionState.load(graph, repository.findNodeAttempts(tenantId, runId), objectMapper);
        } catch (WorkflowNodeFailureException ex) {
            LOG.error("workflow.engine.state_corrupt runId={} tenantId={}", runId, tenantId.value(), ex);
            failRun(
                    tenantId,
                    claim,
                    run.currentNodeKey(),
                    ex.errorCode(),
                    ex.getMessage(),
                    WorkflowResultStatus.NONE,
                    0L,
                    segmentStart);
            return;
        }
        String currentKey = run.currentNodeKey() == null || run.currentNodeKey().isBlank()
                ? graph.startNode().id()
                : run.currentNodeKey();
        long executionMs = run.executionMs();
        long runBudgetMs = properties.runTimeoutMinutes() * 60_000L;
        String traceId = "workflow-run-" + runId;
        WorkflowNodeGuard guard = new LeaseGuard(lease);
        while (true) {
            if (lease.lost()) {
                LOG.warn(
                        "workflow.engine.lease_lost runId={} tenantId={} nodeKey={}",
                        runId,
                        tenantId.value(),
                        currentKey);
                return;
            }
            if (lease.cancelled()) {
                cancelRemaining(tenantId, runId);
                return;
            }
            long elapsed = executionMs
                    + Duration.between(segmentStart, clock.instant()).toMillis();
            if (elapsed >= runBudgetMs) {
                handleRunTimeout(tenantId, claim, graph, state, currentKey, segmentStart);
                return;
            }
            WorkflowGraphNode node = graph.findNode(currentKey).orElse(null);
            if (node == null) {
                failRun(
                        tenantId,
                        claim,
                        currentKey,
                        WorkflowErrorCode.WF_INVALID_GRAPH,
                        "当前节点不在发布图中",
                        WorkflowResultStatus.NONE,
                        0L,
                        segmentStart);
                return;
            }
            WorkflowExecutionState.NodeState latest = state.latest(currentKey).orElse(null);
            if (latest != null && latest.status() == WorkflowNodeStatus.SUCCEEDED) {
                final String next;
                try {
                    next = nextKey(graph, node, state);
                } catch (WorkflowGraphFormatException ex) {
                    failRun(
                            tenantId,
                            claim,
                            currentKey,
                            WorkflowErrorCode.WF_INVALID_GRAPH,
                            ex.getMessage(),
                            WorkflowResultStatus.NONE,
                            0L,
                            segmentStart);
                    return;
                }
                if (next == null) {
                    // END 已成功但运行仍在 RUNNING：上次进程在写结果前崩溃，按存储输出重建结果。
                    recoverEnd(tenantId, claim, graph, state, node, segmentStart);
                    return;
                }
                currentKey = next;
                continue;
            }
            if (state.attemptCount() >= properties.maxSteps()) {
                handleStepLimit(tenantId, claim, graph, state, currentKey, segmentStart);
                return;
            }
            int attempt;
            UUID attemptId;
            if (latest != null && latest.status() == WorkflowNodeStatus.PENDING) {
                // 重试/崩溃恢复已创建 PENDING 尝试：复用同一行与 ID，不插入重复尝试。
                attempt = latest.attempt();
                attemptId = latest.id();
            } else {
                if (latest != null && latest.status() == WorkflowNodeStatus.RUNNING) {
                    // 上次进程在节点执行中崩溃：把遗留尝试标记失败，换下一尝试号继续。
                    repository.failStaleAttempt(tenantId, runId, currentKey, latest.attempt(), clock.instant());
                    state.put(currentKey, latest.attempt(), WorkflowNodeStatus.FAILED);
                }
                attempt = latest == null ? 1 : latest.attempt() + 1;
                attemptId = UuidV7.generate();
                Instant createdAt = clock.instant();
                repository.insertNodeAttempt(
                        tenantId,
                        new WorkflowNodeAttemptInsert(
                                attemptId,
                                runId,
                                currentKey,
                                node.type(),
                                node.name(),
                                attempt,
                                WorkflowNodeStatus.PENDING,
                                null,
                                null,
                                null,
                                null,
                                createdAt));
                state.addAttempt(currentKey, attemptId, attempt, WorkflowNodeStatus.PENDING);
            }
            Instant nodeStart = clock.instant();
            if (repository.startNodeAttempt(tenantId, runId, currentKey, attempt, nodeStart) == 0) {
                LOG.warn("workflow.engine.node_start_race runId={} nodeKey={} attempt={}", runId, currentKey, attempt);
                return;
            }
            state.put(currentKey, attempt, WorkflowNodeStatus.RUNNING);
            if (repository.updateCurrentNode(tenantId, runId, claim.leaseOwner(), currentKey, nodeStart) == 0) {
                LOG.warn("workflow.engine.current_node_lost runId={} nodeKey={}", runId, currentKey);
                return;
            }
            WorkflowDeadline deadline = deadlineFor(node.type(), runBudgetMs - elapsed);
            WorkflowNodeExecution execution = new WorkflowNodeExecution(
                    tenantId,
                    runId,
                    attemptId,
                    run.requesterUserId(),
                    traceId,
                    inputs,
                    graph,
                    node,
                    state.outputs(),
                    state.outputBytesTotal(),
                    state.aggregateCitations(graph),
                    deadline,
                    guard);
            try {
                WorkflowNodeResult result = dispatcher.execute(execution);
                if (lease.lost()) {
                    // 租约已丢失：结果必须丢弃，避免与新租约持有者双写。
                    return;
                }
                Prepared prepared = prepare(result);
                if (node.type() == WorkflowNodeType.END) {
                    finalizeEnd(tenantId, claim, node, attempt, result, prepared, state, segmentStart);
                    return;
                }
                if (repository.finishNodeAttempt(
                                tenantId,
                                new WorkflowNodeFinish(
                                        runId,
                                        currentKey,
                                        attempt,
                                        WorkflowNodeStatus.SUCCEEDED,
                                        result.inputSummary(),
                                        result.outputSummary(),
                                        prepared.outputJson(),
                                        prepared.citationsJson(),
                                        null,
                                        null,
                                        clock.instant()))
                        == 0) {
                    LOG.warn("workflow.engine.node_finish_lost runId={} nodeKey={}", runId, currentKey);
                    return;
                }
                state.recordSuccess(
                        currentKey,
                        attempt,
                        prepared.output(),
                        result.outputSummary(),
                        prepared.outputJson(),
                        prepared.citationsJson(),
                        result.citations());
                Instant now = clock.instant();
                long delta = Math.max(0L, Duration.between(segmentStart, now).toMillis());
                if (repository.addExecutionMs(tenantId, runId, claim.leaseOwner(), delta, now) == 0) {
                    LOG.warn("workflow.engine.execution_ms_lost runId={} nodeKey={}", runId, currentKey);
                    return;
                }
                segmentStart = now;
                executionMs += delta;
                if (lease.lost()) {
                    return;
                }
                if (lease.cancelled()) {
                    cancelRemaining(tenantId, runId);
                    return;
                }
                if (node.type() == WorkflowNodeType.CONDITION) {
                    WorkflowEdgeBranch branch = branchOf(state, currentKey);
                    skipBranch(
                            tenantId,
                            claim,
                            graph,
                            state,
                            node,
                            branch == WorkflowEdgeBranch.TRUE ? WorkflowEdgeBranch.FALSE : WorkflowEdgeBranch.TRUE);
                    currentKey = branchTarget(graph, node, branch);
                } else {
                    currentKey = singleTarget(graph, node);
                }
            } catch (NodeOutputLimitException ex) {
                if (lease.lost()) {
                    return;
                }
                finishNodeFailure(
                        tenantId,
                        runId,
                        currentKey,
                        attempt,
                        WorkflowNodeStatus.FAILED,
                        WorkflowErrorCode.WF_RESULT_LIMIT,
                        ex.getMessage(),
                        null,
                        ex.getMessage());
                skipDownstream(tenantId, claim, graph, state, currentKey, SKIPPED_SUMMARY);
                failRun(
                        tenantId,
                        claim,
                        currentKey,
                        WorkflowErrorCode.WF_RESULT_LIMIT,
                        ex.getMessage(),
                        WorkflowResultStatus.OVER_LIMIT,
                        ex.size(),
                        segmentStart);
                return;
            } catch (WorkflowNodeAbortedException ex) {
                if (ex.lost()) {
                    LOG.warn("workflow.engine.aborted_lease_lost runId={} nodeKey={}", runId, currentKey);
                    return;
                }
                finishNodeFailure(
                        tenantId,
                        runId,
                        currentKey,
                        attempt,
                        WorkflowNodeStatus.CANCELLED,
                        WorkflowErrorCode.WF_CANCELLED,
                        CANCELLED_SUMMARY,
                        null,
                        CANCELLED_SUMMARY);
                cancelRemaining(tenantId, runId);
                return;
            } catch (WorkflowNodeTimeoutException ex) {
                if (lease.lost()) {
                    return;
                }
                long elapsedAtFailure = executionMs
                        + Duration.between(segmentStart, clock.instant()).toMillis();
                boolean runExceeded = elapsedAtFailure >= runBudgetMs;
                WorkflowErrorCode code =
                        runExceeded ? WorkflowErrorCode.WF_RUN_TIMEOUT : WorkflowErrorCode.WF_NODE_TIMEOUT;
                String message = runExceeded ? runTimeoutMessage() : "节点执行超过超时上限";
                finishNodeFailure(
                        tenantId,
                        runId,
                        currentKey,
                        attempt,
                        runExceeded ? WorkflowNodeStatus.CANCELLED : WorkflowNodeStatus.FAILED,
                        code,
                        message,
                        null,
                        message);
                skipDownstream(tenantId, claim, graph, state, currentKey, "上游节点超时未执行");
                failRun(tenantId, claim, currentKey, code, message, WorkflowResultStatus.NONE, 0L, segmentStart);
                return;
            } catch (WorkflowNodeFailureException ex) {
                if (lease.lost()) {
                    return;
                }
                finishNodeFailure(
                        tenantId,
                        runId,
                        currentKey,
                        attempt,
                        WorkflowNodeStatus.FAILED,
                        ex.errorCode(),
                        ex.getMessage(),
                        null,
                        null);
                skipDownstream(tenantId, claim, graph, state, currentKey, SKIPPED_SUMMARY);
                failRun(
                        tenantId,
                        claim,
                        currentKey,
                        ex.errorCode(),
                        ex.getMessage(),
                        WorkflowResultStatus.NONE,
                        0L,
                        segmentStart);
                return;
            } catch (RuntimeException ex) {
                if (lease.lost()) {
                    LOG.warn("workflow.engine.internal_after_lease_lost runId={} nodeKey={}", runId, currentKey);
                    return;
                }
                LOG.error(
                        "workflow.engine.node_internal_failed runId={} tenantId={} nodeKey={}",
                        runId,
                        tenantId.value(),
                        currentKey,
                        ex);
                String message = "节点执行发生内部错误";
                finishNodeFailure(
                        tenantId,
                        runId,
                        currentKey,
                        attempt,
                        WorkflowNodeStatus.FAILED,
                        WorkflowErrorCode.WF_INTERNAL,
                        message,
                        null,
                        null);
                skipDownstream(tenantId, claim, graph, state, currentKey, SKIPPED_SUMMARY);
                failRun(
                        tenantId,
                        claim,
                        currentKey,
                        WorkflowErrorCode.WF_INTERNAL,
                        message,
                        WorkflowResultStatus.NONE,
                        0L,
                        segmentStart);
                return;
            }
        }
    }

    /**
     * 序列化节点输出/引用并执行单节点 256 KiB 检查。
     *
     * @param result 节点结果
     * @return 序列化结果
     * @throws NodeOutputLimitException 输出超过 256 KiB
     */
    private Prepared prepare(WorkflowNodeResult result) {
        String outputJson = serialize(result.output());
        long outputBytes = bytes(outputJson);
        String citationsJson = result.citations().isEmpty() ? null : serialize(result.citations());
        long citationBytes = citationsJson == null ? 0L : bytes(citationsJson);
        if (outputBytes > WorkflowEngineLimits.NODE_OUTPUT_MAX_BYTES) {
            throw new NodeOutputLimitException("节点输出超过 256 KiB 上限，未保存", outputBytes);
        }
        return new Prepared(outputJson, outputBytes, citationsJson, citationBytes, parseOutput(outputJson));
    }

    /**
     * END 成功路径：检查 5 MiB 结果预算、写节点与运行成功。
     *
     * @param tenantId 租户
     * @param claim 领取元数据
     * @param node END 节点
     * @param attempt 尝试号
     * @param result 节点结果
     * @param prepared 序列化输出
     * @param state 运行状态
     * @param segmentStart 本段开始时间
     */
    private void finalizeEnd(
            TenantId tenantId,
            ClaimedWorkflowRun claim,
            WorkflowGraphNode node,
            int attempt,
            WorkflowNodeResult result,
            Prepared prepared,
            WorkflowExecutionState state,
            Instant segmentStart) {
        WorkflowResultPayload payload = result.result();
        if (payload == null) {
            String message = "END 节点没有生成结果";
            finishNodeFailure(
                    tenantId,
                    claim.runId(),
                    node.id(),
                    attempt,
                    WorkflowNodeStatus.FAILED,
                    WorkflowErrorCode.WF_INTERNAL,
                    message,
                    result.inputSummary(),
                    message);
            failRun(
                    tenantId,
                    claim,
                    node.id(),
                    WorkflowErrorCode.WF_INTERNAL,
                    message,
                    WorkflowResultStatus.NONE,
                    0L,
                    segmentStart);
            return;
        }
        String resultJson = resultJson(payload);
        long resultBytes = bytes(resultJson);
        long totalBytes = state.outputBytesTotal() + prepared.outputBytes() + prepared.citationBytes() + resultBytes;
        if (resultBytes > properties.resultMaxBytes() || totalBytes > properties.resultMaxBytes()) {
            long size = Math.max(resultBytes, totalBytes);
            String message = "运行结果超过大小上限，未保存完整结果";
            finishNodeFailure(
                    tenantId,
                    claim.runId(),
                    node.id(),
                    attempt,
                    WorkflowNodeStatus.FAILED,
                    WorkflowErrorCode.WF_RESULT_LIMIT,
                    message,
                    result.inputSummary(),
                    message);
            failRun(
                    tenantId,
                    claim,
                    node.id(),
                    WorkflowErrorCode.WF_RESULT_LIMIT,
                    message,
                    WorkflowResultStatus.OVER_LIMIT,
                    size,
                    segmentStart);
            return;
        }
        if (repository.finishNodeAttempt(
                        tenantId,
                        new WorkflowNodeFinish(
                                claim.runId(),
                                node.id(),
                                attempt,
                                WorkflowNodeStatus.SUCCEEDED,
                                result.inputSummary(),
                                result.outputSummary(),
                                prepared.outputJson(),
                                prepared.citationsJson(),
                                null,
                                null,
                                clock.instant()))
                == 0) {
            LOG.warn("workflow.engine.node_finish_lost runId={} nodeKey={}", claim.runId(), node.id());
            return;
        }
        state.recordSuccess(
                node.id(),
                attempt,
                prepared.output(),
                result.outputSummary(),
                prepared.outputJson(),
                prepared.citationsJson(),
                result.citations());
        Instant now = clock.instant();
        long delta = Math.max(0L, Duration.between(segmentStart, now).toMillis());
        if (repository.finishRunSucceeded(
                        tenantId, claim.runId(), claim.leaseOwner(), node.id(), resultJson, resultBytes, delta, now)
                == 0) {
            LOG.warn(
                    "workflow.engine.run_finish_lost runId={} tenantId={} nodeKey={}",
                    claim.runId(),
                    tenantId.value(),
                    node.id());
        }
    }

    /**
     * END 已成功但运行未结束的恢复路径：按存储输出重建结果并写运行成功。
     *
     * @param tenantId 租户
     * @param claim 领取元数据
     * @param graph 发布图
     * @param state 运行状态
     * @param node END 节点
     * @param segmentStart 本段开始时间
     */
    private void recoverEnd(
            TenantId tenantId,
            ClaimedWorkflowRun claim,
            WorkflowGraph graph,
            WorkflowExecutionState state,
            WorkflowGraphNode node,
            Instant segmentStart) {
        try {
            WorkflowResultPayload payload = resultAssembler.assemble(graph, node, state.outputs());
            String resultJson = resultJson(payload);
            long resultBytes = bytes(resultJson);
            long totalBytes = state.outputBytesTotal() + resultBytes;
            if (resultBytes > properties.resultMaxBytes() || totalBytes > properties.resultMaxBytes()) {
                failRun(
                        tenantId,
                        claim,
                        node.id(),
                        WorkflowErrorCode.WF_RESULT_LIMIT,
                        "运行结果超过大小上限，未保存完整结果",
                        WorkflowResultStatus.OVER_LIMIT,
                        Math.max(resultBytes, totalBytes),
                        segmentStart);
                return;
            }
            Instant now = clock.instant();
            long delta = Math.max(0L, Duration.between(segmentStart, now).toMillis());
            if (repository.finishRunSucceeded(
                            tenantId, claim.runId(), claim.leaseOwner(), node.id(), resultJson, resultBytes, delta, now)
                    == 0) {
                LOG.warn("workflow.engine.run_finish_lost runId={} nodeKey={}", claim.runId(), node.id());
            }
        } catch (WorkflowNodeFailureException ex) {
            failRun(
                    tenantId,
                    claim,
                    node.id(),
                    ex.errorCode(),
                    ex.getMessage(),
                    WorkflowResultStatus.NONE,
                    0L,
                    segmentStart);
        }
    }

    /**
     * 计算下一个节点 key；END 返回 {@code null}。
     *
     * @param graph 发布图
     * @param node 当前节点
     * @param state 运行状态
     * @return 下一个节点 key；END 结束为空
     */
    private static String nextKey(WorkflowGraph graph, WorkflowGraphNode node, WorkflowExecutionState state) {
        if (node.type() == WorkflowNodeType.END) {
            return null;
        }
        if (node.type() == WorkflowNodeType.CONDITION) {
            return branchTarget(graph, node, branchOf(state, node.id()));
        }
        return singleTarget(graph, node);
    }

    /**
     * 读取成功条件节点的分支。
     *
     * @param state 运行状态
     * @param nodeKey 条件节点 key
     * @return 分支
     */
    private static WorkflowEdgeBranch branchOf(WorkflowExecutionState state, String nodeKey) {
        WorkflowNodeOutput output = state.outputs().get(nodeKey);
        if (output == null) {
            throw new WorkflowGraphFormatException("条件节点输出缺失");
        }
        JsonNode branch = output.output().get("branch");
        if (branch == null || !branch.isTextual()) {
            throw new WorkflowGraphFormatException("条件节点输出缺少 branch");
        }
        try {
            return WorkflowEdgeBranch.valueOf(branch.asString());
        } catch (IllegalArgumentException ex) {
            throw new WorkflowGraphFormatException("条件节点分支非法");
        }
    }

    /**
     * 返回条件节点指定分支的目标。
     *
     * @param graph 发布图
     * @param node 条件节点
     * @param branch 分支
     * @return 目标节点 key
     */
    private static String branchTarget(WorkflowGraph graph, WorkflowGraphNode node, WorkflowEdgeBranch branch) {
        return graph.branchEdge(node.id(), branch)
                .orElseThrow(() -> new WorkflowGraphFormatException("条件节点缺少分支边"))
                .target();
    }

    /**
     * 返回普通节点的唯一出边目标。
     *
     * @param graph 发布图
     * @param node 节点
     * @return 目标节点 key
     */
    private static String singleTarget(WorkflowGraph graph, WorkflowGraphNode node) {
        List<WorkflowGraphEdge> outgoing = graph.outgoing(node.id());
        if (outgoing.size() != 1) {
            throw new WorkflowGraphFormatException("节点缺少唯一出边");
        }
        return outgoing.getFirst().target();
    }

    /**
     * 跳过条件节点未走分支的全部可达节点。
     *
     * @param tenantId 租户
     * @param claim 领取元数据
     * @param graph 发布图
     * @param state 运行状态
     * @param node 条件节点
     * @param branch 未走分支
     */
    private void skipBranch(
            TenantId tenantId,
            ClaimedWorkflowRun claim,
            WorkflowGraph graph,
            WorkflowExecutionState state,
            WorkflowGraphNode node,
            WorkflowEdgeBranch branch) {
        graph.branchEdge(node.id(), branch)
                .ifPresent(edge -> skipDownstream(tenantId, claim, graph, state, edge.target(), SKIPPED_SUMMARY));
    }

    /**
     * 把指定节点及其可达下游标记为 SKIPPED（PENDING→SKIPPED；无行则插入一次 SKIPPED 尝试）。
     *
     * @param tenantId 租户
     * @param claim 领取元数据
     * @param graph 发布图
     * @param state 运行状态
     * @param startKey 起始节点
     * @param summary 跳过原因
     */
    private void skipDownstream(
            TenantId tenantId,
            ClaimedWorkflowRun claim,
            WorkflowGraph graph,
            WorkflowExecutionState state,
            String startKey,
            String summary) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(startKey);
        keys.addAll(graph.downstreamKeys(startKey));
        for (String key : keys) {
            WorkflowGraphNode node = graph.findNode(key).orElse(null);
            if (node == null) {
                continue;
            }
            WorkflowExecutionState.NodeState latest = state.latest(key).orElse(null);
            if (latest == null) {
                repository.insertNodeAttempt(
                        tenantId,
                        new WorkflowNodeAttemptInsert(
                                UuidV7.generate(),
                                claim.runId(),
                                key,
                                node.type(),
                                node.name(),
                                1,
                                WorkflowNodeStatus.SKIPPED,
                                null,
                                summary,
                                null,
                                null,
                                clock.instant()));
                state.put(key, 1, WorkflowNodeStatus.SKIPPED);
                continue;
            }
            if (latest.status() == WorkflowNodeStatus.PENDING) {
                repository.skipPendingNode(tenantId, claim.runId(), key, summary, clock.instant());
                state.put(key, latest.attempt(), WorkflowNodeStatus.SKIPPED);
            }
        }
    }

    /**
     * 取消运行后把剩余节点置为 CANCELLED。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     */
    private void cancelRemaining(TenantId tenantId, UUID runId) {
        repository.cancelRemainingNodes(tenantId, runId, CANCELLED_SUMMARY, clock.instant());
    }

    /**
     * 运行超时：当前节点 CANCELLED、下游 SKIPPED、运行 FAILED（WF_RUN_TIMEOUT）。
     *
     * @param tenantId 租户
     * @param claim 领取元数据
     * @param graph 发布图
     * @param state 运行状态
     * @param currentKey 当前节点
     * @param segmentStart 本段开始时间
     */
    private void handleRunTimeout(
            TenantId tenantId,
            ClaimedWorkflowRun claim,
            WorkflowGraph graph,
            WorkflowExecutionState state,
            String currentKey,
            Instant segmentStart) {
        String message = runTimeoutMessage();
        WorkflowExecutionState.NodeState latest = state.latest(currentKey).orElse(null);
        if (latest != null && latest.status() == WorkflowNodeStatus.RUNNING) {
            finishNodeFailure(
                    tenantId,
                    claim.runId(),
                    currentKey,
                    latest.attempt(),
                    WorkflowNodeStatus.CANCELLED,
                    WorkflowErrorCode.WF_RUN_TIMEOUT,
                    message,
                    null,
                    message);
        } else if (latest != null && latest.status() == WorkflowNodeStatus.PENDING) {
            repository.closePendingNode(
                    tenantId,
                    claim.runId(),
                    currentKey,
                    WorkflowNodeStatus.CANCELLED,
                    WorkflowErrorCode.WF_RUN_TIMEOUT,
                    message,
                    clock.instant());
            state.put(currentKey, latest.attempt(), WorkflowNodeStatus.CANCELLED);
        } else if (latest == null && graph.findNode(currentKey).isPresent()) {
            WorkflowGraphNode node = graph.requireNode(currentKey);
            repository.insertNodeAttempt(
                    tenantId,
                    new WorkflowNodeAttemptInsert(
                            UuidV7.generate(),
                            claim.runId(),
                            currentKey,
                            node.type(),
                            node.name(),
                            1,
                            WorkflowNodeStatus.CANCELLED,
                            null,
                            message,
                            WorkflowErrorCode.WF_RUN_TIMEOUT.name(),
                            message,
                            clock.instant()));
            state.put(currentKey, 1, WorkflowNodeStatus.CANCELLED);
        }
        skipDownstream(tenantId, claim, graph, state, currentKey, "运行超时未执行");
        failRun(
                tenantId,
                claim,
                currentKey,
                WorkflowErrorCode.WF_RUN_TIMEOUT,
                message,
                WorkflowResultStatus.NONE,
                0L,
                segmentStart);
    }

    /**
     * 步数超限：当前节点关闭为 FAILED、下游 SKIPPED、运行 FAILED（WF_STEP_LIMIT）。
     *
     * @param tenantId 租户
     * @param claim 领取元数据
     * @param graph 发布图
     * @param state 运行状态
     * @param currentKey 当前节点
     * @param segmentStart 本段开始时间
     */
    private void handleStepLimit(
            TenantId tenantId,
            ClaimedWorkflowRun claim,
            WorkflowGraph graph,
            WorkflowExecutionState state,
            String currentKey,
            Instant segmentStart) {
        String message = "运行超过步数上限 " + properties.maxSteps();
        WorkflowExecutionState.NodeState latest = state.latest(currentKey).orElse(null);
        if (latest != null && latest.status() == WorkflowNodeStatus.RUNNING) {
            // 步数耗尽发生在崩溃遗留 RUNNING 尝试清理之前：先写 FAILED，保证 POST /retry 可用。
            finishNodeFailure(
                    tenantId,
                    claim.runId(),
                    currentKey,
                    latest.attempt(),
                    WorkflowNodeStatus.FAILED,
                    WorkflowErrorCode.WF_STEP_LIMIT,
                    message,
                    null,
                    message);
            state.put(currentKey, latest.attempt(), WorkflowNodeStatus.FAILED);
        } else if (latest != null && latest.status() == WorkflowNodeStatus.PENDING) {
            repository.closePendingNode(
                    tenantId,
                    claim.runId(),
                    currentKey,
                    WorkflowNodeStatus.FAILED,
                    WorkflowErrorCode.WF_STEP_LIMIT,
                    message,
                    clock.instant());
            state.put(currentKey, latest.attempt(), WorkflowNodeStatus.FAILED);
        } else if (latest == null) {
            WorkflowGraphNode node = graph.requireNode(currentKey);
            repository.insertNodeAttempt(
                    tenantId,
                    new WorkflowNodeAttemptInsert(
                            UuidV7.generate(),
                            claim.runId(),
                            currentKey,
                            node.type(),
                            node.name(),
                            1,
                            WorkflowNodeStatus.FAILED,
                            null,
                            message,
                            WorkflowErrorCode.WF_STEP_LIMIT.name(),
                            message,
                            clock.instant()));
            state.put(currentKey, 1, WorkflowNodeStatus.FAILED);
        }
        skipDownstream(tenantId, claim, graph, state, currentKey, SKIPPED_SUMMARY);
        failRun(
                tenantId,
                claim,
                currentKey,
                WorkflowErrorCode.WF_STEP_LIMIT,
                message,
                WorkflowResultStatus.NONE,
                0L,
                segmentStart);
    }

    /**
     * 写节点失败/取消终态。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param attempt 尝试号
     * @param status 终态
     * @param errorCode 错误码
     * @param message 原因
     * @param inputSummary 输入摘要
     * @param outputSummary 输出摘要
     */
    private void finishNodeFailure(
            TenantId tenantId,
            UUID runId,
            String nodeKey,
            int attempt,
            WorkflowNodeStatus status,
            WorkflowErrorCode errorCode,
            String message,
            String inputSummary,
            String outputSummary) {
        int updated = repository.finishNodeAttempt(
                tenantId,
                new WorkflowNodeFinish(
                        runId,
                        nodeKey,
                        attempt,
                        status,
                        inputSummary,
                        outputSummary,
                        null,
                        null,
                        errorCode.name(),
                        message,
                        clock.instant()));
        if (updated == 0) {
            LOG.warn("workflow.engine.node_finish_lost runId={} nodeKey={} attempt={}", runId, nodeKey, attempt);
        }
    }

    /**
     * 写运行失败终态；租约丢失/已取消时只记录，不覆盖他人的结果。
     *
     * @param tenantId 租户
     * @param claim 领取元数据
     * @param nodeKey 当前节点
     * @param errorCode 错误码
     * @param message 原因
     * @param resultStatus 结果状态
     * @param resultSizeBytes 结果大小
     * @param segmentStart 本段开始时间
     */
    private void failRun(
            TenantId tenantId,
            ClaimedWorkflowRun claim,
            String nodeKey,
            WorkflowErrorCode errorCode,
            String message,
            WorkflowResultStatus resultStatus,
            long resultSizeBytes,
            Instant segmentStart) {
        Instant now = clock.instant();
        long delta = Math.max(0L, Duration.between(segmentStart, now).toMillis());
        int updated = repository.finishRunFailed(
                tenantId,
                claim.runId(),
                claim.leaseOwner(),
                nodeKey,
                errorCode,
                message,
                resultStatus,
                resultSizeBytes,
                delta,
                now);
        if (updated == 0) {
            LOG.warn(
                    "workflow.engine.run_fail_lost runId={} tenantId={} status={}",
                    claim.runId(),
                    tenantId.value(),
                    resultStatus);
        }
    }

    /**
     * 计算节点截止时间：节点类型超时与运行剩余预算的较小值。
     *
     * @param type 节点类型
     * @param runRemainingMs 运行剩余毫秒
     * @return 截止时间
     */
    private WorkflowDeadline deadlineFor(WorkflowNodeType type, long runRemainingMs) {
        long nodeBudget = switch (type) {
            case MODEL -> properties.modelTimeoutSeconds() * 1000L;
            case RETRIEVAL -> properties.retrievalTimeoutSeconds() * 1000L;
            default -> runRemainingMs;
        };
        long budget = Math.max(0L, Math.min(Math.max(0L, runRemainingMs), nodeBudget));
        return new WorkflowDeadline(clock.instant().plusMillis(budget), clock);
    }

    /**
     * 序列化最终结果。
     *
     * @param payload 结果内容
     * @return 结果 JSON
     */
    private String resultJson(WorkflowResultPayload payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", payload.format().name());
        result.put("text", payload.text());
        result.put("sizeBytes", payload.sizeBytes());
        result.put(
                "generatedAt",
                OffsetDateTime.ofInstant(clock.instant(), DISPLAY_ZONE).toString());
        return serialize(result);
    }

    /**
     * 序列化任意 JSON 值。
     *
     * @param value 值
     * @return JSON 文本
     */
    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_INTERNAL, "节点输出序列化失败", ex);
        }
    }

    /**
     * 解析节点输出 JSON。
     *
     * @param outputJson 输出 JSON
     * @return JSON 树
     */
    private JsonNode parseOutput(String outputJson) {
        try {
            return objectMapper.readTree(outputJson);
        } catch (JacksonException ex) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_INTERNAL, "节点输出无法解析", ex);
        }
    }

    /**
     * 计算文本 UTF-8 字节数。
     *
     * @param value 文本
     * @return 字节数
     */
    private static long bytes(String value) {
        return value == null ? 0L : value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 运行超时文案。
     *
     * @return 中文可读原因
     */
    private String runTimeoutMessage() {
        return "运行超过执行超时上限 " + properties.runTimeoutMinutes() + " 分钟";
    }

    /**
     * 租约到节点守护的适配器。
     *
     * @param lease 租约句柄
     */
    private record LeaseGuard(WorkflowLease lease) implements WorkflowNodeGuard {

        /** 校验租约。 */
        private LeaseGuard {
            Objects.requireNonNull(lease, "lease must not be null");
        }

        /** {@inheritDoc} */
        @Override
        public boolean lost() {
            return lease.lost();
        }

        /** {@inheritDoc} */
        @Override
        public boolean cancelled() {
            return lease.cancelled();
        }
    }

    /**
     * 节点输出序列化结果。
     *
     * @param outputJson 输出 JSON
     * @param outputBytes 输出字节数
     * @param citationsJson 引用 JSON
     * @param citationBytes 引用字节数
     * @param output 输出树
     */
    private record Prepared(
            String outputJson, long outputBytes, String citationsJson, long citationBytes, JsonNode output) {}

    /**
     * 单节点输出超限信号；由 {@link #execute} 统一转成运行失败。
     */
    private static final class NodeOutputLimitException extends WorkflowNodeFailureException {

        /** 输出字节数。 */
        private final long size;

        /**
         * 创建超限异常。
         *
         * @param message 原因
         * @param size 输出字节数
         */
        private NodeOutputLimitException(String message, long size) {
            super(WorkflowErrorCode.WF_RESULT_LIMIT, message + "（" + size + " 字节）");
            this.size = size;
        }

        /**
         * 返回输出字节数。
         *
         * @return 字节数
         */
        private long size() {
            return size;
        }
    }
}
