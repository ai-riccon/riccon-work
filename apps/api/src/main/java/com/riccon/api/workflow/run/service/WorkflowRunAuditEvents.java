package com.riccon.api.workflow.run.service;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.workflow.run.domain.WorkflowRunSnapshot;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 运行创建/取消/重试审计事件。
 *
 * <p>动作名严格使用 ADR-0024 §8.1 的实际动作：{@code workflow.run.requested}、
 * {@code workflow.run.cancelled}、{@code workflow.run.retried}（不存在 queued 动作）。details 只写
 * ID、版本号、节点 key 与尝试号，不写运行输入、提示词、工具参数或结果（ADR-0024 §8.1/§8.2）。
 */
public final class WorkflowRunAuditEvents {

    /** 工具类禁止实例化。 */
    private WorkflowRunAuditEvents() {}

    /**
     * 构造运行创建审计。
     *
     * @param now 事件时间
     * @param tenantId 租户
     * @param actorId 发起人
     * @param run 运行快照
     * @param nodeCount 版本节点数
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 审计事件
     */
    public static AuditEvent requested(
            Instant now,
            String tenantId,
            UUID actorId,
            WorkflowRunSnapshot run,
            int nodeCount,
            String traceId,
            String requestId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowId", run.workflowId().toString());
        details.put("workflowVersionId", run.workflowVersionId().toString());
        details.put("workflowRunId", run.id().toString());
        details.put("versionNo", run.versionNo());
        details.put("nodeCount", nodeCount);
        return AuditEvent.create(
                now,
                tenantId,
                AuditActorType.USER,
                actorId.toString(),
                AuditActions.WORKFLOW_RUN_REQUESTED,
                AuditTargetType.WORKFLOW_RUN,
                run.id().toString(),
                AuditOutcome.SUCCESS,
                traceId,
                requestId,
                null,
                details);
    }

    /**
     * 构造运行取消审计。
     *
     * @param now 事件时间
     * @param tenantId 租户
     * @param actorId 操作者
     * @param runId 运行 ID
     * @param previousStatus 取消前状态
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 审计事件
     */
    public static AuditEvent cancelled(
            Instant now,
            String tenantId,
            UUID actorId,
            UUID runId,
            WorkflowRunStatus previousStatus,
            String traceId,
            String requestId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowRunId", runId.toString());
        details.put("previousStatus", previousStatus.name());
        return AuditEvent.create(
                now,
                tenantId,
                AuditActorType.USER,
                actorId.toString(),
                AuditActions.WORKFLOW_RUN_CANCELLED,
                AuditTargetType.WORKFLOW_RUN,
                runId.toString(),
                AuditOutcome.SUCCESS,
                traceId,
                requestId,
                null,
                details);
    }

    /**
     * 构造节点重试审计。
     *
     * @param now 事件时间
     * @param tenantId 租户
     * @param actorId 操作者
     * @param runId 运行 ID
     * @param nodeId 失败节点 key
     * @param attempt 新尝试号
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 审计事件
     */
    public static AuditEvent retried(
            Instant now,
            String tenantId,
            UUID actorId,
            UUID runId,
            String nodeId,
            int attempt,
            String traceId,
            String requestId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowRunId", runId.toString());
        details.put("nodeId", nodeId);
        details.put("attempt", attempt);
        return AuditEvent.create(
                now,
                tenantId,
                AuditActorType.USER,
                actorId.toString(),
                AuditActions.WORKFLOW_RUN_RETRIED,
                AuditTargetType.WORKFLOW_RUN,
                runId.toString(),
                AuditOutcome.SUCCESS,
                traceId,
                requestId,
                null,
                details);
    }
}
