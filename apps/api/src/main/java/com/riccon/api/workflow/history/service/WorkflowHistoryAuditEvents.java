package com.riccon.api.workflow.history.service;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 运行管理性读取审计事件。
 *
 * <p>动作固定为 {@code workflow.run.admin_read}，details 只写 {@code workflowRunId}（仅 DETAIL）、
 * {@code scope}（LIST/DETAIL）与固定原因 {@code MANAGEMENT_READ}；不写运行输入、结果、提示词或参数
 * （ADR-0024 §8.1/§8.2）。
 */
public final class WorkflowHistoryAuditEvents {

    /** 管理性读取的固定原因。 */
    private static final String MANAGEMENT_READ = "MANAGEMENT_READ";

    /** 工具类禁止实例化。 */
    private WorkflowHistoryAuditEvents() {}

    /**
     * 构造列表管理性读取审计。
     *
     * <p>一次列表请求写一条 {@code scope=LIST} 审计，不携带单个运行 ID；目标类型仍为
     * {@code WORKFLOW_RUN}（ADR-0024 §8.2）。
     *
     * @param now 事件时间
     * @param tenantId 租户
     * @param actorId 租户管理员账号
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 审计事件
     */
    public static AuditEvent adminListRead(
            Instant now, String tenantId, UUID actorId, String traceId, String requestId) {
        return adminRead(now, tenantId, actorId, null, "LIST", traceId, requestId);
    }

    /**
     * 构造详情管理性读取审计。
     *
     * @param now 事件时间
     * @param tenantId 租户
     * @param actorId 租户管理员账号
     * @param runId 被读取的运行 ID
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 审计事件
     */
    public static AuditEvent adminDetailRead(
            Instant now, String tenantId, UUID actorId, UUID runId, String traceId, String requestId) {
        Objects.requireNonNull(runId, "runId must not be null");
        return adminRead(now, tenantId, actorId, runId, "DETAIL", traceId, requestId);
    }

    /**
     * 构造管理性读取审计。
     *
     * @param now 事件时间
     * @param tenantId 租户
     * @param actorId 租户管理员账号
     * @param runId 运行 ID；列表为空
     * @param scope 范围 LIST/DETAIL
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 审计事件
     */
    private static AuditEvent adminRead(
            Instant now, String tenantId, UUID actorId, UUID runId, String scope, String traceId, String requestId) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (runId != null) {
            details.put("workflowRunId", runId.toString());
        }
        details.put("scope", scope);
        details.put("reason", MANAGEMENT_READ);
        return AuditEvent.create(
                now,
                tenantId,
                AuditActorType.USER,
                actorId.toString(),
                AuditActions.WORKFLOW_RUN_ADMIN_READ,
                AuditTargetType.WORKFLOW_RUN,
                runId == null ? null : runId.toString(),
                AuditOutcome.SUCCESS,
                traceId,
                requestId,
                null,
                details);
    }
}
