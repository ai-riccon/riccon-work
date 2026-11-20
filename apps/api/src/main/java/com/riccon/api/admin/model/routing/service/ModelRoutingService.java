package com.riccon.api.admin.model.routing.service;

import com.riccon.api.admin.model.catalog.domain.AdminModelRow;
import com.riccon.api.admin.model.catalog.repository.PlatformModelRepository;
import com.riccon.api.admin.model.routing.domain.ModelRoutingRules;
import com.riccon.api.admin.model.routing.domain.ModelRoutingValidationException;
import com.riccon.api.admin.model.routing.domain.ModelRoutingVersionConflictException;
import com.riccon.api.admin.model.routing.dto.ModelRoutingEntryResponse;
import com.riccon.api.admin.model.routing.dto.ModelRoutingListResponse;
import com.riccon.api.admin.model.routing.dto.ModelRoutingResponse;
import com.riccon.api.admin.model.routing.repository.PlatformModelRoutingRepository;
import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.identity.domain.PlatformTenant;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.model.routing.domain.RoutingChain;
import com.riccon.api.model.routing.domain.RoutingEntry;
import com.riccon.api.model.routing.repository.ModelRoutingReadRepository;
import com.riccon.platform.common.model.EndpointEnvironmentLabel;
import com.riccon.platform.common.model.ManagedEndpointRegistry;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.model.ModelPurpose;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台模型路由服务。
 *
 * <p>读路径返回 CHAT/REPORT 两条链，写入路径整链替换并在同一事务内写 {@code model.routing.updated} 审计。
 * {@code version} 条件更新是乐观锁最终防线：并发保存只有一个成功，另一个返回 409。读模型不缓存，保存后
 * 下一次请求即读新链；停用模型保留在链中但运行时跳过（ADR-0021 §4.1–§4.4）。
 */
@Service
public class ModelRoutingService {

    /** 对外时间统一使用的团队偏移（Asia/Shanghai，UTC+08:00）。 */
    private static final ZoneOffset TEAM_OFFSET = ZoneOffset.ofHours(8);

    /** 路由链读仓储（生效读模型）。 */
    private final ModelRoutingReadRepository readRepository;

    /** 路由链写仓储。 */
    private final PlatformModelRoutingRepository writeRepository;

    /** 平台模型目录仓储；只读校验模型是否存在、provider 是否可路由。 */
    private final PlatformModelRepository modelRepository;

