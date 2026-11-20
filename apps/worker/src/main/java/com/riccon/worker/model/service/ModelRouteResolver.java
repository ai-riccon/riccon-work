package com.riccon.worker.model.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.model.ModelPurpose;
import com.riccon.worker.audit.service.AuditService;
import com.riccon.worker.model.domain.GatewaySwitches;
import com.riccon.worker.model.domain.ModelCatalogEntry;
import com.riccon.worker.model.domain.ModelCredential;
import com.riccon.worker.model.domain.ModelException;
import com.riccon.worker.model.domain.ModelFailureKind;
import com.riccon.worker.model.domain.ModelRoute;
import com.riccon.worker.model.domain.ModelRoutingMode;
import com.riccon.worker.model.domain.ModelTarget;
import com.riccon.worker.model.domain.RoutedModel;
import com.riccon.worker.model.domain.RoutingChainEntry;
import com.riccon.worker.model.repository.ModelRepository;
import com.riccon.worker.model.repository.ModelRoutingRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 按用途解析模型路由链（ADR-0021 §4.1）。
 *
 * <p>解析顺序：
 *
 * <ol>
 *   <li>读取生效开关（ENV > DB）；
 *   <li>BASELINE：M02 语义（最早创建的 ACTIVE 模型），链长为 1；
 *   <li>ROUTED：按 position 读取 `model_routing_entry`，过滤启停/协议/端点允许列表，再读取平台 key 池；
 *   <li>链为空：`RICCON-MODEL-503` + 指标 + 失败审计，不发起任何出网。
 * </ol>
 *
 * <p><strong>迁移兼容</strong>：V11 迁移会为 CHAT/REPORT 各播种一条链；如果 `model_routing_entry`
 * 完全为空且链从未被管理员保存过（`updated_by IS NULL`），说明当前库只有“迁移基线/测试夹具”的
 * 单模型目录，按 M02 单模型语义兼容。一旦链被保存过，空链或全部被过滤都稳定失败，绝不回退到
 * 链外模型（ADR-0021 §4.1/§5.6）。基线与兼容路径同样执行端点允许列表与平台 key 池过滤。
 */
@Component
public class ModelRouteResolver {

    /** 日志；只记录用途与过滤结果，不记录地址。 */
    private static final Logger LOG = LoggerFactory.getLogger(ModelRouteResolver.class);

    /** 路由链仓储。 */
    private final ModelRoutingRepository routingRepository;

    /** 模型/凭据仓储。 */
    private final ModelRepository modelRepository;

    /** 开关服务。 */
    private final ModelGatewaySwitchService switchService;

    /** 端点守卫。 */
    private final ModelEndpointGuard endpointGuard;

    /** 模型解析（目录→快照）。 */
    private final ModelRegistry modelRegistry;

    /** 指标。 */
    private final ModelMetrics metrics;

    /** 审计。 */
    private final AuditService auditService;

