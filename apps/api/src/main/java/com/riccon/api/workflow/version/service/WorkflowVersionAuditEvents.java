package com.riccon.api.workflow.version.service;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.workflow.WorkflowDefinitionStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 停用/启用/所有权移交审计事件构造。
 *
 * <p>只写 ADR-0024 §8.1 白名单：停用/启用写流程 ID 与前后状态，移交写流程 ID 与 from/to 账号；
 * 不写草稿图、prompt、工具参数或审批理由。事件与状态/所有权更新同事务写入，失败即回滚
 * （ADR-0007/§8.2，AC-16）。
 */
public final class WorkflowVersionAuditEvents {

    /** 工具类禁止实例化。 */
    private WorkflowVersionAuditEvents() {}

    /**
     * 构造停用/启用审计。
     *
     * @param now 发生时间
     * @param tenantId 租户
     * @param actorId 操作者
     * @param workflowId 流程 ID
     * @param previousStatus 变更前状态
     * @param status 变更后状态
     * @param action 动作（{@code workflow.disabled}/{@code workflow.enabled}）
     * @param traceId 追踪 ID
     * @param requestId 请求 ID；可空
     * @return 审计事件
     */
    public static AuditEvent statusChanged(
            Instant now,
            String tenantId,
            UUID actorId,
            UUID workflowId,
            WorkflowDefinitionStatus previousStatus,
            WorkflowDefinitionStatus status,
            String action,
            String traceId,
            String requestId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowId", workflowId.toString());
        details.put("previousStatus", previousStatus.name());
        details.put("status", status.name());
        return event(now, tenantId, actorId, action, workflowId, traceId, requestId, details);
    }

    /**
     * 构造所有权移交审计。
     *
     * @param now 发生时间
     * @param tenantId 租户
     * @param actorId 操作者（租户管理员）
     * @param workflowId 流程 ID
     * @param fromUserId 原所有者
     * @param toUserId 新所有者
     * @param traceId 追踪 ID
     * @param requestId 请求 ID；可空
     * @return 审计事件
     */
    public static AuditEvent ownerTransferred(
            Instant now,
            String tenantId,
            UUID actorId,
            UUID workflowId,
            UUID fromUserId,
            UUID toUserId,
            String traceId,
            String requestId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowId", workflowId.toString());
        details.put("fromUserId", fromUserId.toString());
        details.put("toUserId", toUserId.toString());
        return event(
                now,
                tenantId,
                actorId,
                AuditActions.WORKFLOW_OWNER_TRANSFERRED,
                workflowId,
                traceId,
                requestId,
                details);
    }

    /**
     * 构造流程目标审计事件。
     *
     * @param now 发生时间
     * @param tenantId 租户
     * @param actorId 操作者
     * @param action 动作
     * @param workflowId 流程 ID
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @param details 白名单字段
     * @return 审计事件
     */
    private static AuditEvent event(
            Instant now,
            String tenantId,
            UUID actorId,
            String action,
            UUID workflowId,
            String traceId,
            String requestId,
            Map<String, Object> details) {
        return AuditEvent.create(
                now,
                tenantId,
                AuditActorType.USER,
                actorId.toString(),
                action,
                AuditTargetType.WORKFLOW,
                workflowId.toString(),
                AuditOutcome.SUCCESS,
                traceId,
                requestId,
                null,
                details);
    }
}
