package com.riccon.worker.workflow.approval.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import com.riccon.worker.common.UuidV7;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalInsert;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalParkedException;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalParkingSnapshot;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalPlanInput;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalSealedParams;
import com.riccon.worker.workflow.approval.repository.WorkflowApprovalRepository;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphEdge;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphNode;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphNodeConfig;
import com.riccon.worker.workflow.node.domain.WorkflowNodeAbortedException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecution;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecutor;
import com.riccon.worker.workflow.node.domain.WorkflowNodeFailureException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeOutput;
import com.riccon.worker.workflow.node.domain.WorkflowNodeResult;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * APPROVAL 节点执行器（ADR-0024 §4.4/§5.2/§5.4）。
 *
 * <p>执行顺序：
 * <ol>
 *   <li>校验审批配置与解锁目标（MCP_TOOL 必须指向直接后继工具节点）；
 *   <li>按“本租户 → ACTIVE → 角色/用户范围 → 排除发起人”解析审批人；结果为空也继续创建审批；
 *   <li>工具审批渲染参数、生成脱敏预览、AES-256-GCM 加密并计算 {@code params_sha256}；
 *   <li>计算 {@code plan_sha256}（版本 + 节点配置 + 上游输出摘要）；
 *   <li>同一事务插入审批、写 assignee、把节点与运行推进为 {@code WAITING_APPROVAL} 并释放租约；
 *   <li>写 {@code workflow.approval.requested} 审计（失败即回滚）；
 *   <li>抛 {@link WorkflowApprovalParkedException} 通知引擎停止本段推进，由审批决定恢复运行。
 * </ol>
 *
 * <p>无可用审批人时运行保持 {@code WAITING_APPROVAL} 并写 {@code WF_NO_APPROVER} + 固定中文提示，
 * 不自动放行、不跳过节点（AC-06/SEC-WF-001）。{@code noRollbackFor} 只放行“已停车”信号；其他
 * 异常（含审计失败、状态竞争）都会回滚，保证不会留下半停车状态。
 */
@Component
public class WorkflowApprovalExecutor implements WorkflowNodeExecutor {

    /** 无可用审批人的固定中文提示（ADR-0024 §5.2）。 */
    public static final String NO_APPROVER_MESSAGE = "需先配置非发起人审批人";

    /** 审批请求审计的 trace 前缀。 */
    private static final String TRACE_PREFIX = "workflow-run-";

    /** 审批仓储。 */
    private final WorkflowApprovalRepository repository;

    /** 审批人解析。 */
    private final WorkflowApprovalAssigneeResolver assigneeResolver;

    /** 参数渲染/加密。 */
    private final WorkflowApprovalParamsService paramsService;

    /** 计划哈希。 */
    private final WorkflowApprovalPlanHasher planHasher;

    /** 审计。 */
    private final WorkflowApprovalAuditor auditor;