    /**
     * 创建路由解析器。
     *
     * @param routingRepository 路由链仓储
     * @param modelRepository 模型/凭据仓储
     * @param switchService 开关服务
     * @param endpointGuard 端点守卫
     * @param modelRegistry 模型解析
     * @param metrics 指标
     * @param auditService 审计
     */
    public ModelRouteResolver(
            ModelRoutingRepository routingRepository,
            ModelRepository modelRepository,
            ModelGatewaySwitchService switchService,
            ModelEndpointGuard endpointGuard,
            ModelRegistry modelRegistry,
            ModelMetrics metrics,
            AuditService auditService) {
        this.routingRepository = Objects.requireNonNull(routingRepository, "routingRepository must not be null");
        this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository must not be null");
        this.switchService = Objects.requireNonNull(switchService, "switchService must not be null");
        this.endpointGuard = Objects.requireNonNull(endpointGuard, "endpointGuard must not be null");
        this.modelRegistry = Objects.requireNonNull(modelRegistry, "modelRegistry must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
    }

    /**
     * 解析一次运行的路由链。
     *
     * @param tenantId 请求租户（用于失败审计）
     * @param purpose 用途
     * @param traceId 追踪 ID
     * @param requestId 请求 ID，可为空
     * @return 非空路由链
     * @throws ModelException 开关非法或链为空
     */
    public ModelRoute resolve(TenantId tenantId, ModelPurpose purpose, String traceId, String requestId) {
        GatewaySwitches switches = switchService.effective();
        if (switches.routingMode() == ModelRoutingMode.BASELINE) {
            List<RoutedModel> models = baselineChain();
            if (models.isEmpty()) {
                throw chainEmpty(tenantId, purpose, traceId, requestId);
            }
            return new ModelRoute(purpose, ModelRoutingMode.BASELINE, switches.failoverEnabled(), false, models);
        }
        List<RoutingChainEntry> entries = routingRepository.entries(purpose);
        if (entries.isEmpty() && !routingRepository.configured(purpose)) {
            // 迁移基线/测试夹具：没有链且从未保存过链，使用 M02 单模型语义。
            LOG.debug("model.routing.chain_missing purpose={} fallback=baseline", purpose);
            List<RoutedModel> models = baselineChain();
            if (models.isEmpty()) {
                throw chainEmpty(tenantId, purpose, traceId, requestId);
            }
            return new ModelRoute(purpose, ModelRoutingMode.ROUTED, switches.failoverEnabled(), false, models);
        }
        List<RoutedModel> models = new ArrayList<>(entries.size());
        for (RoutingChainEntry entry : entries) {
            ModelTarget target;
            try {
                target = modelRegistry.target(new ModelCatalogEntry(entry.model(), entry.provider()));
            } catch (RuntimeException ex) {
                LOG.warn(
                        "model.routing.entry_skipped purpose={} position={} reason=ENDPOINT_INVALID",
                        purpose,
                        entry.position());
                continue;
            }
            if (!endpointGuard.isRoutable(entry.provider())) {
                LOG.warn(
                        "model.routing.entry_skipped purpose={} position={} reason=NOT_ROUTABLE",
                        purpose,
                        entry.position());
                continue;
            }
            List<ModelCredential> credentials =
                    modelRepository.findPlatformPool(entry.provider().id());
            if (credentials.isEmpty()) {
                LOG.warn(
                        "model.routing.entry_skipped purpose={} position={} reason=NO_ACTIVE_KEY",
                        purpose,
                        entry.position());
                continue;
            }
            models.add(new RoutedModel(target, credentials));
        }
        if (models.isEmpty()) {
            throw chainEmpty(tenantId, purpose, traceId, requestId);
        }
        return new ModelRoute(purpose, ModelRoutingMode.ROUTED, switches.failoverEnabled(), true, models);
    }

    /**
     * 构造 M02 单模型兼容链。
     *
     * @return 至多一个可用模型
     */
    private List<RoutedModel> baselineChain() {
        java.util.Optional<ModelCatalogEntry> catalog = modelRepository.findActiveCatalog();
        if (catalog.isEmpty()) {
            return List.of();
        }
        ModelCatalogEntry entry = catalog.get();
        if (!endpointGuard.isRoutable(entry.provider())) {
            return List.of();
        }
        ModelTarget target = modelRegistry.target(entry);
        List<ModelCredential> credentials =
                modelRepository.findPlatformPool(entry.provider().id());
        if (credentials.isEmpty()) {
            return List.of();
        }
        return List.of(new RoutedModel(target, credentials));
    }

    /**
     * 记录链为空并构造稳定失败。
     *
     * @param tenantId 请求租户
     * @param purpose 用途
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 503 错误（由调用方抛出）
     */
    private ModelException chainEmpty(TenantId tenantId, ModelPurpose purpose, String traceId, String requestId) {
        metrics.chainEmpty(purpose);
        try {
            auditService.modelUnavailable(tenantId, traceId, requestId, purpose, ModelException.UNAVAILABLE);
        } catch (RuntimeException ex) {
            // 审计 fail-closed：写失败时请求失败，不返回“模型不可用”掩盖审计故障。
            return new ModelException(
                    ModelException.CONFIGURATION,
                    false,
                    "model chain empty audit could not be written",
                    ModelFailureKind.CONFIGURATION,
                    ex);
        }
        LOG.warn("model.routing.chain_empty purpose={}", purpose);
        return new ModelException(
                ModelException.UNAVAILABLE,
                true,
                ModelException.CHAIN_UNAVAILABLE_MESSAGE,
                ModelFailureKind.CONFIGURATION);
    }
}
