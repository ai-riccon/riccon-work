package com.riccon.worker.audit.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.model.ModelPurpose;
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
 * Worker 审计写入服务。
 *
 * <p>只提供模型与 MCP 调用的固定动作，details 由 {@link AuditDetailsPolicy} 白名单校验。
 * 审计写入失败直接抛出，调用方必须让当前业务失败（ADR-0007 第 4 节）。
 */
@Component
public class AuditService {

    /** Worker 服务操作者 ID。 */
    static final String SERVICE_ACTOR = "riccon-worker";

    /** 审计仓储。 */
    private final AuditRepository auditRepository;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建审计服务。
     *
     * @param auditRepository 审计仓储
     * @param clock UTC 时钟
     */
    public AuditService(AuditRepository auditRepository, Clock clock) {
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 模型调用开始。
     *
     * @param tenantId 租户
     * @param traceId 追踪 ID
     * @param requestId 请求 ID，可为空
     * @param modelId 模型 ID
     * @param purpose 用途
     */
    public void modelStarted(TenantId tenantId, String traceId, String requestId, UUID modelId, ModelPurpose purpose) {
        append(
                tenantId,
                traceId,
                requestId,
                AuditActions.MODEL_INVOCATION_STARTED,
                AuditTargetType.MODEL,
                modelId.toString(),
                AuditOutcome.SUCCESS,
                Map.of("modelId", modelId.toString(), "purpose", purpose.name().toLowerCase(java.util.Locale.ROOT)));
    }

    /**
     * 模型调用成功结束。
     *
     * @param tenantId 租户
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @param modelId 模型 ID
     * @param durationMs 耗时
     * @param promptTokens 输入 token，可为空
     * @param completionTokens 输出 token，可为空
     */
    public void modelCompleted(
            TenantId tenantId,
            String traceId,
            String requestId,
            UUID modelId,
            long durationMs,
            Integer promptTokens,
            Integer completionTokens) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("modelId", modelId.toString());
        details.put("durationMs", durationMs);
        if (promptTokens != null) {
            details.put("promptTokens", promptTokens);
        }
        if (completionTokens != null) {
            details.put("completionTokens", completionTokens);
        }
        append(
                tenantId,
                traceId,
                requestId,
                AuditActions.MODEL_INVOCATION_COMPLETED,
                AuditTargetType.MODEL,
                modelId.toString(),
                AuditOutcome.SUCCESS,
                details);
    }

    /**
     * 模型调用失败。
     *
     * @param tenantId 租户
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @param modelId 模型 ID
     * @param durationMs 耗时
     * @param errorCode 稳定错误码
     * @param retryable 是否可重试
     */
    public void modelFailed(
            TenantId tenantId,
            String traceId,
            String requestId,
            UUID modelId,
            long durationMs,
            String errorCode,
            boolean retryable) {
        append(
                tenantId,
                traceId,
                requestId,
                AuditActions.MODEL_INVOCATION_FAILED,
                AuditTargetType.MODEL,
                modelId.toString(),
                AuditOutcome.FAILURE,
                Map.of(
                        "modelId", modelId.toString(),
                        "durationMs", durationMs,
                        "errorCode", errorCode,
                        "retryable", retryable));
    }

    /**
     * MCP 调用结束（成功、失败或拒绝）。
     *
     * @param tenantId 租户
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @param toolId 工具 ID
     * @param toolName 工具名快照
     * @param paramsSha256 入参摘要
     * @param resultSha256 结果摘要，失败或拒绝时为空
     * @param durationMs 耗时
     * @param approvalId 审批 ID，可为空
     * @param outcome 结果
     */
    public void mcpInvoked(
            TenantId tenantId,
            String traceId,
            String requestId,
            UUID toolId,
            String toolName,
            String paramsSha256,
            String resultSha256,
            long durationMs,
            UUID approvalId,
            AuditOutcome outcome) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("toolName", toolName);
        details.put("paramsSha256", paramsSha256);
        if (resultSha256 != null) {
            details.put("resultSha256", resultSha256);
        }
        details.put("durationMs", durationMs);
        if (approvalId != null) {
            details.put("approvalId", approvalId.toString());
        }
        // 失败原因只写 mcp_invocation.error_code；审计 details 严格按 ADR-0007 白名单，
        // 不新增 errorCode 键，避免两个通道的字段语义漂移。
        append(
                tenantId,
                traceId,
                requestId,
                AuditActions.MCP_TOOL_INVOKED,
                AuditTargetType.MCP_TOOL,
                toolId.toString(),
                outcome,
                details);
    }

    /**
     * 跨租户访问拒绝。
     *
     * <p>租户无法确定时调用方应传 {@code platform}；本方法不读取或回显被访问对象的内容。
     *
     * @param tenantId 记录审计的租户（令牌租户或 platform）
     * @param traceId 追踪 ID
     * @param requestId 请求 ID，可为空
     * @param attemptedTenant 被尝试访问的租户或 unknown
     * @param reason 拒绝原因键
     */
    public void tenantAccessDenied(
            TenantId tenantId, String traceId, String requestId, String attemptedTenant, String reason) {
        append(
                tenantId,
                traceId,
                requestId,
                WorkerAuditActions.TENANT_ACCESS_DENIED,
                AuditTargetType.TENANT,
                tenantId.value(),
                AuditOutcome.DENIED,
                Map.of("attemptedTenant", attemptedTenant, "reason", reason));
    }

    /**
     * 运行发起人已禁用。
     *
     * @param tenantId 租户
     * @param traceId 追踪 ID
     * @param requestId 请求 ID，可为空
     * @param userId 用户 ID
     */
    public void userAccessDenied(TenantId tenantId, String traceId, String requestId, UUID userId) {
        append(
                tenantId,
                traceId,
                requestId,
                WorkerAuditActions.USER_ACCESS_DENIED,
                AuditTargetType.USER,
                userId.toString(),
                AuditOutcome.DENIED,
                Map.of("userId", userId.toString(), "reason", "USER_DISABLED"));
    }

    /**
     * 构造并追加事件。
     *
     * @param tenantId 租户
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @param action 动作
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param outcome 结果
     * @param details 白名单字段
     */
    private void append(
            TenantId tenantId,
            String traceId,
            String requestId,
            String action,
            AuditTargetType targetType,
            String targetId,
            AuditOutcome outcome,
            Map<String, Object> details) {
        AuditDetailsPolicy.validate(action, details);
        Instant now = clock.instant();
        auditRepository.append(new AuditEvent(
                UuidV7.generate(),
                now,
                tenantId.value(),
                AuditActorType.SERVICE,
                SERVICE_ACTOR,
                action,
                targetType,
                targetId,
                outcome,
                traceId,
                requestId,
                details,
                AuditEvent.CURRENT_SCHEMA_VERSION));
    }
}
