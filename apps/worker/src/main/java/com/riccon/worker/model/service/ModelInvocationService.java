package com.riccon.worker.model.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.model.InvocationStatus;
import com.riccon.platform.domain.model.ModelPurpose;
import com.riccon.worker.audit.service.AuditService;
import com.riccon.worker.common.UuidV7;
import com.riccon.worker.model.domain.ModelException;
import com.riccon.worker.model.domain.ModelInvocationRecord;
import com.riccon.worker.model.repository.ModelInvocationRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 模型调用记录与审计。
 *
 * <p>每次调用固定写 {@code model.invocation.started} 与 completed/failed 两条审计；
 * 调用记录用于运行详情。任何写库失败都让当前模型调用失败，禁止静默继续（ADR-0007）。
 * M04 增记 {@code attempt_count} 与 {@code emitted}，把尝试数与首字后语义落库。
 */
@Component
public class ModelInvocationService {

    /** 日志记录器；只记录调用 ID 与错误类型。 */
    private static final Logger LOG = LoggerFactory.getLogger(ModelInvocationService.class);

    /** 调用记录仓储。 */
    private final ModelInvocationRepository invocationRepository;

    /** 审计服务。 */
    private final AuditService auditService;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建调用记录服务。
     *
     * @param invocationRepository 调用记录仓储
     * @param auditService 审计服务
     * @param clock UTC 时钟
     */
    public ModelInvocationService(
            ModelInvocationRepository invocationRepository, AuditService auditService, Clock clock) {
        this.invocationRepository = Objects.requireNonNull(invocationRepository, "invocationRepository");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 记录调用开始。
     *
     * @param tenantId 租户
     * @param runId 运行 ID，可为空
     * @param conversationId 会话 ID，可为空
     * @param messageId 回答消息 ID，可为空
     * @param modelId 首个尝试模型 ID
     * @param modelKey 模型标识快照
     * @param purpose 用途
     * @param traceId 追踪 ID
     * @param requestId 请求 ID，可为空
     * @return 调用句柄
     */
    public InvocationHandle start(
            TenantId tenantId,
            UUID runId,
            UUID conversationId,
            UUID messageId,
            UUID modelId,
            String modelKey,
            ModelPurpose purpose,
            String traceId,
            String requestId) {
        UUID id = UuidV7.generate();
        long startedAt = clock.millis();
        try {
            invocationRepository.started(
                    tenantId,
                    new ModelInvocationRecord(
                            id, runId, conversationId, messageId, modelId, modelKey, purpose, traceId));
            auditService.modelStarted(tenantId, traceId, requestId, modelId, purpose);
        } catch (RuntimeException ex) {
            throw new ModelException(ModelException.CONFIGURATION, false, "model invocation could not be recorded", ex);
        }
        return new InvocationHandle(id, tenantId, modelId, startedAt);
    }

    /**
     * 记录成功结束。
     *
     * @param handle 调用句柄
     * @param actualModelId 最终成功模型 ID（降级后可能与首个尝试不同）
     * @param actualModelKey 最终成功模型标识快照
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @param firstTokenMs 首字延迟
     * @param promptTokens 输入 token，可为空
     * @param completionTokens 输出 token，可为空
     * @param attemptCount 实际尝试次数
     */
    public void succeeded(
            InvocationHandle handle,
            UUID actualModelId,
            String actualModelKey,
            String traceId,
            String requestId,
            Integer firstTokenMs,
            Integer promptTokens,
            Integer completionTokens,
            int attemptCount) {
        long duration = Math.max(0, clock.millis() - handle.startedAt());
        int normalizedAttempts = Math.max(1, attemptCount);
        try {
            invocationRepository.succeeded(
                    handle.tenantId(),
                    handle.id(),
                    actualModelId,
                    actualModelKey,
                    firstTokenMs,
                    duration,
                    promptTokens,
                    completionTokens,
                    normalizedAttempts);
            auditService.modelCompleted(
                    handle.tenantId(), traceId, requestId, actualModelId, duration, promptTokens, completionTokens);
        } catch (RuntimeException ex) {
            LOG.error("model.invocation.completion_record_failed invocationId={}", handle.id(), ex);
            throw new ModelException(
                    ModelException.CONFIGURATION, false, "model invocation completion could not be recorded", ex);
        }
    }

    /**
     * 记录失败。
     *
     * @param handle 调用句柄
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @param errorCode 稳定错误码
     * @param retryable 是否可重试
     * @param firstTokenMs 首字延迟
     * @param emitted 是否已在首字后产生可见输出
     * @param attemptCount 实际尝试次数
     */
    public void failed(
            InvocationHandle handle,
            String traceId,
            String requestId,
            String errorCode,
            boolean retryable,
            Integer firstTokenMs,
            boolean emitted,
            int attemptCount) {
        terminate(
                handle,
                traceId,
                requestId,
                InvocationStatus.FAILED,
                errorCode,
                retryable,
                firstTokenMs,
                emitted,
                attemptCount);
    }

    /**
     * 记录用户取消。
     *
     * @param handle 调用句柄
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @param firstTokenMs 首字延迟
     * @param attemptCount 实际尝试次数
     */
    public void cancelled(
            InvocationHandle handle, String traceId, String requestId, Integer firstTokenMs, int attemptCount) {
        terminate(
                handle, traceId, requestId, InvocationStatus.CANCELLED, null, false, firstTokenMs, false, attemptCount);
    }

    /**
     * 推进终态；取消不写失败审计（取消不是模型失败）。
     *
     * @param handle 调用句柄
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @param status FAILED 或 CANCELLED
     * @param errorCode 错误码
     * @param retryable 是否可重试
     * @param firstTokenMs 首字延迟
     * @param emitted 是否已有可见输出
     * @param attemptCount 实际尝试次数
     */
    private void terminate(
            InvocationHandle handle,
            String traceId,
            String requestId,
            InvocationStatus status,
            String errorCode,
            boolean retryable,
            Integer firstTokenMs,
            boolean emitted,
            int attemptCount) {
        long duration = Math.max(0, clock.millis() - handle.startedAt());
        int normalizedAttempts = Math.max(1, attemptCount);
        try {
            invocationRepository.terminated(
                    handle.tenantId(), handle.id(), status, errorCode, duration, firstTokenMs, normalizedAttempts);
            if (status == InvocationStatus.FAILED) {
                auditService.modelFailed(
                        handle.tenantId(),
                        traceId,
                        requestId,
                        handle.modelId(),
                        duration,
                        errorCode,
                        retryable,
                        emitted);
            }
        } catch (RuntimeException ex) {
            throw new ModelException(
                    ModelException.CONFIGURATION, false, "model invocation termination could not be recorded", ex);
        }
    }

    /**
     * 调用句柄。
     *
     * @param id 调用 ID
     * @param tenantId 租户
     * @param modelId 首个尝试模型 ID
     * @param startedAt 开始时间（epoch millis）
     */
    public record InvocationHandle(UUID id, TenantId tenantId, UUID modelId, long startedAt) {}
}
