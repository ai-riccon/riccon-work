package com.riccon.api.workflow.publish.service;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.workflow.WorkflowRunScope;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 发布审计事件构造。
 *
 * <p>只写 ADR-0024 §8.1 白名单：{@code workflow.published} 含流程 ID、版本 ID/号、范围、部门与节点数；
 * 不写图 JSON、prompt/query 模板、节点输出、工具参数或文档正文。事件与版本插入、发布指针更新同事务
 * 写入，审计失败会回滚发布（ADR-0007/§8.2）。
 */
public final class WorkflowPublishAuditEvents {

    /** 工具类禁止实例化。 */
    private WorkflowPublishAuditEvents() {}

    /**
     * 构造发布审计。
     *
     * @param now 发生时间
     * @param tenantId 租户
     * @param actorId 发布人
     * @param workflowId 流程 ID
     * @param versionId 版本 ID
     * @param versionNo 版本号
     * @param runScope 运行范围
     * @param departmentId 部门范围 ID；其他范围为空
     * @param nodeCount 节点数
     * @param traceId 追踪 ID
     * @param requestId 请求 ID；可空
     * @return 审计事件
     */
    public static AuditEvent published(
            Instant now,
            String tenantId,
            UUID actorId,
            UUID workflowId,
            UUID versionId,
            int versionNo,
            WorkflowRunScope runScope,
            UUID departmentId,
            int nodeCount,
            String traceId,
            String requestId) {
        Objects.requireNonNull(runScope, "runScope must not be null");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowId", workflowId.toString());
        details.put("workflowVersionId", versionId.toString());
        details.put("versionNo", versionNo);
        details.put("runScope", runScope.name());
        // 非 DEPARTMENT 范围显式写 null；AuditDetailsPolicy 对该动作的 departmentId 允许空值。
        details.put("departmentId", departmentId == null ? null : departmentId.toString());
        details.put("nodeCount", nodeCount);
        return AuditEvent.create(
                now,
                tenantId,
                AuditActorType.USER,
                actorId.toString(),
                AuditActions.WORKFLOW_PUBLISHED,
                AuditTargetType.WORKFLOW,
                workflowId.toString(),
                AuditOutcome.SUCCESS,
                traceId,
                requestId,
                null,
                details);
    }
}
