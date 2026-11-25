package com.riccon.worker.workflow.tool.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalPlanInput;
import com.riccon.worker.workflow.approval.service.WorkflowApprovalParamsService;
import com.riccon.worker.workflow.approval.service.WorkflowApprovalPlanHasher;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphNode;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecution;
import com.riccon.worker.workflow.node.domain.WorkflowNodeFailureException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeOutput;
import com.riccon.worker.workflow.tool.domain.WorkflowToolApprovalSnapshot;
import com.riccon.worker.workflow.tool.domain.WorkflowToolRunBinding;
import com.riccon.worker.workflow.tool.repository.WorkflowToolRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 工具调用前的审批/plan/参数校验（ADR-0024 §4.7，T07b）。
 *
 * <p>执行顺序（任一步失败都不发起外部调用）：
 *
 * <ol>
 *   <li>工具节点必须配置 {@code toolId}，且运行存在最新 {@code MCP_TOOL} 审批；
 *   <li>审批状态必须为 {@link WorkflowApprovalStatus#APPROVED}，审批人不能是发起人；
 *   <li>审批目标节点/目标工具必须与当前节点快照一致；
 *   <li>按 {@link WorkflowApprovalPlanHasher} 用运行绑定版本、审批节点配置与上游摘要重算
 *       {@code plan_sha256}，与审批行不一致即 {@code WF_PLAN_DRIFT}；
 *   <li>写工具重试必须重新审批：旧审批不得早于工具节点历史尝试（{@code created_at <= previous}）；
 *   <li>参数只能通过密文 + key id 解密并复核 {@code params_sha256}，禁止回退到脱敏预览。
 * </ol>
 *
 * <p><b>上游摘要口径</b>：T07a 创建审批时，审批节点的最新尝试是当前 RUNNING 尝试，引擎不会为它
 * 记录成功输出，因此计划哈希只包含之前已成功节点。T07b 执行工具节点时，APPROVAL 已成功并写入了
 * {@code output_summary}；重算必须排除当前审批节点自身，否则同一计划会被误判漂移。该口径对首次
 * 审批与重审批都成立（重审批时旧成功输出也不会被引擎记录，因为最新尝试尚未成功）。
 */
@Component
public class WorkflowToolApprovalVerifier {

    /** 工具调用仓储。 */
    private final WorkflowToolRepository repository;

    /** 参数解密。 */
    private final WorkflowApprovalParamsService paramsService;

    /** 计划哈希。 */
    private final WorkflowApprovalPlanHasher planHasher;

    /**
     * 创建校验器。
     *
     * @param repository 工具调用仓储
     * @param paramsService 参数解密
     * @param planHasher 计划哈希
     */
    public WorkflowToolApprovalVerifier(
            WorkflowToolRepository repository,
            WorkflowApprovalParamsService paramsService,
            WorkflowApprovalPlanHasher planHasher) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.paramsService = Objects.requireNonNull(paramsService, "paramsService must not be null");
        this.planHasher = Objects.requireNonNull(planHasher, "planHasher must not be null");
    }

    /**
     * 校验审批绑定并解密参数。
     *
     * @param execution 节点执行上下文
     * @return 已验证审批、工具与参数
     * @throws WorkflowNodeFailureException 缺审批、状态不符、漂移、重试未重审批或参数不可重放
     */
    public VerifiedToolApproval verify(WorkflowNodeExecution execution) {
        TenantId tenantId = execution.tenantId();
        UUID runId = execution.runId();
        WorkflowGraphNode node = execution.node();
        UUID configuredToolId = node.config().toolId();
        if (configuredToolId == null) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_TOOL_NOT_AUTHORIZED, "工具节点缺少工具配置");
        }
        WorkflowToolApprovalSnapshot approval = repository
                .findLatestToolApproval(tenantId, runId, node.id())
                .orElseThrow(
                        () -> new WorkflowNodeFailureException(WorkflowErrorCode.WF_APPROVAL_REQUIRED, "工具调用缺少前置审批"));
        requireApproved(approval);
        requireToolBinding(approval, node.id(), configuredToolId);

        WorkflowToolRunBinding binding = repository
                .findRunBinding(tenantId, runId)
                .orElseThrow(() -> new WorkflowNodeFailureException(WorkflowErrorCode.WF_INTERNAL, "工具运行时找不到工作流运行"));
        int attempt = repository
                .findAttempt(tenantId, execution.nodeRunId())
                .orElseThrow(() -> new WorkflowNodeFailureException(WorkflowErrorCode.WF_INTERNAL, "工具节点尝试不存在"));
        requirePlanMatches(execution, approval, binding, node.id());
        requireFreshApproval(execution, approval, attempt);

        Map<String, Object> arguments = paramsService.openToolParams(
                tenantId, approval.id(), approval.paramsKeyId(), approval.paramsCiphertext(), approval.paramsSha256());
        return new VerifiedToolApproval(
                approval, configuredToolId, approval.targetToolName(), approval.paramsSha256(), arguments);
    }

    /**
     * 校验审批状态与禁自批。
     *
     * @param approval 审批快照
     */
    private static void requireApproved(WorkflowToolApprovalSnapshot approval) {
        switch (approval.status()) {
            case APPROVED -> {
                // 继续校验审批人。
            }
            case PENDING -> throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_APPROVAL_REQUIRED, "工具审批尚未批准");
            case REJECTED -> throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_APPROVAL_REJECTED, "工具审批已拒绝");
            case CANCELLED -> throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_APPROVAL_REQUIRED, "工具审批已取消");
        }
        if (approval.decidedBy() == null) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_PLAN_DRIFT, "工具审批缺少审批人");
        }
        if (approval.decidedBy().equals(approval.requestedBy())) {
            // 禁自批是 T09 的请求校验；执行前复查，防止损坏数据或旁路写入放行。
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_PLAN_DRIFT, "工具审批不能由发起人自己决定");
        }
        if (approval.actionKind() != WorkflowApprovalActionKind.MCP_TOOL) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_PLAN_DRIFT, "审批动作类型与工具节点不匹配");
        }
    }

    /**
     * 校验审批目标与当前工具节点一致。
     *
     * @param approval 审批快照
     * @param nodeKey 当前工具节点 key
     * @param configuredToolId 节点配置的工具 ID
     */
    private static void requireToolBinding(
            WorkflowToolApprovalSnapshot approval, String nodeKey, UUID configuredToolId) {
        if (!nodeKey.equals(approval.targetNodeKey())) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_PLAN_DRIFT, "审批目标节点与工具节点不一致");
        }
        if (!configuredToolId.equals(approval.targetToolId())) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_PLAN_DRIFT, "审批目标工具与工具节点不一致");
        }
        if (approval.targetToolName() == null || approval.targetToolName().isBlank()) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_PLAN_DRIFT, "审批缺少工具名快照");
        }
    }

    /**
     * 重算计划哈希并比对审批行。
     *
     * @param execution 执行上下文
     * @param approval 审批快照
     * @param binding 运行绑定版本
     * @param nodeKey 当前工具节点 key
     */
    private void requirePlanMatches(
            WorkflowNodeExecution execution,
            WorkflowToolApprovalSnapshot approval,
            WorkflowToolRunBinding binding,
            String nodeKey) {
        WorkflowGraphNode approvalNode = execution
                .graph()
                .findNode(approval.nodeKey())
                .orElseThrow(
                        () -> new WorkflowNodeFailureException(WorkflowErrorCode.WF_PLAN_DRIFT, "审批节点不在运行绑定的发布图中"));
        String recomputed = planHasher.hash(new WorkflowApprovalPlanInput(
                binding.workflowId(),
                binding.workflowVersionId(),
                approval.nodeKey(),
                approval.actionKind(),
                approval.assigneeType(),
                approval.assigneeRoleCode(),
                configuredUserIds(approval, approvalNode),
                nodeKey,
                approval.targetToolId(),
                approval.targetToolName(),
                upstreamSummaries(execution, approval.nodeKey())));
        if (!recomputed.equals(approval.planSha256())) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_PLAN_DRIFT, "审批计划与当前运行不一致，需重新审批");
        }
    }

    /**
     * 校验写工具重试使用的新审批。
     *
     * <p>工具节点 attempt &gt; 1 时，审批创建时间必须晚于该节点上一次尝试开始时间；否则说明是
     * 旧审批被复用，按 {@code WF_APPROVAL_REQUIRED} 失败关闭，提示重新走审批节点。该规则对
     * 未发起调用的失败同样适用，避免“失败后原地重试”绕过人工确认。
     *
     * @param execution 执行上下文
     * @param approval 审批快照
     * @param attempt 工具节点当前尝试号
     */
    private void requireFreshApproval(
            WorkflowNodeExecution execution, WorkflowToolApprovalSnapshot approval, int attempt) {
        TenantId tenantId = execution.tenantId();
        String nodeKey = execution.node().id();
        if (attempt <= 1) {
            return;
        }
        Optional<Instant> previousStart =
                repository.findPreviousAttemptStartedAt(tenantId, execution.runId(), nodeKey, attempt);
        if (previousStart.isEmpty()) {
            // attempt > 1 但没有历史尝试属于状态损坏；不猜测可重试，要求重新审批。
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_APPROVAL_REQUIRED, "工具重试缺少上一尝试记录，需重新审批");
        }
        if (!approval.createdAt().isAfter(previousStart.get())) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_APPROVAL_REQUIRED, "工具重试必须重新审批");
        }
    }

    /**
     * 参与计划哈希的用户审批人：与 T07a 一致，角色审批为空，用户审批按配置去重（哈希器排序）。
     *
     * @param approval 审批快照
     * @param approvalNode 审批节点
     * @return 用户 ID 列表
     */
    private static List<UUID> configuredUserIds(WorkflowToolApprovalSnapshot approval, WorkflowGraphNode approvalNode) {
        if (approval.assigneeType() == WorkflowApprovalAssigneeType.ROLE
                || approvalNode.config().assigneeUserIds() == null) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(approvalNode.config().assigneeUserIds()));
    }

    /**
     * 上游成功节点摘要（排除当前审批节点自身）。
     *
     * <p>引擎重建状态时只为“最新尝试且 SUCCEEDED”的节点记录输出（{@code WorkflowExecutionState.load}）。
     * T07a 创建审批时，审批节点的最新尝试正是当前 RUNNING 尝试，因此其输出永远不在
     * {@code upstreamOutputs} 中。T07b 执行工具节点时审批节点已 SUCCEEDED，必须排除自身才能与
     * 创建时的集合一致；否则每次工具执行都会被误判 {@code WF_PLAN_DRIFT}。
     *
     * @param execution 执行上下文
     * @param approvalNodeKey 当前审批节点 key
     * @return 节点 key 到摘要
     */
    private static Map<String, String> upstreamSummaries(WorkflowNodeExecution execution, String approvalNodeKey) {
        Map<String, String> summaries = new TreeMap<>();
        for (Map.Entry<String, WorkflowNodeOutput> entry :
                execution.upstreamOutputs().entrySet()) {
            if (!entry.getKey().equals(approvalNodeKey)) {
                summaries.put(entry.getKey(), entry.getValue().summary());
            }
        }
        return summaries;
    }

    /**
     * 已验证的工具审批。
     *
     * @param approval 审批快照
     * @param toolId 节点配置的工具 ID（已与审批快照核对）
     * @param toolName 工具名快照
     * @param paramsSha256 参数摘要
     * @param arguments 解密后的参数
     */
    public record VerifiedToolApproval(
            WorkflowToolApprovalSnapshot approval,
            UUID toolId,
            String toolName,
            String paramsSha256,
            Map<String, Object> arguments) {

        /** 校验必填字段并复制参数。 */
        public VerifiedToolApproval {
            Objects.requireNonNull(approval, "approval must not be null");
            Objects.requireNonNull(toolId, "toolId must not be null");
            Objects.requireNonNull(toolName, "toolName must not be null");
            Objects.requireNonNull(paramsSha256, "paramsSha256 must not be null");
            arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments must not be null"));
        }
    }
}
