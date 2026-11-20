package com.riccon.worker.audit.service;

import com.riccon.platform.common.tenant.TenantContext;
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
 *
 * <p>M04：降级事件按请求租户写入；`model.credential.invalidated` 必须以平台租户 +
 * {@code actor_type=SYSTEM} 写入（ADR-0021 §7.4），因此单独提供 {@code appendSystem} 路径，
 * 不复用调用方租户。
 */
@Component
public class AuditService {

    /** Worker 服务操作者 ID。 */
    static final String SERVICE_ACTOR = "riccon-worker";

    /** 平台 key 生命周期操作者 ID；不指向任何员工。 */
    static final String PLATFORM_ACTOR = "platform-key-pool";

    /**
     * 平台保留租户标识。
     *
     * <p>与 {@code provider_credential.tenant_id='platform'} 及 ADR-0012 的平台例外一致；
     * 审计服务位于 service 层，不能依赖 persistence 包的 {@code PlatformTenant} 常量
     * （ArchUnit 单向分层），因此在此显式声明。
     */
    static final TenantId PLATFORM_TENANT = TenantId.of("platform");

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
                AuditActorType.SERVICE,
                SERVICE_ACTOR,
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
                AuditActorType.SERVICE,
                SERVICE_ACTOR,
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
     * @param emitted 是否已在首字后产生可见输出（区分 AC-04 首字后失败）
     */
    public void modelFailed(
            TenantId tenantId,
            String traceId,
            String requestId,
            UUID modelId,
            long durationMs,
            String errorCode,
            boolean retryable,
            boolean emitted) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("modelId", modelId.toString());
        details.put("durationMs", durationMs);
        details.put("errorCode", errorCode);
        details.put("retryable", retryable);
        details.put("emitted", emitted);
        append(
                tenantId,
                AuditActorType.SERVICE,
                SERVICE_ACTOR,
                traceId,
                requestId,
                AuditActions.MODEL_INVOCATION_FAILED,
                AuditTargetType.MODEL,
                modelId.toString(),
                AuditOutcome.FAILURE,
                details);
    }

    /**
     * 全链不可用（链为空/无可用 key），尚未创建调用记录时写一条失败审计。
     *
     * <p>此时没有具体模型 ID，targetId 使用用途，details 只含错误码、可重试标记与 0 耗时；
     * 原因是链为空由指标 `riccon_model_chain_empty_total{purpose}` 与运行状态承载，
     * 不新增 details 键，保持 ADR-0007/ADR-0021 §7.4 白名单不漂移。
     *
     * @param tenantId 租户
     * @param traceId 追踪 ID
     * @param requestId 请求 ID，可为空
     * @param purpose 用途
     * @param errorCode 稳定错误码
     */
    public void modelUnavailable(
            TenantId tenantId, String traceId, String requestId, ModelPurpose purpose, String errorCode) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("durationMs", 0L);
        details.put("errorCode", errorCode);
        details.put("retryable", true);
        details.put("emitted", false);
        append(
                tenantId,
                AuditActorType.SERVICE,
                SERVICE_ACTOR,
                traceId,
                requestId,
                AuditActions.MODEL_INVOCATION_FAILED,
                AuditTargetType.MODEL,
                purpose.name(),
                AuditOutcome.FAILURE,
                details);
    }

    /**
     * 首字前切换 key/模型。
     *
     * <p>details 只含模型 ID/展示级原因，不含 credential ID、provider 地址或 key；
     * 写入失败必须让当前模型请求失败（fail-closed）。
     *
     * @param tenantId 请求租户
     * @param traceId 追踪 ID
     * @param requestId 请求 ID，可为空
     * @param purpose 用途
     * @param fromModelId 失败模型 ID
     * @param toModelId 下一个尝试的模型 ID（同 key 重试时相同）
     * @param reason 触发原因
     * @param attempt 失败尝试序号
     */
    public void modelFailoverTriggered(
            TenantId tenantId,
            String traceId,
            String requestId,
            ModelPurpose purpose,
            UUID fromModelId,
            UUID toModelId,
            String reason,
            int attempt) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("purpose", purpose.name().toLowerCase(java.util.Locale.ROOT));
        details.put("fromModelId", fromModelId.toString());
        details.put("toModelId", toModelId.toString());
        details.put("reason", reason);
        details.put("attempt", attempt);
        append(
                tenantId,
                AuditActorType.SERVICE,
                SERVICE_ACTOR,
                traceId,
                requestId,
                WorkerAuditActions.MODEL_FAILOVER_TRIGGERED,
                AuditTargetType.MODEL,
                fromModelId.toString(),
                AuditOutcome.SUCCESS,
                details);
    }

    /**
     * 401/403 后平台 key 自动失效。
     *
     * <p>以 {@code tenant_id=platform}、{@code actor_type=SYSTEM} 写入；租户管理员不可见
     * （ADR-0021 §7.4）。写入失败时调用方必须让模型请求失败。
     *
     * @param providerId 供应商 ID
     * @param credentialId 凭据 ID
     * @param maskedHint 掩码
     * @param reason 失效原因
     */
    public void modelCredentialInvalidated(UUID providerId, UUID credentialId, String maskedHint, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("providerId", providerId.toString());
        details.put("credentialId", credentialId.toString());
        details.put("maskedHint", maskedHint == null || maskedHint.isBlank() ? "****" : maskedHint);
        details.put("reason", reason);
        // 平台租户上下文：TenantJdbc 要求当前线程租户与写入租户一致，且租户管理员不可见该事件。
        TenantContext.callWithTenant(PLATFORM_TENANT, () -> {
            append(
                    PLATFORM_TENANT,
                    AuditActorType.SYSTEM,
                    PLATFORM_ACTOR,
                    "credential-" + credentialId,
                    null,
                    WorkerAuditActions.MODEL_CREDENTIAL_INVALIDATED,
                    AuditTargetType.MODEL_CREDENTIAL,
                    credentialId.toString(),
                    AuditOutcome.SUCCESS,
                    details);
            return Boolean.TRUE;
        });
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
                AuditActorType.SERVICE,
                SERVICE_ACTOR,
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
                AuditActorType.SERVICE,
                SERVICE_ACTOR,
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
                AuditActorType.SERVICE,
                SERVICE_ACTOR,
                traceId,
                requestId,
                WorkerAuditActions.USER_ACCESS_DENIED,
                AuditTargetType.USER,
                userId.toString(),
                AuditOutcome.DENIED,
                Map.of("userId", userId.toString(), "reason", "USER_DISABLED"));
    }

    /**
     * 报告产物保存。
     *
     * @param tenantId 租户
     * @param traceId 追踪 ID
     * @param requestId 请求 ID，可为空
     * @param artifactId 产物 ID
     * @param reportRunId 生成运行 ID
     * @param version 版本号
     */
    public void artifactSaved(
            TenantId tenantId, String traceId, String requestId, UUID artifactId, UUID reportRunId, int version) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("artifactId", artifactId.toString());
        details.put("reportRunId", reportRunId.toString());
        details.put("version", version);
        append(
                tenantId,
                AuditActorType.SERVICE,
                SERVICE_ACTOR,
                traceId,
                requestId,
                AuditActions.ARTIFACT_SAVED,
                AuditTargetType.ARTIFACT,
                artifactId.toString(),
                AuditOutcome.SUCCESS,
                details);
    }

    /**
     * 构造并追加事件。
     *
     * @param tenantId 租户
     * @param actorType 操作者类型
     * @param actorId 操作者 ID
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
            AuditActorType actorType,
            String actorId,
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
                actorType,
                actorId,
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
