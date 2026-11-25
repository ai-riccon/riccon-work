package com.riccon.worker.workflow.queue.service;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.worker.audit.domain.AuditDetailsPolicy;
import com.riccon.worker.audit.domain.AuditEvent;
import com.riccon.worker.audit.domain.WorkerAuditActions;
import com.riccon.worker.audit.repository.AuditRepository;
import com.riccon.worker.common.UuidV7;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 运行队列审计（非法租户拒绝与 WF_INTERNAL 终态）。
 *
 * <p>ADR-0024 §4.3/TM-85 要求：非法/空租户 fail-closed，写 {@code platform} 租户 denied 审计
 * （不打开目标租户上下文、不读取业务列）；{@code claim_attempts} 耗尽进入 {@code WF_INTERNAL}
 * 时写 {@code workflow.run.failed}。审计 details 走
 * {@link AuditDetailsPolicy} 白名单，只写 ID、枚举与稳定错误码，不写输入/输出/图与工具参数。
 *
 * <p>{@code AuditService} 只承载模型/MCP 动作且不在本任务写范围；本类复用同一仓储与白名单，
 * 保持“审计失败即业务失败”的 fail-closed 语义。
 */
@Component
public class WorkflowQueueAuditor {

    /** Worker 服务操作者 ID，与 AuditService 保持一致。 */
    private static final String SERVICE_ACTOR = "riccon-worker";

    /** 平台保留租户；与 ADR-0012 的平台例外一致。 */
    private static final TenantId PLATFORM_TENANT = TenantId.of("platform");

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
    public WorkflowQueueAuditor(AuditRepository auditRepository, Clock clock) {
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 写入非法租户拒绝审计（platform 租户、DENIED）。
     *
     * <p>只记录被尝试的租户字符串与运行 ID 作为 trace，不读取该运行任何业务内容；无法归属时
     * 仍然写 platform，便于安全评审检索（TEN-M06-01）。
     *
     * @param runId 被领取的运行 ID
     * @param attemptedTenant 领取行上的原始租户字符串
     */
    public void claimDenied(UUID runId, String attemptedTenant) {
        Map<String, Object> details = Map.of("attemptedTenant", attemptedTenant, "reason", "INVALID_TENANT");
        AuditDetailsPolicy.validate(WorkerAuditActions.TENANT_ACCESS_DENIED, details);
        AuditEvent event = new AuditEvent(
                UuidV7.generate(),
                clock.instant(),
                PLATFORM_TENANT.value(),
                AuditActorType.SYSTEM,
                SERVICE_ACTOR,
                WorkerAuditActions.TENANT_ACCESS_DENIED,
                AuditTargetType.TENANT,
                null,
                AuditOutcome.DENIED,
                traceId(runId),
                null,
                details,
                AuditEvent.CURRENT_SCHEMA_VERSION);
        TenantContext.callWithTenant(PLATFORM_TENANT, () -> {
            auditRepository.append(event);
            return Boolean.TRUE;
        });
    }

    /**
     * 写入 WF_INTERNAL 失败审计（租户内 FAILURE）。
     *
     * @param tenantId 已校验租户
     * @param runId 运行 ID
     * @param failedNodeKey 当前节点 key；可为空
     */
    public void claimExhausted(TenantId tenantId, UUID runId, String failedNodeKey) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowRunId", runId.toString());
        details.put("errorCode", WorkflowErrorCode.WF_INTERNAL.name());
        if (failedNodeKey != null && !failedNodeKey.isBlank()) {
            details.put("failedNodeId", failedNodeKey);
        }
        AuditDetailsPolicy.validate(AuditActions.WORKFLOW_RUN_FAILED, details);
        Instant now = clock.instant();
        AuditEvent event = new AuditEvent(
                UuidV7.generate(),
                now,
                tenantId.value(),
                AuditActorType.SERVICE,
                SERVICE_ACTOR,
                AuditActions.WORKFLOW_RUN_FAILED,
                AuditTargetType.WORKFLOW_RUN,
                runId.toString(),
                AuditOutcome.FAILURE,
                traceId(runId),
                null,
                details,
                AuditEvent.CURRENT_SCHEMA_VERSION);
        TenantContext.callWithTenant(tenantId, () -> {
            auditRepository.append(event);
            return Boolean.TRUE;
        });
    }

    /**
     * 生成稳定的审计 trace ID。
     *
     * @param runId 运行 ID
     * @return 不超过 64 字符的 trace
     */
    private static String traceId(UUID runId) {
        return "workflow-claim-" + runId;
    }
}