    /** 部署端点允许列表。 */
    private final ManagedEndpointRegistry endpointRegistry;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建模型路由服务。
     *
     * @param readRepository 路由链读仓储
     * @param writeRepository 路由链写仓储
     * @param modelRepository 平台模型目录仓储
     * @param endpointRegistry 部署端点允许列表
     * @param auditService 审计写入
     * @param clock 可注入时钟
     */
    public ModelRoutingService(
            ModelRoutingReadRepository readRepository,
            PlatformModelRoutingRepository writeRepository,
            PlatformModelRepository modelRepository,
            ManagedEndpointRegistry endpointRegistry,
            AuditService auditService,
            Clock clock) {
        this.readRepository = Objects.requireNonNull(readRepository, "readRepository must not be null");
        this.writeRepository = Objects.requireNonNull(writeRepository, "writeRepository must not be null");
        this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository must not be null");
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 读取 CHAT/REPORT 两条路由链。
     *
     * @return 两条链，顺序为 CHAT、REPORT
     * @throws IllegalStateException 迁移未种子化链头
     */
    @Transactional(readOnly = true)
    public ModelRoutingListResponse listChains() {
        Map<ModelPurpose, RoutingChain> byPurpose = new EnumMap<>(ModelPurpose.class);
        for (RoutingChain chain : readRepository.findAll()) {
            byPurpose.put(chain.purpose(), chain);
        }
        List<ModelRoutingResponse> items = new ArrayList<>(ModelPurpose.values().length);
        for (ModelPurpose purpose : ModelPurpose.values()) {
            RoutingChain chain = byPurpose.get(purpose);
            if (chain == null) {
                // V11 为两种用途各插入一条链头；缺失说明迁移/修复被绕过，不能返回不完整契约。
                throw new IllegalStateException("model routing chain is not seeded: " + purpose);
            }
            items.add(toResponse(chain));
        }
        return new ModelRoutingListResponse(List.copyOf(items));
    }

    /**
     * 整链保存指定用途的路由链。
     *
     * <p>校验顺序：版本与模型列表 → 模型存在且 provider 可路由 → 乐观锁 → 整链替换 → 审计。任何一步失败
     * 都让事务回滚，不留下“已保存但无审计”的中间状态（ADR-0021 §4.2–§4.4）。
     *
     * @param purpose 用途
     * @param requestedVersion 客户端读到的版本
     * @param rawModelIds 有序模型 ID 字符串
     * @param actor 平台管理员
     * @param meta 请求元数据
     * @return 保存后重新读取的链
     * @throws ModelRoutingValidationException 字段级 400 条件
     * @throws ModelRoutingVersionConflictException 版本冲突
     */
    @Transactional
    public ModelRoutingResponse saveChain(
            ModelPurpose purpose,
            Integer requestedVersion,
            List<String> rawModelIds,
            RicconPrincipal actor,
            RequestMeta meta) {
        int version = ModelRoutingRules.requireVersion(requestedVersion);
        List<UUID> modelIds = ModelRoutingRules.parseModelIds(rawModelIds);
        for (UUID modelId : modelIds) {
            AdminModelRow model = modelRepository
                    .findById(modelId)
                    .orElseThrow(() -> new ModelRoutingValidationException(
                            "modelIds", "UNKNOWN_MODEL", "modelIds contains an unknown model"));
            ModelRoutingRules.requireRoutable(model, endpointRegistry);
        }

        RoutingChain current = readRepository
                .find(purpose)
                .orElseThrow(() -> new IllegalStateException("model routing chain is not seeded: " + purpose));
        if (current.version() != version) {
            throw new ModelRoutingVersionConflictException(current.version());
        }

        Instant now = clock.instant();
        boolean advanced = writeRepository.advanceVersion(purpose.name(), version, actor.userId(), now);
        if (!advanced) {
            // 并发保存已推进版本：重读当前版本用于 409 说明，不做自动重试或覆盖。
            int latestVersion =
                    readRepository.find(purpose).map(RoutingChain::version).orElse(current.version());
            throw new ModelRoutingVersionConflictException(latestVersion);
        }
        writeRepository.replaceEntries(purpose.name(), modelIds);

        int newVersion = version + 1;
        appendAudit(actor, purpose, modelIds, newVersion, version, meta, now);
        return readRepository
                .find(purpose)
                .map(this::toResponse)
                .orElseThrow(() -> new IllegalStateException("model routing chain disappeared after save: " + purpose));
    }

    /**
     * 写 {@code model.routing.updated} 审计。
     *
     * <p>事件归属 platform 租户，目标为 {@code MODEL_ROUTING} + purpose；details 只含 purpose、有序
     * modelIds 数组、新旧版本与结果，不写 baseUrl、provider code 或 key（ADR-0021 §4.4/§7.4）。
     *
     * @param actor 平台管理员
     * @param purpose 用途
     * @param modelIds 有序模型 ID
     * @param newVersion 保存后的版本
     * @param previousVersion 保存前版本
     * @param meta 请求元数据
     * @param occurredAt 事件时间
     */
    private void appendAudit(
            RicconPrincipal actor,
            ModelPurpose purpose,
            List<UUID> modelIds,
            int newVersion,
            int previousVersion,
            RequestMeta meta,
            Instant occurredAt) {
        List<String> auditedModelIds = modelIds.stream().map(UUID::toString).toList();
        TenantContext.runWithTenant(
                PlatformTenant.TENANT_ID,
                () -> auditService.append(AuditEvent.create(
                        occurredAt,
                        PlatformTenant.ID,
                        AuditActorType.USER,
                        actor.userId().toString(),
                        AuditActions.MODEL_ROUTING_UPDATED,
                        AuditTargetType.MODEL_ROUTING,
                        purpose.name(),
                        AuditOutcome.SUCCESS,
                        meta.traceId(),
                        meta.requestId(),
                        null,
                        Map.of(
                                "purpose", purpose.name(),
                                "modelIds", auditedModelIds,
                                "version", newVersion,
                                "previousVersion", previousVersion,
                                "result", "SUCCESS"))));
    }

    /**
     * 映射读模型为响应。
     *
     * @param chain 路由链
     * @return 路由链响应
     */
    private ModelRoutingResponse toResponse(RoutingChain chain) {
        List<ModelRoutingEntryResponse> entries =
                chain.entries().stream().map(this::toEntryResponse).toList();
        return new ModelRoutingResponse(
                chain.purpose().name(),
                chain.version(),
                entries,
                chain.updatedAt().atOffset(TEAM_OFFSET),
                chain.updatedBy());
    }

    /**
     * 映射链目；端点标签由部署允许列表派生，缺失时按安全默认 PRODUCTION 展示。
     *
     * @param entry 链目
     * @return 链目响应
     */
    private ModelRoutingEntryResponse toEntryResponse(RoutingEntry entry) {
        String environmentLabel = endpointRegistry
                .labelFor(entry.providerCode())
                .map(EndpointEnvironmentLabel::name)
                .orElse(EndpointEnvironmentLabel.PRODUCTION.name());
        return ModelRoutingEntryResponse.of(
                entry.position(), entry.modelId(), entry.displayName(), entry.status(), environmentLabel);
    }
}
