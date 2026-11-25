package com.riccon.worker.workflow.approval.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowApprovalDecision;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalConflictException;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalDecisionCommand;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalDecisionResult;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalForbiddenException;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalInvalidRequestException;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalNotFoundException;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalRow;
import com.riccon.worker.workflow.approval.domain.WorkflowRunApprovalState;
import com.riccon.worker.workflow.approval.repository.WorkflowApprovalRepository;
import com.riccon.worker.workflow.engine.domain.WorkflowEngineLimits;
import com.riccon.worker.workflow.engine.domain.WorkflowGraph;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphEdge;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphFormatException;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphJson;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 审批决定状态机（ADR-0024 §5.2/§5.4）。
 *
 * <p>决定在单个事务内完成：审批条件更新（PENDING→APPROVED/REJECTED，0 行即重复/并发冲突）、
 * 审批节点终态、运行恢复或失败、拒绝时下游 SKIPPED、审计写入。任一业务/审计写入失败都回滚，
 * 不产生“已决定但没有状态推进”或“无审计的变更”。
 *
 * <p>校验顺序：自批 → 账号 ACTIVE → assignee/角色范围 → 审批状态 → 运行状态。自批、范围外与
 * 停用账号写 {@code outcome=denied} 审计后抛 {@link WorkflowApprovalForbiddenException}；这些
 * 拒绝不改变审批/运行状态，因此允许审计提交（{@code noRollbackFor}），运行继续等待。
 *
 * <p>批准：审批节点写 {@code SUCCEEDED} 与 {@code {"decision":"APPROVED"}}（供下游条件节点读取
 * {@code APPROVAL.decision}），运行回到 {@code PENDING} 并从目标节点继续；拒绝：审批节点
 * {@code FAILED}（{@code WF_APPROVAL_REJECTED}），运行 {@code FAILED}，下游全部 {@code SKIPPED}，
 * 不写最终结果。
 *
 * <p>Worker 没有入站 HTTP；公开端点与 {@code workflow.approve} 权限由 API T09 负责，决定理由的
 * 审计口径（U-05）仍是临时默认：完整理由进入审计，长度 1–500。
 */
@Component
public class WorkflowApprovalDecisionService {

    /** 审批节点失败原因（拒绝）。 */
    public static final String REJECTED_MESSAGE = "审批已拒绝，运行终止";

    /** 下游跳过原因。 */
    public static final String REJECTED_SKIP_SUMMARY = "审批拒绝，后续节点未执行";

    /** 审批通过摘要。 */
    public static final String APPROVED_SUMMARY = "审批通过";

    /** 拒绝时的审批节点摘要。 */
    public static final String REJECTED_NODE_SUMMARY = "审批已拒绝，后续节点未执行";

    /** 审批仓储。 */
    private final WorkflowApprovalRepository repository;

    /** 审批人解析（范围复查）。 */
    private final WorkflowApprovalAssigneeResolver assigneeResolver;

    /** 审计。 */
    private final WorkflowApprovalAuditor auditor;

