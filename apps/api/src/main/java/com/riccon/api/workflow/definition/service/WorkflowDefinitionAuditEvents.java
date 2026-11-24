package com.riccon.api.workflow.definition.service;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionSourceType;
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
 * 流程草稿审计事件构造。
 *
 * <p>只写 ADR-0024 §8.1 白名单字段：{@code workflow.created} 写流程 ID 与来源模板，
 * {@code workflow.draft.saved} 写流程 ID、版本与节点数；不写 prompt、查询模板、图 JSON、工具参数或
 * 文档正文。事件由服务层与业务同事务写入，失败即业务回滚（ADR-0007）。
 */
public final class WorkflowDefinitionAuditEvents {

    /** 工具类禁止实例化。 */
    private WorkflowDefinitionAuditEvents() {}

    /**
     * 构造新建草稿审计。
     *
     * @param now 发生时间
     * @param tenantId 租户
     * @param actorId 操作者
     * @param workflowId 流程 ID
     * @param sourceTemplateId 来源模板 ID；可空
     * @param sourceTemplateType 来源模板类型；可空
     * @param traceId 追踪 ID
     * @param requestId 请求 ID；可空
     * @return 审计事件
     */
    public static AuditEvent created(
            Instant now,
            String tenantId,
            UUID actorId,
            UUID workflowId,
            UUID sourceTemplateId,
            WorkflowDefinitionSourceType sourceTemplateType,
            String traceId,
            String requestId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowId", workflowId.toString());
        details.put("sourceTemplateId", sourceTemplateId == null ? null : sourceTemplateId.toString());
        details.put("sourceTemplateType", sourceTemplateType == null ? null : sourceTemplateType.auditValue());
        return event(now, tenantId, actorId, AuditActions.WORKFLOW_CREATED, workflowId, traceId, requestId, details);
    }

    /**
     * 构造草稿保存审计。
     *
     * @param now 发生时间
     * @param tenantId 租户
     * @param actorId 操作者
     * @param workflowId 流程 ID
     * @param version 保存后的草稿版本
     * @param nodeCount 节点数
     * @param traceId 追踪 ID
     * @param requestId 请求 ID；可空
     * @return 审计事件
     */
    public static AuditEvent draftSaved(
            Instant now,
            String tenantId,
            UUID actorId,
            UUID workflowId,
            int version,
            int nodeCount,
            String traceId,
            String requestId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowId", workflowId.toString());
        details.put("version", version);
        details.put("nodeCount", nodeCount);
        return event(
                now, tenantId, actorId, AuditActions.WORKFLOW_DRAFT_SAVED, workflowId, traceId, requestId, details);
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
        Objects.requireNonNull(workflowId, "workflowId must not be null");
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
