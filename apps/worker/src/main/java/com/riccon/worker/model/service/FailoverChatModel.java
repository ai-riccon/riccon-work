package com.riccon.worker.model.service;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.model.ModelPurpose;
import com.riccon.worker.audit.service.AuditService;
import com.riccon.worker.model.config.ModelRoutingProperties;
import com.riccon.worker.model.domain.FailureReason;
import com.riccon.worker.model.domain.ModelAttemptStatus;
import com.riccon.worker.model.domain.ModelCredential;
import com.riccon.worker.model.domain.ModelException;
import com.riccon.worker.model.domain.ModelFailureKind;
import com.riccon.worker.model.domain.ModelRoute;
import com.riccon.worker.model.domain.ModelTarget;
import com.riccon.worker.model.domain.ResolvedModel;
import com.riccon.worker.model.domain.RoutedModel;
import com.riccon.worker.model.repository.ModelAttemptRepository;
import com.riccon.worker.model.service.ModelInvocationService.InvocationHandle;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

/**
 * 带降级的 AgentScope ChatModel（ADR-0021 §5）。
 *
 * <p>所有重试、换 key 与换模型都由本类拥有；内层 {@link OpenAiCompatibleChatModel} 固定只发一次
 * HTTP，因此 HTTP 调用数 = `model_invocation_attempt` 行数 = 降级审计条数。
 *
 * <p>状态机不变量：
 *
 * <ul>
 *   <li>首字前失败才允许降级；每次尝试写一行 attempt，并在确实还有下一次尝试时写
 *       `model.failover.triggered`；
 *   <li>首字后失败不重试、不重放、不切换，保留已输出内容，以 502/504 中断文案结束；
 *   <li>同一 key 只对 5xx/429 显式退避重试一次，作为新的 attempt 计数；
 *   <li>全链失败统一 `RICCON-MODEL-503` 与固定中文文案；关闭 `failoverEnabled` 时只尝试一次；
 *   <li>尝试数上限、首字前预算、key 冷却与退避抖动共同控制降级风暴。
 * </ul>
 *
 * <p>本类是“每次调用一个实例”：同一个 Agent 运行内多次 stream（工具循环）共享首字标识与
 * 尝试预算，避免工具循环绕过上限。
 */
public class FailoverChatModel implements Model {

    /** 日志；只记录模型 ID、provider code、尝试号与原因。 */
    private static final Logger LOG = LoggerFactory.getLogger(FailoverChatModel.class);

    /** 已安装的路由链。 */
    private final ModelRoute route;

    /** 降级预算。 */
    private final ModelRoutingProperties routingProperties;

    /** 模型解析与客户端创建。 */
    private final ModelRegistry modelRegistry;

    /** 尝试记录。 */
    private final ModelAttemptRepository attemptRepository;

    /** key 健康。 */
    private final CredentialHealthService healthService;

    /** 审计。 */
    private final AuditService auditService;

    /** 指标。 */
    private final ModelMetrics metrics;

    /** UTC 时钟。 */
    private final Clock clock;

    /** 请求租户。 */
    private final TenantId tenantId;

    /** 调用句柄。 */
    private final InvocationHandle invocation;

    /** 追踪 ID。 */
    private final String traceId;

    /** 请求 ID，可为空。 */
    private final String requestId;

    /** 用途。 */
    private final ModelPurpose purpose;

    /** 是否已向客户端输出可见内容；跨 stream 调用共享。 */
    private final AtomicBoolean emitted = new AtomicBoolean();

    /** 已执行的 HTTP 尝试总数；跨 stream 调用共享。 */
    private final AtomicInteger attemptCount = new AtomicInteger();

    /** 首个可见输出使用的模型；用于 `chat.started` 与报告 modelKey。 */
    private final AtomicReference<ModelTarget> selected;

    /** 已做过同 key 重试的 key，避免无限重试。 */
    private final Set<UUID> retriedKeys = ConcurrentHashMap.newKeySet();

    /** 首字前预算的截止时间（epoch millis）；0 表示尚未开始。 */
    private final AtomicLong budgetDeadline = new AtomicLong();

