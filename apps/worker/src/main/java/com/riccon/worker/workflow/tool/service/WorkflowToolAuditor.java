package com.riccon.worker.workflow.tool.service;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.worker.audit.domain.AuditDetailsPolicy;
import com.riccon.worker.audit.domain.AuditEvent;
import com.riccon.worker.audit.repository.AuditRepository;
import com.riccon.worker.common.UuidV7;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 工作流工具调用审计（{@code mcp.tool.invoked}，ADR-0024 §4.7/§8.1/T01）。
 *
 * <p>复用 T01 维护的 {@link AuditDetailsPolicy} 白名单，给 J2 同名动作追加可选
 * {@code workflowRunId}/{@code workflowNodeId}；不新增第二套工具审计动作。details 只含工具名、
 * 参数/结果哈希、耗时、审批 ID 与运行/节点 ID，不写工具参数、结果原文、端点或凭据。
 *
 * <p><b>fail-closed</b>：审计写入失败直接抛出；调用方必须让节点失败，不能静默继续。工具调用
 * 记录（{@code workflow_tool_invocation}）与审计互相独立，缺一都不能视为完成。
 */
@Component
public class WorkflowToolAuditor {

    /** Worker 服务操作者 ID，与既有审计组件一致。 */
    private static final String SERVICE_ACTOR = "riccon-worker";

    /** 审计 trace 前缀；与 T07a/引擎使用同一关联口径。 */
    private static final String TRACE_PREFIX = "workflow-run-";

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
    public WorkflowToolAuditor(AuditRepository auditRepository, Clock clock) {
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 写一次工具调用审计。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 工具节点 key
     * @param toolId 工具 ID
     * @param toolName 工具名快照
     * @param paramsSha256 参数摘要；未知时为空
     * @param resultSha256 结果摘要；失败/拒绝时为空
     * @param durationMs 耗时
     * @param approvalId 工作流审批 ID；未知时为空
     * @param outcome 结果
     */
    public void invoked(
            TenantId tenantId,
            UUID runId,
            String nodeKey,
            UUID toolId,
            String toolName,
            String paramsSha256,
            String resultSha256,
            long durationMs,
            UUID approvalId,
            AuditOutcome outcome) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowRunId", runId.toString());
        details.put("workflowNodeId", nodeKey);
        if (toolName != null && !toolName.isBlank()) {
            details.put("toolName", toolName);
        }
        if (paramsSha256 != null) {
            details.put("paramsSha256", paramsSha256);
        }
        if (resultSha256 != null) {
            details.put("resultSha256", resultSha256);
        }
        details.put("durationMs", Math.max(0L, durationMs));
        if (approvalId != null) {
            details.put("approvalId", approvalId.toString());
        }
        AuditDetailsPolicy.validate(AuditActions.MCP_TOOL_INVOKED, details);
        AuditEvent event = new AuditEvent(
                UuidV7.generate(),
                clock.instant(),
                tenantId.value(),
                AuditActorType.SERVICE,
                SERVICE_ACTOR,
                AuditActions.MCP_TOOL_INVOKED,
                AuditTargetType.MCP_TOOL,
                toolId.toString(),
                outcome,
                TRACE_PREFIX + runId,
                null,
                details,
                AuditEvent.CURRENT_SCHEMA_VERSION);
        // 审计与业务同租户：失败直接抛出，由调用方让节点失败（ADR-0007）。
        TenantContext.runWithTenant(tenantId, () -> auditRepository.append(event));
    }
}
