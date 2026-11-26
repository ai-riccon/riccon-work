package com.riccon.api.workflow.approval.service;

import com.riccon.api.audit.domain.AuditDetailsPolicy;
import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.workflow.approval.domain.WorkflowApprovalSnapshot;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.workflow.WorkflowApprovalDecision;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 审批决定审计事件。
 *
 * <p>动作严格使用 ADR-0024 第 8.1 节的 {@code workflow.approval.decided}，目标为运行；details 只允许
 * {@code workflowRunId/workflowApprovalId/nodeId/decision/approverUserId/targetToolName?/commentSha256?}。
 * 自批/范围外/停用写 {@code outcome=DENIED} 且不带理由；成功决定按 DEC-0050=B 只写理由的稳定哈希，
 * 不写 {@code decisionComment} 明文（理由原文保留在 {@code workflow_approval.decision_comment}）。
 * 不写工具参数、密文、提示词、凭据或文档正文。
 */
public final class WorkflowApprovalAuditEvents {

    /** 工具类禁止实例化。 */
    private WorkflowApprovalAuditEvents() {}

    /**
     * 构造审批决定审计。
     *
     * @param now 事件时间
     * @param tenantId 租户
     * @param approverUserId 审批人
     * @param approval 审批快照
     * @param decision 决定
     * @param comment 理由；仅用于计算 {@code commentSha256}，不写明文
     * @param outcome 结果
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 审计事件
     */
    public static AuditEvent decided(
            Instant now,
            String tenantId,
            UUID approverUserId,
            WorkflowApprovalSnapshot approval,
            WorkflowApprovalDecision decision,
            String comment,
            AuditOutcome outcome,
            String traceId,
            String requestId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowRunId", approval.workflowRunId().toString());
        details.put("workflowApprovalId", approval.id().toString());
        details.put("nodeId", approval.nodeId());
        details.put("decision", decision.name());
        details.put("approverUserId", approverUserId.toString());
        if (approval.targetToolName() != null) {
            details.put("targetToolName", approval.targetToolName());
        }
        String commentSha256 = WorkflowApprovalCommentHash.sha256(comment);
        if (commentSha256 != null) {
            // DEC-0050=B：审计只存哈希，理由原文只保留在审批记录中供详情读取。
            details.put("commentSha256", commentSha256);
        }
        // 写入端显式拒绝历史明文键；读取端仍可通过 validate 读取旧行。
        AuditDetailsPolicy.validateWrite(AuditActions.WORKFLOW_APPROVAL_DECIDED, details);
        return AuditEvent.create(
                now,
                tenantId,
                AuditActorType.USER,
                approverUserId.toString(),
                AuditActions.WORKFLOW_APPROVAL_DECIDED,
                AuditTargetType.WORKFLOW_RUN,
                approval.workflowRunId().toString(),
                outcome,
                traceId,
                requestId,
                null,
                details);
    }
}
