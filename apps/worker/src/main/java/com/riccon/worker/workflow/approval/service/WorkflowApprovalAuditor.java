package com.riccon.worker.workflow.approval.service;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalDecision;
import com.riccon.worker.audit.domain.AuditDetailsPolicy;
import com.riccon.worker.audit.domain.AuditEvent;
import com.riccon.worker.audit.repository.AuditRepository;
import com.riccon.worker.common.UuidV7;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalAuditPolicy;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 审批审计写入（ADR-0024 §8.1/§8.2）。
 *
 * <p>{@code workflow.approval.requested} 由 Worker 在审批创建时写入，使用 T01 维护的共享
 * {@link AuditDetailsPolicy} 白名单；{@code workflow.approval.decided} 的公开写入方是 api（T09），
 * Worker 侧决定状态机为了证明“决定与审计同事务、审计失败即回滚”也写同名动作，details 由
 * {@link WorkflowApprovalAuditPolicy} 按 ADR 字段清单校验。
 *
 * <p><b>fail-closed</b>：本类不捕获仓储异常；调用方在事务内调用时，审计失败会让整个审批停车/
 * 决定回滚，不产生无审计的业务变更。details 只含 ID、枚举与理由哈希，不含工具参数、提示词、
 * 凭据、文档正文或审批理由明文（DEC-0050=B）。
 */
@Component
public class WorkflowApprovalAuditor {

    /** Worker 服务操作者 ID，与既有审计组件一致。 */
    private static final String SERVICE_ACTOR = "riccon-worker";

    /** 审计仓储。 */
    private final AuditRepository auditRepository;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建审计组件。
     *
     * @param auditRepository 审计仓储
     * @param clock UTC 时钟
     */
    public WorkflowApprovalAuditor(AuditRepository auditRepository, Clock clock) {
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 写审批请求审计。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param approvalId 审批 ID
     * @param nodeKey 审批节点 key
     * @param assigneeCount 可用审批人数（可为 0）
     * @param actionKind 解锁动作
     * @param targetToolName 目标工具名；可空
     * @param traceId 追踪 ID
     */
    public void requested(
            TenantId tenantId,
            UUID runId,
            UUID approvalId,
            String nodeKey,
            int assigneeCount,
            WorkflowApprovalActionKind actionKind,
            String targetToolName,
            String traceId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowRunId", runId.toString());
        details.put("workflowApprovalId", approvalId.toString());
        details.put("nodeId", nodeKey);
        details.put("assigneeCount", assigneeCount);
        details.put("actionKind", actionKind.name());
        if (targetToolName != null) {
            details.put("targetToolName", targetToolName);
        }
        AuditDetailsPolicy.validate(AuditActions.WORKFLOW_APPROVAL_REQUESTED, details);
        append(tenantId, AuditActions.WORKFLOW_APPROVAL_REQUESTED, AuditOutcome.SUCCESS, runId, traceId, details);
    }

    /**
     * 写审批决定审计（成功）。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param approvalId 审批 ID
     * @param nodeKey 审批节点 key
     * @param decision 决定
     * @param approverUserId 审批人
     * @param targetToolName 目标工具名；可空
     * @param comment 理由；可空
     * @param traceId 追踪 ID
     */
    public void decided(
            TenantId tenantId,
            UUID runId,
            UUID approvalId,
            String nodeKey,
            WorkflowApprovalDecision decision,
            UUID approverUserId,
            String targetToolName,
            String comment,
            String traceId) {
        Map<String, Object> details =
                decisionDetails(runId, approvalId, nodeKey, decision, approverUserId, targetToolName, comment);
        // 共享动作级白名单与本地字段清单双重校验；两者都 fail-closed。
        AuditDetailsPolicy.validate(AuditActions.WORKFLOW_APPROVAL_DECIDED, details);
        WorkflowApprovalAuditPolicy.validateDecided(details);
        append(tenantId, AuditActions.WORKFLOW_APPROVAL_DECIDED, AuditOutcome.SUCCESS, runId, traceId, details);
    }

    /**
     * 写被拒绝的审批尝试审计（自批/范围外/账号停用）。
     *
     * <p>理由不写入：被拒绝的尝试没有形成业务决定，写入用户输入会扩大审计内容面。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param approvalId 审批 ID
     * @param nodeKey 审批节点 key
     * @param decision 尝试的决定
     * @param approverUserId 审批人
     * @param targetToolName 目标工具名；可空
     * @param traceId 追踪 ID
     */
    public void denied(
            TenantId tenantId,
            UUID runId,
            UUID approvalId,
            String nodeKey,
            WorkflowApprovalDecision decision,
            UUID approverUserId,
            String targetToolName,
            String traceId) {
        Map<String, Object> details =
                decisionDetails(runId, approvalId, nodeKey, decision, approverUserId, targetToolName, null);
        AuditDetailsPolicy.validate(AuditActions.WORKFLOW_APPROVAL_DECIDED, details);
        WorkflowApprovalAuditPolicy.validateDecided(details);
        append(tenantId, AuditActions.WORKFLOW_APPROVAL_DECIDED, AuditOutcome.DENIED, runId, traceId, details);
    }

    /**
     * 构造决定审计字段。
     *
     * @param runId 运行 ID
     * @param approvalId 审批 ID
     * @param nodeKey 审批节点 key
     * @param decision 决定
     * @param approverUserId 审批人
     * @param targetToolName 工具名；可空
     * @param comment 理由；仅用于计算 {@code commentSha256}，不写明文
     * @return details
     */
    private static Map<String, Object> decisionDetails(
            UUID runId,
            UUID approvalId,
            String nodeKey,
            WorkflowApprovalDecision decision,
            UUID approverUserId,
            String targetToolName,
            String comment) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowRunId", runId.toString());
        details.put("workflowApprovalId", approvalId.toString());
        details.put("nodeId", nodeKey);
        details.put("decision", decision.name());
        details.put("approverUserId", approverUserId.toString());
        if (targetToolName != null) {
            details.put("targetToolName", targetToolName);
        }
        String commentSha256 = WorkflowApprovalCommentHash.sha256(comment);
        if (commentSha256 != null) {
            // DEC-0050=B：审计只存哈希，理由原文只保留在审批记录中供详情读取。
            details.put("commentSha256", commentSha256);
        }
        return details;
    }

    /**
     * 追加事件并强制租户上下文一致。
     *
     * @param tenantId 租户
     * @param action 动作
     * @param outcome 结果
     * @param runId 运行 ID
     * @param traceId 追踪 ID
     * @param details 白名单字段
     */
    private void append(
            TenantId tenantId,
            String action,
            AuditOutcome outcome,
            UUID runId,
            String traceId,
            Map<String, Object> details) {
        AuditEvent event = new AuditEvent(
                UuidV7.generate(),
                clock.instant(),
                tenantId.value(),
                AuditActorType.SERVICE,
                SERVICE_ACTOR,
                action,
                AuditTargetType.WORKFLOW_RUN,
                runId.toString(),
                outcome,
                traceId,
                null,
                details,
                AuditEvent.CURRENT_SCHEMA_VERSION);
        // 审计与业务同事务：失败直接抛出，由调用方事务回滚（ADR-0007/§8.2）。
        TenantContext.runWithTenant(tenantId, () -> auditRepository.append(event));
    }
}
