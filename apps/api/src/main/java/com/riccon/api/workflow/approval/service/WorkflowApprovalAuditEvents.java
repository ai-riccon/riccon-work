package com.riccon.api.workflow.approval.service;

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
 * {@code workflowRunId/workflowApprovalId/nodeId/decision/approverUserId/targetToolName?/decisionComment?}。
 * 自批/范围外/停用写 {@code outcome=DENIED} 且不带理由；成功决定带理由（≤500，U-05 未决前按 ADR §8.3
 * 的临时默认完整写入）。不写工具参数、密文、提示词、凭据或文档正文。
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
     * @param comment 理由；拒绝的拒绝路径为空
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
        if (comment != null) {
            details.put("decisionComment", comment);
        }
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
