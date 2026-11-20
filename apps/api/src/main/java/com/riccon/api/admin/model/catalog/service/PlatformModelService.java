package com.riccon.api.admin.model.catalog.service;

import com.riccon.api.admin.model.catalog.domain.AdminModelRow;
import com.riccon.api.admin.model.catalog.dto.AdminModelPageResponse;
import com.riccon.api.admin.model.catalog.dto.AdminModelResponse;
import com.riccon.api.admin.model.catalog.repository.PlatformModelRepository;
import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.PlatformTenant;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.model.catalog.BillingProperties;
import com.riccon.platform.common.model.EndpointEnvironmentLabel;
import com.riccon.platform.common.model.ManagedEndpointRegistry;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台模型目录服务。
 *
 * <p>目录只读并带价格快照；启停是 M04 唯一模型写操作，审计 {@code model.updated}，审计失败即回滚
 * （ADR-0021 §7.1/§7.4）。价格不参与任何写操作，历史调用价格快照不会被改写（ADR-0022 §2.1）。
 */
@Service
public class PlatformModelService {

    /** 模型目录仓储。 */
    private final PlatformModelRepository modelRepository;

    /** 部署端点允许列表。 */
    private final ManagedEndpointRegistry endpointRegistry;

    /** 部署币种。 */
    private final BillingProperties billingProperties;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建平台模型目录服务。
     *
     * @param modelRepository 模型目录仓储
     * @param endpointRegistry 部署端点允许列表
     * @param billingProperties 部署币种
     * @param auditService 审计写入
     * @param clock 可注入时钟
     */
    public PlatformModelService(
            PlatformModelRepository modelRepository,
            ManagedEndpointRegistry endpointRegistry,
            BillingProperties billingProperties,
            AuditService auditService,
            Clock clock) {
        this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository must not be null");
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry must not be null");
        this.billingProperties = Objects.requireNonNull(billingProperties, "billingProperties must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 分页列出平台模型目录。
     *
     * @param page 分页参数
     * @return 模型分页
     */
    @Transactional(readOnly = true)
    public AdminModelPageResponse listModels(PageQuery page) {
        List<AdminModelResponse> all =
                modelRepository.findAll().stream().map(this::toResponse).toList();
        int total = all.size();
        int from = Math.min(page.offset(), total);
        int to = Math.min(from + page.size(), total);
        return new AdminModelPageResponse(
                page.page(), page.size(), total, page.totalPages(total), List.copyOf(all.subList(from, to)));
    }

    /**
     * 启用或停用模型。
     *
     * <p>停用模型保留在路由链中，运行时由 Worker 跳过；下一次请求生效（ADR-0021 §4.1）。审计与状态更新
     * 在同一事务内，审计写入失败时保存失败。
     *
     * @param modelId 模型 ID
     * @param status 目标状态（ACTIVE/DISABLED，控制器已校验）
     * @param actor 平台管理员
     * @param meta 请求元数据
     * @return 更新后的模型
     * @throws NotFoundException 模型不存在
     */
    @Transactional
    public AdminModelResponse updateStatus(UUID modelId, String status, RicconPrincipal actor, RequestMeta meta) {
        AdminModelRow existing = modelRepository.findById(modelId).orElseThrow(() -> new NotFoundException("model"));
        modelRepository.updateStatus(modelId, status);
        appendAudit(actor, modelId, existing.providerId(), status, meta);
        AdminModelRow updated = modelRepository.findById(modelId).orElseThrow(() -> new NotFoundException("model"));
        return toResponse(updated);
    }

    /**
     * 写 {@code model.updated} 审计；事件归属 platform 租户，details 只含模型 ID/状态/provider ID。
     *
     * @param actor 平台管理员
     * @param modelId 模型 ID
     * @param providerId 供应商 ID
     * @param status 新状态
     * @param meta 请求元数据
     */
    private void appendAudit(RicconPrincipal actor, UUID modelId, UUID providerId, String status, RequestMeta meta) {
        TenantContext.runWithTenant(
                PlatformTenant.TENANT_ID,
                () -> auditService.append(AuditEvent.create(
                        clock.instant(),
                        PlatformTenant.ID,
                        AuditActorType.USER,
                        actor.userId().toString(),
                        AuditActions.MODEL_UPDATED,
                        AuditTargetType.MODEL,
                        modelId.toString(),
                        AuditOutcome.SUCCESS,
                        meta.traceId(),
                        meta.requestId(),
                        null,
                        Map.of(
                                "modelId", modelId.toString(),
                                "status", status,
                                "providerId", providerId.toString()))));
    }

    /**
     * 映射目录行；价格只读、员工不可见。
     *
     * @param row 目录行
     * @return 平台模型响应
     */
    private AdminModelResponse toResponse(AdminModelRow row) {
        String environmentLabel = endpointRegistry
                .labelFor(row.providerCode())
                .map(EndpointEnvironmentLabel::name)
                .orElse(EndpointEnvironmentLabel.PRODUCTION.name());
        boolean priced = row.inputPricePer1M() != null && row.outputPricePer1M() != null;
        return new AdminModelResponse(
                row.modelId().toString(),
                row.modelKey(),
                row.displayName(),
                row.providerId().toString(),
                row.providerCode(),
                environmentLabel,
                row.contextWindow(),
                row.maxOutputTokens(),
                row.capabilities(),
                formatPrice(row.inputPricePer1M()),
                formatPrice(row.outputPricePer1M()),
                billingProperties.currency(),
                priced ? "PRICED" : "UNPRICED",
                row.modelStatus());
    }

    /**
     * 把价格转为 6 位小数字符串，避免 JSON 浮点精度损失。
     *
     * @param value DECIMAL(12,6) 价格
     * @return 6 位小数字符串；空值返回 null
     */
    private static String formatPrice(BigDecimal value) {
        return value == null ? null : value.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }
}
