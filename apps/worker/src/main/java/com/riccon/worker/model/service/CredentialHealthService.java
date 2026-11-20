package com.riccon.worker.model.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.audit.service.AuditService;
import com.riccon.worker.model.domain.FailureReason;
import com.riccon.worker.model.domain.ModelCredential;
import com.riccon.worker.model.domain.ModelException;
import com.riccon.worker.model.domain.ModelFailureKind;
import com.riccon.worker.model.domain.RoutedModel;
import com.riccon.worker.model.repository.ModelRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 平台 key 健康状态机（ADR-0021 §5.1/§5.5）。
 *
 * <p>健康状态只落数据库，跨 Worker 实例共享，不用进程内熔断器：
 *
 * <ul>
 *   <li>401/403：`ACTIVE → INVALID`，写 `model.credential.invalidated`（platform/SYSTEM），审计失败即请求失败；
 *   <li>429：写 `cooldown_until`（默认 60 秒），不改状态；
 *   <li>超时/连接失败/非法响应：`consecutive_failures+1`，连续 3 次冷却 30 秒；
 *   <li>配置错误与首字后中断：不改 key 状态。
 * </ul>
 *
 * <p>明文中转不经过本类；日志只记录 provider code、凭据 ID 与原因，不含密文或 Authorization。
 */
@Component
public class CredentialHealthService {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(CredentialHealthService.class);

    /** 连续失败触发冷却的阈值。 */
    static final int FAILURE_COOLDOWN_THRESHOLD = 3;

    /** 连续失败触发的冷却时长。 */
    static final Duration FAILURE_COOLDOWN = Duration.ofSeconds(30);

    /** 429 默认冷却时长；上游未解析 Retry-After 时使用。 */
    static final Duration RATE_LIMIT_COOLDOWN = Duration.ofSeconds(60);

    /** 模型仓储。 */
    private final ModelRepository modelRepository;

    /** 审计。 */
    private final AuditService auditService;

    /** 指标。 */
    private final ModelMetrics metrics;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建 key 健康服务。
     *
     * @param modelRepository 模型仓储
     * @param auditService 审计
     * @param metrics 指标
     * @param clock UTC 时钟
     */
    public CredentialHealthService(
            ModelRepository modelRepository, AuditService auditService, ModelMetrics metrics, Clock clock) {
        this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 根据失败原因更新 key 健康状态。
     *
     * <p>401/403 的失效审计 fail-closed：审计写失败时抛出配置错误，由降级状态机终止请求。
     *
     * @param tenantId 请求租户（仅日志/追踪上下文）
     * @param model 本次尝试的模型
     * @param credential 本次尝试的 key
     * @param reason 失败原因
     * @param traceId 追踪 ID
     */
    public void onAttemptFailure(
            TenantId tenantId, RoutedModel model, ModelCredential credential, FailureReason reason, String traceId) {
        switch (reason) {
            case UNAUTHORIZED, FORBIDDEN -> invalidate(model, credential, reason);
            case RATE_LIMITED -> rateLimited(model, credential);
            case UNAVAILABLE, TIMEOUT, INVALID_RESPONSE -> recordFailure(model, credential);
            case CONFIGURATION, INTERRUPTED, RETRY -> {
                // 配置问题与首字后中断不改变 key 状态：前者可能是主密钥/DEK 问题，后者可能是网络瞬断。
            }
        }
    }

    /**
     * 记录成功使用时间；best-effort，失败只记日志，不影响已成功的回答。
     *
     * @param credential 使用的 key
     */
    public void onSuccess(ModelCredential credential) {
        try {
            modelRepository.markCredentialUsed(credential.id());
        } catch (RuntimeException ex) {
            LOG.warn(
                    "model.credential.last_used_update_failed providerId={} credentialId={}",
                    credential.providerId(),
                    credential.id());
        }
    }

    /**
     * 401/403：立即失效并审计（SYSTEM/platform）。
     *
     * @param model 模型
     * @param credential key
     * @param reason 失效原因
     */
    private void invalidate(RoutedModel model, ModelCredential credential, FailureReason reason) {
        boolean changed = modelRepository.invalidateCredential(credential.id());
        if (!changed) {
            // 并发请求可能已经失效同一把 key；幂等返回，不重复写审计。
            return;
        }
        try {
            auditService.modelCredentialInvalidated(
                    model.target().provider().id(), credential.id(), credential.maskedHint(), reason.code());
        } catch (RuntimeException ex) {
            throw new ModelException(
                    ModelException.CONFIGURATION,
                    false,
                    "model credential invalidation audit could not be written",
                    ModelFailureKind.CONFIGURATION,
                    ex);
        }
        metrics.credentialInvalid(model.target().provider().code());
        LOG.warn(
                "model.credential.invalidated providerCode={} credentialId={} reason={}",
                model.target().provider().code(),
                credential.id(),
                reason.code());
    }

    /**
     * 429：写冷却截止，不改状态。
     *
     * @param model 模型
     * @param credential key
     */
    private void rateLimited(RoutedModel model, ModelCredential credential) {
        LocalDateTime until = cooldownUntil(RATE_LIMIT_COOLDOWN);
        modelRepository.recordCredentialRateLimited(credential.id(), until);
        metrics.credentialCooldown(model.target().provider().code());
    }

    /**
     * 可重试失败：次数 +1，连续 3 次冷却 30 秒。
     *
     * @param model 模型
     * @param credential key
     */
    private void recordFailure(RoutedModel model, ModelCredential credential) {
        int current = modelRepository.currentCredentialFailures(credential.id());
        modelRepository.recordCredentialFailure(credential.id(), cooldownUntil(FAILURE_COOLDOWN));
        if (current + 1 >= FAILURE_COOLDOWN_THRESHOLD) {
            metrics.credentialCooldown(model.target().provider().code());
        }
    }

    /**
     * 计算 UTC 冷却截止。
     *
     * @param duration 冷却时长
     * @return UTC LocalDateTime
     */
    private LocalDateTime cooldownUntil(Duration duration) {
        return LocalDateTime.ofInstant(clock.instant().plus(duration), ZoneOffset.UTC);
    }
}
