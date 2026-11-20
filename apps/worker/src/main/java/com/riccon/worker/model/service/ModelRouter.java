package com.riccon.worker.model.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.model.ModelPurpose;
import com.riccon.worker.audit.service.AuditService;
import com.riccon.worker.model.config.ModelRoutingProperties;
import com.riccon.worker.model.domain.ModelRoute;
import com.riccon.worker.model.repository.ModelAttemptRepository;
import com.riccon.worker.model.service.ModelInvocationService.InvocationHandle;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Worker 模型网关装配入口。
 *
 * <p>调用方只依赖本类：先 {@link #resolve} 得到路由链，再用链首模型开始调用记录，最后
 * {@link #createModel} 构造带降级的 ChatModel。这样 chat 流、报告工具阶段与生成阶段共享同一
 * 状态机与预算，不会各自复制一套重试逻辑。
 */
@Component
public class ModelRouter {

    /** 路由解析。 */
    private final ModelRouteResolver routeResolver;

    /** 降级预算。 */
    private final ModelRoutingProperties routingProperties;

    /** 模型解析。 */
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

    /**
     * 创建模型网关。
     *
     * @param routeResolver 路由解析
     * @param routingProperties 降级预算
     * @param modelRegistry 模型解析
     * @param attemptRepository 尝试记录
     * @param healthService key 健康
     * @param auditService 审计
     * @param metrics 指标
     * @param clock UTC 时钟
     */
    public ModelRouter(
            ModelRouteResolver routeResolver,
            ModelRoutingProperties routingProperties,
            ModelRegistry modelRegistry,
            ModelAttemptRepository attemptRepository,
            CredentialHealthService healthService,
            AuditService auditService,
            ModelMetrics metrics,
            Clock clock) {
        this.routeResolver = Objects.requireNonNull(routeResolver, "routeResolver must not be null");
        this.routingProperties = Objects.requireNonNull(routingProperties, "routingProperties must not be null");
        this.modelRegistry = Objects.requireNonNull(modelRegistry, "modelRegistry must not be null");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository must not be null");
        this.healthService = Objects.requireNonNull(healthService, "healthService must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 解析指定用途的路由链。
     *
     * @param tenantId 请求租户
     * @param purpose 用途
     * @param traceId 追踪 ID
     * @param requestId 请求 ID，可为空
     * @return 非空路由链
     */
    public ModelRoute resolve(TenantId tenantId, ModelPurpose purpose, String traceId, String requestId) {
        return routeResolver.resolve(tenantId, purpose, traceId, requestId);
    }

    /**
     * 创建带降级的 ChatModel。
     *
     * @param route 已解析链
     * @param tenantId 请求租户
     * @param invocation 调用句柄
     * @param traceId 追踪 ID
     * @param requestId 请求 ID，可为空
     * @param purpose 用途
     * @return 降级 ChatModel
     */
    public FailoverChatModel createModel(
            ModelRoute route,
            TenantId tenantId,
            InvocationHandle invocation,
            String traceId,
            String requestId,
            ModelPurpose purpose) {
        return new FailoverChatModel(
                route,
                routingProperties,
                modelRegistry,
                attemptRepository,
                healthService,
                auditService,
                metrics,
                clock,
                tenantId,
                invocation,
                traceId,
                requestId,
                purpose);
    }
}