    /** JSON 序列化。 */
    private final ObjectMapper objectMapper;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建决定服务。
     *
     * @param repository 审批仓储
     * @param assigneeResolver 审批人解析
     * @param auditor 审计
     * @param objectMapper JSON 序列化
     * @param clock UTC 时钟
     */
    public WorkflowApprovalDecisionService(
            WorkflowApprovalRepository repository,
            WorkflowApprovalAssigneeResolver assigneeResolver,
            WorkflowApprovalAuditor auditor,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.assigneeResolver = Objects.requireNonNull(assigneeResolver, "assigneeResolver must not be null");
        this.auditor = Objects.requireNonNull(auditor, "auditor must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 执行批准/拒绝。
     *
     * @param command 决定输入
     * @return 决定结果
     * @throws WorkflowApprovalNotFoundException 审批不可见
     * @throws WorkflowApprovalForbiddenException 自批/范围外/账号停用
     * @throws WorkflowApprovalInvalidRequestException 拒绝理由缺失/超长
     * @throws WorkflowApprovalConflictException 重复或并发决定、运行状态冲突
     */
    @Transactional(
            noRollbackFor = {
                WorkflowApprovalNotFoundException.class,
                WorkflowApprovalForbiddenException.class,
                WorkflowApprovalInvalidRequestException.class,
                WorkflowApprovalConflictException.class
            })
    public WorkflowApprovalDecisionResult decide(WorkflowApprovalDecisionCommand command) {
        validateComment(command);
        TenantId tenantId = command.tenantId();
        WorkflowApprovalRow approval = repository
                .findApproval(tenantId, command.approvalId())
                .orElseThrow(() -> new WorkflowApprovalNotFoundException(
                        command.approvalId().toString()));
        requireApprover(tenantId, approval, command);
        if (approval.status() != WorkflowApprovalStatus.PENDING) {
            throw new WorkflowApprovalConflictException(WorkflowApprovalConflictException.Reason.ALREADY_DECIDED);
        }
        WorkflowRunApprovalState run = repository
                .findRunApprovalState(tenantId, approval.workflowRunId())
                .orElseThrow(() -> new WorkflowApprovalNotFoundException(
                        command.approvalId().toString()));
        if (run.status() != WorkflowRunStatus.WAITING_APPROVAL) {
            throw new WorkflowApprovalConflictException(WorkflowApprovalConflictException.Reason.RUN_STATE_CONFLICT);
        }
        WorkflowGraph graph = loadGraph(tenantId, approval.workflowRunId());
        Instant now = clock.instant();
        long waitMs = waitMillis(approval.createdAt(), now);
        return command.decision() == WorkflowApprovalDecision.APPROVE
                ? approve(command, approval, graph, waitMs, now)
                : reject(command, approval, graph, waitMs, now);
    }

    /**
     * 批准：条件更新审批、节点成功、运行恢复并写审计。
     *
     * @param command 决定
     * @param approval 审批行
     * @param graph 发布图
     * @param waitMs 等待毫秒
     * @param now 决定时间
     * @return 结果
     */
    private WorkflowApprovalDecisionResult approve(
            WorkflowApprovalDecisionCommand command,
            WorkflowApprovalRow approval,
            WorkflowGraph graph,
            long waitMs,
            Instant now) {
        if (repository.approve(command.tenantId(), approval.id(), command.approverUserId(), command.comment(), now)
                == 0) {
            throw new WorkflowApprovalConflictException(WorkflowApprovalConflictException.Reason.CONCURRENT_DECISION);
        }
        String targetNodeKey =
                approval.targetNodeKey() != null ? approval.targetNodeKey() : successorOf(graph, approval.nodeKey());
        if (repository.finishApprovalNode(
                        command.tenantId(),
                        approval.workflowRunId(),
                        approval.nodeKey(),
                        approval.attempt(),
                        WorkflowNodeStatus.SUCCEEDED.name(),
                        decisionOutput(WorkflowApprovalDecision.APPROVE),
                        APPROVED_SUMMARY,
                        null,
                        null,
                        now)
                == 0) {
            // 审批已从 PENDING 改成功，但节点不在 WAITING_APPROVAL：状态不一致，整体回滚。
            throw new IllegalStateException("approval node is not waiting for approval");
        }
        if (repository.resumeRunPending(command.tenantId(), approval.workflowRunId(), targetNodeKey, waitMs, now)
                == 0) {
            throw new IllegalStateException("workflow run is not waiting for approval");
        }
        auditor.decided(
                command.tenantId(),
                approval.workflowRunId(),
                approval.id(),
                approval.nodeKey(),
                WorkflowApprovalDecision.APPROVE,
                command.approverUserId(),
                approval.targetToolName(),
                command.comment(),
                command.traceId());
        return new WorkflowApprovalDecisionResult(
                approval.id(),
                approval.workflowRunId(),
                approval.nodeKey(),
                WorkflowApprovalDecision.APPROVE.name(),
                WorkflowApprovalStatus.APPROVED,
                WorkflowRunStatus.PENDING,
                targetNodeKey);
    }

    /**
     * 拒绝：条件更新审批、节点失败、运行失败、下游跳过并写审计。
     *
     * @param command 决定
     * @param approval 审批行
     * @param graph 发布图
     * @param waitMs 等待毫秒
     * @param now 决定时间
     * @return 结果
     */
    private WorkflowApprovalDecisionResult reject(
            WorkflowApprovalDecisionCommand command,
            WorkflowApprovalRow approval,
            WorkflowGraph graph,
            long waitMs,
            Instant now) {
        if (repository.reject(command.tenantId(), approval.id(), command.approverUserId(), command.comment(), now)
                == 0) {
            throw new WorkflowApprovalConflictException(WorkflowApprovalConflictException.Reason.CONCURRENT_DECISION);
        }
        if (repository.finishApprovalNode(
                        command.tenantId(),
                        approval.workflowRunId(),
                        approval.nodeKey(),
                        approval.attempt(),
                        WorkflowNodeStatus.FAILED.name(),
                        null,
                        REJECTED_NODE_SUMMARY,
                        WorkflowErrorCode.WF_APPROVAL_REJECTED.name(),
                        REJECTED_MESSAGE,
                        now)
                == 0) {
            throw new IllegalStateException("approval node is not waiting for approval");
        }
        skipDownstream(command.tenantId(), approval.workflowRunId(), graph, approval.nodeKey(), now);
        if (repository.failRunRejected(
                        command.tenantId(), approval.workflowRunId(), approval.nodeKey(), REJECTED_MESSAGE, waitMs, now)
                == 0) {
            throw new IllegalStateException("workflow run is not waiting for approval");
        }
        auditor.decided(
                command.tenantId(),
                approval.workflowRunId(),
                approval.id(),
                approval.nodeKey(),
                WorkflowApprovalDecision.REJECT,
                command.approverUserId(),
                approval.targetToolName(),
                command.comment(),
                command.traceId());
        return new WorkflowApprovalDecisionResult(
                approval.id(),
                approval.workflowRunId(),
                approval.nodeKey(),
                WorkflowApprovalDecision.REJECT.name(),
                WorkflowApprovalStatus.REJECTED,
                WorkflowRunStatus.FAILED,
                null);
    }

    /**
     * 校验理由：拒绝必填，任何决定不超过 500 字符。
     *
     * @param command 决定
     */
    private static void validateComment(WorkflowApprovalDecisionCommand command) {
        int length = command.commentLength();
        if (command.decision() == WorkflowApprovalDecision.REJECT && length == 0) {
            throw new WorkflowApprovalInvalidRequestException(
                    WorkflowApprovalInvalidRequestException.Reason.COMMENT_REQUIRED);
        }
        if (length > WorkflowApprovalDecisionCommand.MAX_COMMENT_LENGTH) {
            throw new WorkflowApprovalInvalidRequestException(
                    WorkflowApprovalInvalidRequestException.Reason.COMMENT_TOO_LONG);
        }
    }

    /**
     * 校验审批人：自批 403、账号停用 403、范围外 403；拒绝写 denied 审计。
     *
     * @param tenantId 租户
     * @param approval 审批行
     * @param command 决定
     */
    private void requireApprover(
            TenantId tenantId, WorkflowApprovalRow approval, WorkflowApprovalDecisionCommand command) {
        if (command.approverUserId().equals(approval.requestedBy())) {
            auditDenied(tenantId, approval, command);
            throw new WorkflowApprovalForbiddenException(WorkflowApprovalForbiddenException.Reason.SELF_APPROVAL);
        }
        if (!repository.isActiveUser(tenantId, command.approverUserId())) {
            auditDenied(tenantId, approval, command);
            throw new WorkflowApprovalForbiddenException(WorkflowApprovalForbiddenException.Reason.APPROVER_INACTIVE);
        }
        if (!assigneeResolver.inScope(tenantId, approval, command.approverUserId())) {
            auditDenied(tenantId, approval, command);
            throw new WorkflowApprovalForbiddenException(WorkflowApprovalForbiddenException.Reason.OUT_OF_SCOPE);
        }
    }

    /**
     * 写拒绝审计；失败直接抛出，不吞异常。
     *
     * @param tenantId 租户
     * @param approval 审批行
     * @param command 被拒绝的决定
     */
    private void auditDenied(TenantId tenantId, WorkflowApprovalRow approval, WorkflowApprovalDecisionCommand command) {
        auditor.denied(
                tenantId,
                approval.workflowRunId(),
                approval.id(),
                approval.nodeKey(),
                command.decision(),
                command.approverUserId(),
                approval.targetToolName(),
                command.traceId());
    }

    /**
     * 跳过审批节点之后的全部可达节点；已有尝试写 SKIPPED，没有尝试直接插入终态。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param graph 发布图
     * @param approvalNodeKey 审批节点
     * @param now 当前时间
     */
    private void skipDownstream(
            TenantId tenantId, UUID runId, WorkflowGraph graph, String approvalNodeKey, Instant now) {
        Set<String> keys = new LinkedHashSet<>(graph.downstreamKeys(approvalNodeKey));
        for (String key : keys) {
            WorkflowGraphNode node = graph.findNode(key).orElse(null);
            if (node == null) {
                continue;
            }
            if (repository.hasNodeAttempt(tenantId, runId, key)) {
                repository.skipPendingNode(tenantId, runId, key, REJECTED_SKIP_SUMMARY, now);
            } else {
                repository.insertSkippedNode(
                        tenantId, runId, key, node.type(), node.name(), REJECTED_SKIP_SUMMARY, now);
            }
        }
    }

    /**
     * 返回审批节点的直接后继。
     *
     * @param graph 发布图
     * @param nodeKey 审批节点
     * @return 后继 key
     */
    private static String successorOf(WorkflowGraph graph, String nodeKey) {
        List<WorkflowGraphEdge> outgoing = graph.outgoing(nodeKey);
        if (outgoing.size() != 1) {
            throw new IllegalStateException("workflow approval node has no unique successor");
        }
        return outgoing.getFirst().target();
    }

    /**
     * 读取运行绑定版本的发布图并做运行入口校验。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @return 图
     */
    private WorkflowGraph loadGraph(TenantId tenantId, UUID runId) {
        String graphJson = repository
                .findRunGraphJson(tenantId, runId)
                .orElseThrow(() -> new IllegalStateException("workflow run version graph is missing"));
        try {
            WorkflowGraph graph = WorkflowGraphJson.parse(graphJson, WorkflowEngineLimits.MAX_GRAPH_NODES);
            graph.validateForRun();
            return graph;
        } catch (WorkflowGraphFormatException exception) {
            throw new IllegalStateException("workflow run version graph is invalid", exception);
        }
    }

    /**
     * 序列化审批节点输出，供下游条件读取 {@code APPROVAL.decision}。
     *
     * <p>值与 {@link WorkflowApprovalStatus} 一致（{@code APPROVED}/{@code REJECTED}），便于条件
     * 右值直接使用审批状态词汇；拒绝时下游已全部 SKIPPED，实际只会评估批准路径。
     *
     * @param decision 决定
     * @return JSON 文本
     */
    private String decisionOutput(WorkflowApprovalDecision decision) {
        WorkflowApprovalStatus status = decision == WorkflowApprovalDecision.APPROVE
                ? WorkflowApprovalStatus.APPROVED
                : WorkflowApprovalStatus.REJECTED;
        try {
            return objectMapper.writeValueAsString(Map.of("decision", status.name()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("workflow approval output cannot be serialized", exception);
        }
    }

    /**
     * 计算等待毫秒（负值归零，防止测试时钟回拨）。
     *
     * @param createdAt 创建时间
     * @param now 决定时间
     * @return 等待毫秒
     */
    private static long waitMillis(Instant createdAt, Instant now) {
        long millis = Duration.between(createdAt, now).toMillis();
        return Math.max(0L, millis);
    }
}