    /**
     * 创建降级客户端。
     *
     * @param route 已解析路由链
     * @param routingProperties 降级预算
     * @param modelRegistry 模型解析
     * @param attemptRepository 尝试记录
     * @param healthService key 健康
     * @param auditService 审计
     * @param metrics 指标
     * @param clock UTC 时钟
     * @param tenantId 请求租户
     * @param invocation 调用句柄
     * @param traceId 追踪 ID
     * @param requestId 请求 ID，可为空
     * @param purpose 用途
     */
    public FailoverChatModel(
            ModelRoute route,
            ModelRoutingProperties routingProperties,
            ModelRegistry modelRegistry,
            ModelAttemptRepository attemptRepository,
            CredentialHealthService healthService,
            AuditService auditService,
            ModelMetrics metrics,
            Clock clock,
            TenantId tenantId,
            InvocationHandle invocation,
            String traceId,
            String requestId,
            ModelPurpose purpose) {
        this.route = Objects.requireNonNull(route, "route must not be null");
        this.routingProperties = Objects.requireNonNull(routingProperties, "routingProperties must not be null");
        this.modelRegistry = Objects.requireNonNull(modelRegistry, "modelRegistry must not be null");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository must not be null");
        this.healthService = Objects.requireNonNull(healthService, "healthService must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.invocation = Objects.requireNonNull(invocation, "invocation must not be null");
        this.traceId = Objects.requireNonNull(traceId, "traceId must not be null");
        this.requestId = requestId;
        this.purpose = Objects.requireNonNull(purpose, "purpose must not be null");
        this.selected = new AtomicReference<>(
                route.firstModel().map(RoutedModel::target).orElse(null));
    }

    /**
     * 返回上游模型标识（AgentScope 协议要求）。
     *
     * @return 当前选中/首个模型 key
     */
    @Override
    public String getModelName() {
        ModelTarget target = selected.get();
        return target == null ? "unavailable" : target.model().modelKey();
    }

    /**
     * 发起一次带降级的流式调用。
     *
     * @param messages 对话消息
     * @param tools 工具 schema
     * @param options 生成参数
     * @return 响应增量序列
     */
    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.<ChatResponse>create(sink -> run(sink, messages, tools, options), FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 返回首个可见输出使用的模型；尚未输出时为链首模型。
     *
     * @return 模型快照；链为空时为空
     */
    public ModelTarget selectedTarget() {
        return selected.get();
    }

    /**
     * 返回本调用已执行的尝试次数。
     *
     * @return 尝试次数
     */
    public int attemptCount() {
        return attemptCount.get();
    }

    /**
     * 是否已有可见输出。
     *
     * @return 已输出返回 true
     */
    public boolean emitted() {
        return emitted.get();
    }

    /**
     * 顺序执行尝试直到成功、中断或全链失败。
     *
     * @param sink 响应订阅者
     * @param messages 消息
     * @param tools 工具
     * @param options 生成参数
     */
    private void run(FluxSink<ChatResponse> sink, List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        // 模型流在 boundedElastic 上执行；TenantJdbc 要求当前线程有租户上下文，必须显式打开，
        // 不能依赖 Reactor/线程继承（TenantContext 明确禁止隐式传播）。
        try (TenantContext.TenantScope ignored = TenantContext.open(tenantId)) {
            runInTenant(sink, messages, tools, options);
        }
    }

    /**
     * 在租户作用域内执行尝试循环。
     *
     * @param sink 响应订阅者
     * @param messages 消息
     * @param tools 工具
     * @param options 生成参数
     */
    private void runInTenant(
            FluxSink<ChatResponse> sink, List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        List<AttemptCandidate> candidates = flatten(route);
        if (candidates.isEmpty()) {
            sink.error(chainUnavailable());
            return;
        }
        long now = clock.millis();
        budgetDeadline.compareAndSet(
                0, now + routingProperties.preFirstTokenBudget().toMillis());
        int maxAttempts = route.failoverEnabled() ? routingProperties.maxAttempts() : 1;
        int index = 0;
        try {
            while (index < candidates.size()) {
                if (sink.isCancelled()) {
                    return;
                }
                if (attemptCount.get() >= maxAttempts) {
                    break;
                }
                if (!emitted.get() && budgetDeadline.get() - clock.millis() <= 0) {
                    // 首字前总预算耗尽：不发出新的 HTTP 调用，返回 504（员工文案仍是统一失败文案）。
                    sink.error(new ModelException(
                            ModelException.TIMEOUT,
                            true,
                            ModelException.CHAIN_UNAVAILABLE_MESSAGE,
                            ModelFailureKind.TIMEOUT));
                    return;
                }
                AttemptCandidate candidate = candidates.get(index);
                AttemptResult result = runAttempt(sink, candidate, messages, tools, options);
                if (result.cancelled()) {
                    return;
                }
                if (result.error() == null) {
                    metrics.request(
                            candidate.model().target().provider().code(),
                            candidate.model().target().model().modelKey(),
                            "success");
                    healthService.onSuccess(candidate.credential());
                    // Flux.create 不会在 generator 正常返回时自动终止，必须显式 complete。
                    sink.complete();
                    return;
                }
                ModelException error = result.error();
                metrics.request(
                        candidate.model().target().provider().code(),
                        candidate.model().target().model().modelKey(),
                        "failure");
                if (emitted.get()) {
                    // 首字后：不重试、不重放、不切换，保留已输出内容。
                    sink.error(interrupted(error));
                    return;
                }
                FailureReason reason = ModelFailureClassifier.classify(error);
                healthService.onAttemptFailure(tenantId, candidate.model(), candidate.credential(), reason, traceId);

                boolean sameKeyRetry = ModelFailureClassifier.retrySameKeyAllowed(error)
                        && retriedKeys.add(candidate.credential().id());
                AttemptCandidate next = sameKeyRetry ? candidate : nextCandidate(candidates, index);
                boolean moreAllowed = route.failoverEnabled() && attemptCount.get() < maxAttempts;
                if (!moreAllowed || next == null) {
                    break;
                }
                // 审计 fail-closed：写失败时终止请求，不把“已切换”当成成功。
                auditService.modelFailoverTriggered(
                        tenantId,
                        traceId,
                        requestId,
                        purpose,
                        candidate.model().target().model().id(),
                        next.model().target().model().id(),
                        sameKeyRetry ? FailureReason.RETRY.code() : reason.code(),
                        attemptCount.get());
                metrics.failover(
                        candidate.model().target().model().id().toString(),
                        next.model().target().model().id().toString(),
                        sameKeyRetry ? FailureReason.RETRY.code() : reason.code());
                if (sameKeyRetry) {
                    backoff(attemptCount.get());
                } else {
                    index++;
                }
            }
            sink.error(chainUnavailable());
        } catch (RuntimeException ex) {
            if (!sink.isCancelled()) {
                sink.error(
                        ex instanceof ModelException modelError
                                ? modelError
                                : new ModelException(
                                        ModelException.CONFIGURATION,
                                        false,
                                        "model failover failed",
                                        ModelFailureKind.CONFIGURATION,
                                        ex));
            }
        }
    }

    /**
     * 执行一次 HTTP 尝试并把增量转发给订阅者。
     *
     * @param sink 订阅者
     * @param candidate 本次尝试的模型与 key
     * @param messages 消息
     * @param tools 工具
     * @param options 生成参数
     * @return 尝试结果
     */
    private AttemptResult runAttempt(
            FluxSink<ChatResponse> sink,
            AttemptCandidate candidate,
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options) {
        int attemptNo = attemptCount.incrementAndGet();
        long startedAt = clock.millis();
        try {
            attemptRepository.started(
                    tenantId,
                    invocation.id(),
                    attemptNo,
                    candidate.model().target().model().id(),
                    candidate.model().target().provider().id(),
                    candidate.credential().id());
        } catch (RuntimeException ex) {
            throw new ModelException(
                    ModelException.CONFIGURATION,
                    false,
                    "model attempt could not be recorded",
                    ModelFailureKind.CONFIGURATION,
                    ex);
        }
        Integer firstTokenMs = null;
        ResolvedModel resolved;
        try {
            resolved = modelRegistry.resolve(candidate.model().target(), candidate.credential());
        } catch (ModelException ex) {
            finishAttempt(attemptNo, ModelAttemptStatus.FAILED, ex.errorCode(), null);
            metrics.attemptDuration(Duration.ofMillis(0));
            return AttemptResult.failure(ex);
        }
        long timeoutMillis = timeoutMillis();
        Flux<ChatResponse> responses = modelRegistry.createClient(resolved).stream(messages, tools, options);
        if (timeoutMillis > 0) {
            responses = responses.timeout(Duration.ofMillis(timeoutMillis));
        }
        try {
            Iterator<ChatResponse> iterator = responses.toIterable().iterator();
            while (iterator.hasNext()) {
                ChatResponse response = iterator.next();
                if (sink.isCancelled()) {
                    finishAttempt(attemptNo, ModelAttemptStatus.CANCELLED, null, firstTokenMs);
                    metrics.attemptDuration(Duration.ofMillis(clock.millis() - startedAt));
                    return AttemptResult.cancel();
                }
                if (firstTokenMs == null && hasVisibleContent(response)) {
                    firstTokenMs = (int) Math.max(0, clock.millis() - startedAt);
                    if (emitted.compareAndSet(false, true)) {
                        selected.set(candidate.model().target());
                    }
                }
                sink.next(response);
            }
            if (sink.isCancelled()) {
                finishAttempt(attemptNo, ModelAttemptStatus.CANCELLED, null, firstTokenMs);
                metrics.attemptDuration(Duration.ofMillis(clock.millis() - startedAt));
                return AttemptResult.cancel();
            }
            if (selected.get() == null) {
                selected.set(candidate.model().target());
            }
            finishAttempt(attemptNo, ModelAttemptStatus.SUCCEEDED, null, firstTokenMs);
            metrics.attemptDuration(Duration.ofMillis(clock.millis() - startedAt));
            return AttemptResult.success();
        } catch (RuntimeException ex) {
            ModelException error = translate(ex);
            if (sink.isCancelled()) {
                finishAttempt(attemptNo, ModelAttemptStatus.CANCELLED, null, firstTokenMs);
                metrics.attemptDuration(Duration.ofMillis(clock.millis() - startedAt));
                return AttemptResult.cancel();
            }
            finishAttempt(attemptNo, ModelAttemptStatus.FAILED, error.errorCode(), firstTokenMs);
            metrics.attemptDuration(Duration.ofMillis(clock.millis() - startedAt));
            LOG.warn(
                    "model.attempt.failed purpose={} modelId={} providerCode={} attempt={} errorCode={} emitted={}",
                    purpose,
                    candidate.model().target().model().id(),
                    candidate.model().target().provider().code(),
                    attemptNo,
                    error.errorCode(),
                    emitted.get());
            return AttemptResult.failure(error);
        }
    }

    /**
     * 推进 attempt 终态；记录失败不掩盖原始模型错误。
     *
     * @param attemptNo 尝试序号
     * @param status 状态
     * @param errorCode 错误码
     * @param firstTokenMs 首字延迟
     */
    private void finishAttempt(int attemptNo, ModelAttemptStatus status, String errorCode, Integer firstTokenMs) {
        attemptRepository.finished(tenantId, invocation.id(), attemptNo, status, errorCode, firstTokenMs);
    }

    /**
     * 展平为尝试顺序：模型顺序 × key 优先级。
     *
     * @param modelRoute 路由链
     * @return 有序候选
     */
    private static List<AttemptCandidate> flatten(ModelRoute modelRoute) {
        List<AttemptCandidate> candidates = new ArrayList<>();
        for (RoutedModel model : modelRoute.models()) {
            for (ModelCredential credential : model.credentials()) {
                candidates.add(new AttemptCandidate(model, credential));
            }
        }
        return candidates;
    }

    /**
     * 返回下一个候选。
     *
     * @param candidates 候选列表
     * @param index 当前下标
     * @return 下一个候选；没有时为空
     */
    private static AttemptCandidate nextCandidate(List<AttemptCandidate> candidates, int index) {
        int next = index + 1;
        return next < candidates.size() ? candidates.get(next) : null;
    }

    /**
     * 计算本次尝试的首字超时。
     *
     * @return 毫秒；0 表示预算已耗尽
     */
    private long timeoutMillis() {
        long firstToken = routingProperties.firstTokenTimeout().toMillis();
        if (emitted.get()) {
            return firstToken;
        }
        return Math.min(firstToken, Math.max(0, budgetDeadline.get() - clock.millis()));
    }

    /**
     * 把异常翻译为模型错误。
     *
     * @param ex 运行时异常
     * @return 模型错误
     */
    private static ModelException translate(RuntimeException ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ModelException modelError) {
                return modelError;
            }
            if (current instanceof TimeoutException) {
                return new ModelException(
                        ModelException.TIMEOUT, true, "model attempt timed out", ModelFailureKind.TIMEOUT, ex);
            }
            current = current.getCause();
        }
        return new ModelException(
                ModelException.INVALID_RESPONSE, false, "model stream failed", ModelFailureKind.INVALID_RESPONSE, ex);
    }

