package com.riccon.worker.model.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.model.InvocationStatus;
import com.riccon.platform.domain.model.ModelPurpose;
import com.riccon.worker.audit.service.AuditService;
import com.riccon.worker.common.UuidV7;
import com.riccon.worker.model.domain.ModelException;
import com.riccon.worker.model.domain.ModelInvocationRecord;
import com.riccon.worker.model.repository.ModelInvocationRepository;
import com.riccon.worker.model.repository.ModelInvocationRepository.CostSnapshot;
import com.riccon.worker.model.repository.ModelInvocationRepository.CostStatus;
import com.riccon.worker.model.repository.ModelInvocationRepository.ModelPricing;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模型调用记录、成本快照与审计。
 *
 * <p>每次调用固定写 {@code model.invocation.started} 与 completed/failed 两条审计；
 * 调用记录用于运行详情。任何写库失败都让当前模型调用失败，禁止静默继续（ADR-0007）。
 * M04 增记 {@code attempt_count} 与 {@code emitted}，并在最终成功调用写入价格/币种/金额快照。
 *
 * <p>成本口径（ADR-0022 §2.2）：{@code prompt_tokens × input_price_per_1m / 1_000_000 +
 * completion_tokens × output_price_per_1m / 1_000_000}，使用 {@link BigDecimal}、6 位小数、
 * {@link RoundingMode#HALF_UP}。价格缺失为 {@code UNPRICED}，token 缺失为 {@code USAGE_UNKNOWN}；
 * 两者都不写金额。失败/取消只写 {@code USAGE_UNKNOWN} 与日桶，不伪造成本。
 */
@Component
public class ModelInvocationService {

    /** 日志记录器；只记录调用 ID 与错误类型。 */
    private static final Logger LOG = LoggerFactory.getLogger(ModelInvocationService.class);

    /** 计价公式版本；M04 固定 v1（ADR-0022 §2.1）。 */
    public static final String PRICING_VERSION = "v1";

    /** 每百万 token 的价格除数。 */
    private static final BigDecimal TOKENS_PER_MILLION = new BigDecimal("1000000");

    /** 成本存储精度：6 位小数。 */
    private static final int COST_SCALE = 6;

    /** 成本日桶时区；日/月边界统一使用北京时间（ADR-0022 §1.6/§2.3）。 */
    private static final ZoneId COST_ZONE = ZoneId.of("Asia/Shanghai");

    /** 调用记录仓储。 */
    private final ModelInvocationRepository invocationRepository;

    /** 审计服务。 */
    private final AuditService auditService;

    /** UTC 时钟。 */
    private final Clock clock;

    /** 部署计费币种。 */
    private final BillingSettings billingSettings;

    /**
     * 创建调用记录服务。
     *
     * @param invocationRepository 调用记录仓储
     * @param auditService 审计服务
     * @param clock UTC 时钟
     * @param billingSettings 部署计费币种
     */
    public ModelInvocationService(
            ModelInvocationRepository invocationRepository,
            AuditService auditService,
            Clock clock,
            BillingSettings billingSettings) {
        this.invocationRepository = Objects.requireNonNull(invocationRepository, "invocationRepository");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.billingSettings = Objects.requireNonNull(billingSettings, "billingSettings");
    }

    /**
     * 记录调用开始。
     *
     * @param tenantId 租户
     * @param runId 运行 ID，可为空
     * @param conversationId 会话 ID，可为空
     * @param messageId 回答消息 ID，可为空
     * @param userId 发起人快照，可为空
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
            UUID userId,
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
                            id, runId, conversationId, messageId, modelId, modelKey, purpose, traceId),
                    userId,
                    Instant.ofEpochMilli(startedAt));
            auditService.modelStarted(tenantId, traceId, requestId, modelId, purpose);
        } catch (RuntimeException ex) {
            throw new ModelException(ModelException.CONFIGURATION, false, "model invocation could not be recorded", ex);
        }
        return new InvocationHandle(id, tenantId, modelId, startedAt);
    }

    /**
     * 记录成功结束并写入成本快照。
     *
     * <p>价格在成功终态读取一次并固定：M04 不提供价格编辑端点，一次成功调用只使用一个价格快照，
     * 后续更新 {@code model} 价格不会改写本行（ADR-0022 §2.1）。
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
            CostSnapshot cost = costSnapshot(actualModelId, promptTokens, completionTokens, handle.startedAt());
            invocationRepository.succeeded(
                    handle.tenantId(),
                    handle.id(),
                    actualModelId,
                    actualModelKey,
                    firstTokenMs,
                    duration,
                    promptTokens,
                    completionTokens,
                    normalizedAttempts,
                    cost);
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
                    handle.tenantId(),
                    handle.id(),
                    status,
                    errorCode,
                    duration,
                    firstTokenMs,
                    normalizedAttempts,
                    costDay(handle.startedAt()));
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
     * 计算单次成功调用的成本快照。
     *
     * <p>状态优先级：先判断价格，再判断 token；价格任一为空即 {@code UNPRICED}（有 token 也无法计价），
     * 价格齐全但 token 缺失为 {@code USAGE_UNKNOWN}。金额只在 {@code PRICED} 时计算并做一次 6 位
     * HALF_UP 舍入。
     *
     * @param modelId 最终成功模型 ID
     * @param promptTokens 输入 token，可为空
     * @param completionTokens 输出 token，可为空
     * @param startedAt 调用开始瞬时（epoch millis）
     * @return 成本快照
     */
    private CostSnapshot costSnapshot(UUID modelId, Integer promptTokens, Integer completionTokens, long startedAt) {
        LocalDate day = costDay(startedAt);
        ModelPricing pricing = invocationRepository.findPricing(modelId).orElse(new ModelPricing(null, null));
        BigDecimal inputPrice = pricing.inputPricePer1M();
        BigDecimal outputPrice = pricing.outputPricePer1M();
        if (inputPrice == null || outputPrice == null) {
            return new CostSnapshot(inputPrice, outputPrice, null, null, CostStatus.UNPRICED, PRICING_VERSION, day);
        }
        String currency = billingSettings.currency();
        if (promptTokens == null || completionTokens == null) {
            return new CostSnapshot(
                    inputPrice, outputPrice, currency, null, CostStatus.USAGE_UNKNOWN, PRICING_VERSION, day);
        }
        // 先求分子和，再做一次 6 位 HALF_UP 除法；禁止先按展示精度舍入再汇总（ADR-0022 §2.2）。
        BigDecimal numerator = BigDecimal.valueOf(promptTokens)
                .multiply(inputPrice)
                .add(BigDecimal.valueOf(completionTokens).multiply(outputPrice));
        BigDecimal amount = numerator.divide(TOKENS_PER_MILLION, COST_SCALE, RoundingMode.HALF_UP);
        return new CostSnapshot(inputPrice, outputPrice, currency, amount, CostStatus.PRICED, PRICING_VERSION, day);
    }

    /**
     * 把开始瞬时转换为 {@code Asia/Shanghai} 自然日。
     *
     * <p>日桶必须与查询侧边界一致；这里使用带时区转换而不是手工加 8 小时（ADR-0022 §1.6）。
     *
     * @param epochMillis 开始瞬时
     * @return 北京时间自然日
     */
    static LocalDate costDay(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(COST_ZONE).toLocalDate();
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

    /**
     * Worker 部署计费币种（{@code RICCON_BILLING_CURRENCY}）。
     *
     * <p>与 API 的 {@code BillingProperties} 使用同一配置键；只接受 ISO 4217 三位大写代码，
     * 非法值启动失败。M04 单币种，历史快照币种不一致由报告单列（ADR-0022 §2.1）。
     *
     * <p>本类与调用记录服务同文件，避免为单个只读常量新增配置包文件；语义仍是
     * {@code @ConfigurationProperties} + 启动校验。
     */
    @Component
    @ConfigurationProperties(prefix = "riccon.billing")
    public static class BillingSettings implements InitializingBean {

        /** ISO 4217 币种格式。 */
        private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");

        /** 部署币种，默认 CNY。 */
        private String currency = "CNY";

        /**
         * 绑定币种。
         *
         * @param currency ISO 4217 三位代码
         */
        public void setCurrency(String currency) {
            this.currency = currency;
        }

        /**
         * 返回部署币种。
         *
         * @return 三位大写币种
         */
        public String currency() {
            return currency;
        }

        /**
         * 校验币种格式。
         *
         * @throws IllegalStateException 币种不是 ISO 4217 三位代码
         */
        @Override
        public void afterPropertiesSet() {
            String normalized = currency == null ? "" : currency.strip().toUpperCase(Locale.ROOT);
            if (!CURRENCY_PATTERN.matcher(normalized).matches()) {
                throw new IllegalStateException("riccon.billing.currency must be an ISO 4217 code");
            }
            this.currency = normalized;
        }
    }
}