    /** JSON 序列化。 */
    private final ObjectMapper objectMapper;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建审批执行器。
     *
     * @param repository 审批仓储
     * @param assigneeResolver 审批人解析
     * @param paramsService 参数渲染/加密
     * @param planHasher 计划哈希
     * @param auditor 审计
     * @param objectMapper JSON 序列化
     * @param clock UTC 时钟
     */
    public WorkflowApprovalExecutor(
            WorkflowApprovalRepository repository,
            WorkflowApprovalAssigneeResolver assigneeResolver,
            WorkflowApprovalParamsService paramsService,
            WorkflowApprovalPlanHasher planHasher,
            WorkflowApprovalAuditor auditor,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.assigneeResolver = Objects.requireNonNull(assigneeResolver, "assigneeResolver must not be null");
        this.paramsService = Objects.requireNonNull(paramsService, "paramsService must not be null");
        this.planHasher = Objects.requireNonNull(planHasher, "planHasher must not be null");
        this.auditor = Objects.requireNonNull(auditor, "auditor must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public WorkflowNodeType nodeType() {
        return WorkflowNodeType.APPROVAL;
    }

    /**
     * 创建审批并让运行进入等待态。
     *
     * @param execution 节点执行上下文
     * @return 本方法不返回；审批等待通过异常信号停止引擎推进
     * @throws WorkflowNodeFailureException 配置/工具/参数/加密/审计失败
     */
    @Override
    @Transactional(noRollbackFor = WorkflowApprovalParkedException.class)
    public WorkflowNodeResult execute(WorkflowNodeExecution execution) {
        execution.guard().requireActive();
        TenantId tenantId = execution.tenantId();
        UUID runId = execution.runId();
        WorkflowGraphNode node = execution.node();
        WorkflowGraphNodeConfig config = node.config();

        WorkflowApprovalParkingSnapshot snapshot = repository
                .findParkingSnapshot(tenantId, runId)
                .orElseThrow(() -> new WorkflowNodeFailureException(WorkflowErrorCode.WF_INTERNAL, "审批运行时找不到工作流运行"));
        if (snapshot.status() != WorkflowRunStatus.RUNNING
                || snapshot.leaseOwner() == null
                || !node.id().equals(snapshot.currentNodeKey())) {
            // 运行已被取消/重领或当前节点已推进：不写任何状态，交给新租约持有者或取消路径。
            throw new WorkflowNodeAbortedException(true);
        }

        WorkflowApprovalActionKind actionKind = requireActionKind(config);
        String targetNodeKey = resolveTargetNodeKey(execution, actionKind);
        // 工具参数与工具 ID 来自被解锁的工具节点快照；审批节点只持有 assignee/actionKind/target。
        WorkflowGraphNode targetNode =
                execution.graph().findNode(targetNodeKey).orElseThrow(() -> invalid("审批目标节点不在发布图中"));
        UUID targetToolId =
                actionKind == WorkflowApprovalActionKind.MCP_TOOL ? requireToolId(targetNode.config()) : null;
        WorkflowApprovalAssigneeResolver.Resolution assignees =
                assigneeResolver.resolve(tenantId, config, snapshot.requesterUserId());
        String toolName = targetToolId == null
                ? null
                : repository
                        .findActiveToolName(targetToolId)
                        .orElseThrow(() -> new WorkflowNodeFailureException(
                                WorkflowErrorCode.WF_TOOL_NOT_AUTHORIZED, "审批目标工具未授权或已停用"));

        int attempt = repository
                .findRunningAttempt(tenantId, runId, node.id())
                .orElseThrow(() -> new WorkflowNodeFailureException(WorkflowErrorCode.WF_INTERNAL, "审批节点尝试不存在"));
        UUID approvalId = UuidV7.generate();
        Instant now = clock.instant();
        WorkflowApprovalSealedParams sealed = actionKind == WorkflowApprovalActionKind.MCP_TOOL
                ? paramsService.sealToolParams(
                        tenantId,
                        approvalId,
                        targetNode.config().argsTemplate(),
                        execution.inputs(),
                        execution.upstreamOutputs())
                : null;
        String planSha256 = planHasher.hash(new WorkflowApprovalPlanInput(
                snapshot.workflowId(),
                snapshot.workflowVersionId(),
                node.id(),
                actionKind,
                assignees.type(),
                assignees.roleCode(),
                configuredUserIds(config, assignees),
                targetNodeKey,
                targetToolId,
                toolName,
                upstreamSummaries(execution)));

        repository.insertApproval(
                tenantId,
                new WorkflowApprovalInsert(
                        approvalId,
                        runId,
                        snapshot.workflowId(),
                        node.id(),
                        attempt,
                        assignees.type(),
                        assignees.roleCode(),
                        actionKind,
                        targetNodeKey,
                        targetToolId,
                        toolName,
                        previewJson(sealed),
                        sealed == null ? null : sealed.ciphertext(),
                        sealed == null ? null : sealed.keyId(),
                        sealed == null ? null : sealed.paramsSha256(),
                        planSha256,
                        snapshot.requesterUserId(),
                        now));
        if (assignees.type() == WorkflowApprovalAssigneeType.USER && !assignees.empty()) {
            repository.insertAssignees(tenantId, approvalId, assignees.userIds(), now);
        }
        if (repository.markNodeWaiting(tenantId, runId, node.id(), attempt, approvalId, now) == 0) {
            // 节点已不在 RUNNING：状态竞争，回滚审批插入，由引擎按租约/取消语义处理。
            throw new IllegalStateException("approval node is no longer running");
        }
        String errorCode = assignees.empty() ? WorkflowErrorCode.WF_NO_APPROVER.name() : null;
        String errorMessage = assignees.empty() ? NO_APPROVER_MESSAGE : null;
        if (repository.markRunWaiting(tenantId, runId, node.id(), snapshot.leaseOwner(), errorCode, errorMessage, now)
                == 0) {
            // 租约/状态已变化：回滚本次审批与节点等待，避免与新持有者双写。
            throw new WorkflowNodeAbortedException(true);
        }
        auditor.requested(
                tenantId, runId, approvalId, node.id(), assignees.count(), actionKind, toolName, TRACE_PREFIX + runId);
        // 已提交等待态：通知引擎停止推进，不写节点终态（决定路径负责恢复）。
        throw new WorkflowApprovalParkedException();
    }

    /**
     * 读取并校验解锁动作。
     *
     * @param config 节点配置
     * @return 解锁动作
     */
    private static WorkflowApprovalActionKind requireActionKind(WorkflowGraphNodeConfig config) {
        WorkflowApprovalActionKind actionKind = config.actionKind();
        if (actionKind == null) {
            throw invalid("审批节点缺少 actionKind");
        }
        return actionKind;
    }

    /**
     * 解析并校验解锁目标为直接后继。
     *
     * @param execution 执行上下文
     * @param actionKind 解锁动作
     * @return 恢复节点 key
     */
    private static String resolveTargetNodeKey(WorkflowNodeExecution execution, WorkflowApprovalActionKind actionKind) {
        WorkflowGraphNode node = execution.node();
        List<WorkflowGraphEdge> outgoing = execution.graph().outgoing(node.id());
        if (outgoing.size() != 1) {
            throw invalid("审批节点必须恰好一条出边");
        }
        String successor = outgoing.getFirst().target();
        WorkflowGraphNode successorNode =
                execution.graph().findNode(successor).orElseThrow(() -> invalid("审批节点后继不在发布图中"));
        String configured = node.config().targetNodeKey();
        if (configured != null && !configured.equals(successor)) {
            throw invalid("targetNodeKey 必须指向审批节点的直接后继");
        }
        switch (actionKind) {
            case MCP_TOOL -> {
                if (!successor.equals(configured)) {
                    throw invalid("MCP 工具审批必须调用 targetNodeKey 指向直接后继工具节点");
                }
                if (successorNode.type() != WorkflowNodeType.MCP_TOOL) {
                    throw invalid("MCP 工具审批的后继必须是工具节点");
                }
            }
            case MODEL -> {
                if (successorNode.type() != WorkflowNodeType.MODEL) {
                    throw invalid("模型审批的后继必须是模型节点");
                }
            }
            case CONTINUE -> {
                // 继续动作不限制后继类型；只要求配置目标（如填写）为直接后继。
            }
        }
        return successor;
    }

    /**
     * 读取工具审批的目标工具 ID。
     *
     * @param targetConfig 目标工具节点配置
     * @return 工具 ID
     */
    private static UUID requireToolId(WorkflowGraphNodeConfig targetConfig) {
        if (targetConfig.toolId() == null) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_TOOL_NOT_AUTHORIZED, "审批目标工具未配置");
        }
        return targetConfig.toolId();
    }

    /**
     * 参与计划哈希的用户审批人：使用节点配置（排序去重），不使用运行时解析结果，避免账号状态变化
     * 导致无关漂移；角色审批不参与用户列表。
     *
     * @param config 节点配置
     * @param assignees 解析结果
     * @return 用户 ID 列表
     */
    private static List<UUID> configuredUserIds(
            WorkflowGraphNodeConfig config, WorkflowApprovalAssigneeResolver.Resolution assignees) {
        if (assignees.type() == WorkflowApprovalAssigneeType.ROLE || config.assigneeUserIds() == null) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(config.assigneeUserIds()));
    }

    /**
     * 上游成功节点摘要（计划哈希绑定）。
     *
     * @param execution 执行上下文
     * @return 节点 key 到摘要
     */
    private static Map<String, String> upstreamSummaries(WorkflowNodeExecution execution) {
        Map<String, String> summaries = new TreeMap<>();
        for (Map.Entry<String, WorkflowNodeOutput> entry :
                execution.upstreamOutputs().entrySet()) {
            summaries.put(entry.getKey(), entry.getValue().summary());
        }
        return summaries;
    }

    /**
     * 序列化脱敏预览；非工具审批写空对象。
     *
     * @param sealed 参数加密结果；可空
     * @return JSON 文本
     */
    private String previewJson(WorkflowApprovalSealedParams sealed) {
        if (sealed == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(sealed.preview());
        } catch (JacksonException exception) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_INTERNAL, "审批参数预览无法序列化", exception);
        }
    }

    /**
     * 构造配置非法失败。
     *
     * @param message 脱敏原因
     * @return 节点失败
     */
    private static WorkflowNodeFailureException invalid(String message) {
        return new WorkflowNodeFailureException(WorkflowErrorCode.WF_INVALID_GRAPH, message);
    }
}