    /**
     * 首字后失败的中断错误：保留已输出内容，客户端可重试。
     *
     * @param error 原始错误
     * @return 502 或 504 中断错误
     */
    private static ModelException interrupted(ModelException error) {
        String code = ModelException.TIMEOUT.equals(error.errorCode())
                ? ModelException.TIMEOUT
                : ModelException.INVALID_RESPONSE;
        return new ModelException(
                code, true, ModelException.STREAM_INTERRUPTED_MESSAGE, ModelFailureKind.INVALID_RESPONSE);
    }

    /**
     * 全链失败错误。
     *
     * @return 503 统一文案
     */
    private static ModelException chainUnavailable() {
        return new ModelException(
                ModelException.UNAVAILABLE,
                true,
                ModelException.CHAIN_UNAVAILABLE_MESSAGE,
                ModelFailureKind.CONFIGURATION);
    }

    /**
     * 判断响应是否含客户端可见文本增量。
     *
     * <p>ADR-0021 §1 把“首字”定义为第一个非空 {@code TextBlockDeltaEvent}；工具调用不是可见
     * 首字，因此不锁定降级。工具审批在 Agent 层挂起，重放最多重新生成一次工具请求，不会
     * 执行两次副作用。
     *
     * @param response 响应
     * @return 含非空文本块时返回 true
     */
    private static boolean hasVisibleContent(ChatResponse response) {
        List<ContentBlock> content = response.getContent();
        if (content == null || content.isEmpty()) {
            return false;
        }
        for (ContentBlock block : content) {
            if (block instanceof TextBlock text
                    && text.getText() != null
                    && !text.getText().isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 同 key 重试退避：按尝试序号取延迟并乘 0.5–1.0 抖动；0 延迟直接返回。
     *
     * @param attempt 当前尝试序号
     */
    private void backoff(int attempt) {
        List<Duration> delays = routingProperties.retryDelays();
        Duration base = delays.get(Math.min(Math.max(0, attempt - 1), delays.size() - 1));
        if (base.isZero()) {
            return;
        }
        long jittered =
                (long) (base.toMillis() * (0.5 + ThreadLocalRandom.current().nextDouble() * 0.5));
        try {
            Thread.sleep(Math.max(0, jittered));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ModelException(
                    ModelException.TIMEOUT, false, "model retry backoff interrupted", ModelFailureKind.TIMEOUT, ex);
        }
    }

    /**
     * 一次尝试的候选。
     *
     * @param model 模型
     * @param credential key
     */
    private record AttemptCandidate(RoutedModel model, ModelCredential credential) {}

    /**
     * 一次尝试的结果。
     *
     * @param error 失败错误；成功时为空
     * @param cancelled 是否取消
     */
    private record AttemptResult(ModelException error, boolean cancelled) {

        /** 成功结果。 */
        private static AttemptResult success() {
            return new AttemptResult(null, false);
        }

        /** 失败结果。 */
        private static AttemptResult failure(ModelException error) {
            return new AttemptResult(error, false);
        }

        /** 取消结果。 */
        private static AttemptResult cancel() {
            return new AttemptResult(null, true);
        }
    }
}
