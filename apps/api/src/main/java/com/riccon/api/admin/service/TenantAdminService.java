package com.riccon.api.admin.service;

import com.riccon.api.admin.repository.AdminRepository;
import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.error.BadRequestException;
import com.riccon.api.error.ConflictException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.PlatformTenant;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.domain.RoleDefinition;
import com.riccon.api.identity.domain.RoleDefinitions;
import com.riccon.api.identity.domain.TenantRecord;
import com.riccon.api.identity.domain.TenantStatus;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.identity.dto.TenantPageResponse;
import com.riccon.api.identity.dto.TenantSummaryResponse;
import com.riccon.api.identity.dto.TenantUpdateRequest;
import com.riccon.api.web.Validation;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台租户管理。
 *
 * <p>仅平台管理员可调用（URL 与权限注解双重校验）。创建租户时同步复制系统角色与权限映射；所有变更写审计，
 * 审计失败使事务回滚（ADR-0007）。平台级事件租户固定为 {@code platform}，目标租户写入 target/id 与 details。
 */
@Service
public class TenantAdminService {

    /** 租户代码格式，与 OpenAPI {@code TenantCreateRequest.id} 一致。 */
    private static final Pattern TENANT_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");

    /** 新租户默认保留天数。 */
    private static final int DEFAULT_RETENTION_DAYS = 180;

    /** 最小保留天数。 */
    private static final int MIN_RETENTION_DAYS = 30;

    /** 最大保留天数。 */
    private static final int MAX_RETENTION_DAYS = 3650;

    /** 平台管理仓储。 */
    private final AdminRepository adminRepository;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建租户管理服务。
     *
     * @param adminRepository 平台管理仓储
     * @param auditService 审计写入
     * @param clock 可注入时钟
     */
    public TenantAdminService(AdminRepository adminRepository, AuditService auditService, Clock clock) {
        this.adminRepository = Objects.requireNonNull(adminRepository, "adminRepository");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 分页列出平台租户。
     *
     * @param page 分页参数
     * @return 租户分页
     */
    @Transactional(readOnly = true)
    public TenantPageResponse listTenants(PageQuery page) {
        long total = adminRepository.countTenants();
        List<TenantSummaryResponse> items = adminRepository.listTenants(page.size(), page.offset()).stream()
                .map(TenantAdminService::toSummary)
                .toList();
        return new TenantPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 创建租户与系统角色。
     *
     * @param tenantId 租户代码
     * @param name 展示名
     * @param actor 平台管理员
     * @param meta 请求元数据
     * @return 新租户摘要
     * @throws ConflictException 租户代码已存在
     */
    @Transactional
    public TenantSummaryResponse createTenant(String tenantId, String name, RicconPrincipal actor, RequestMeta meta) {
        String code = Validation.requirePattern(tenantId, "id", TENANT_CODE, 64);
        String displayName = Validation.requireText(name, "name", 1, 200);
        if (PlatformTenant.ID.equals(code)) {
            throw new ConflictException("tenant id is reserved");
        }
        if (adminRepository.findTenant(code).isPresent()) {
            throw new ConflictException("tenant already exists");
        }
        Instant now = clock.instant();
        TenantRecord tenant =
                new TenantRecord(code, displayName, TenantStatus.ACTIVE, "standard", DEFAULT_RETENTION_DAYS, now);
        try {
            adminRepository.insertTenant(tenant);
            TenantId targetTenant = TenantId.of(code);
            for (RoleDefinition role : RoleDefinitions.tenantSystemRoles()) {
                adminRepository.createRole(targetTenant, role);
            }
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("tenant already exists");
        }
        appendAudit(
                actor,
                PlatformTenant.ID,
                AuditActions.TENANT_CREATED,
                AuditTargetType.TENANT,
                code,
                AuditOutcome.SUCCESS,
                meta,
                Map.of("plan", "standard"));
        // 返回数据库中的 created_at，避免响应时间与持久化值出现毫秒级差异。
        return toSummary(adminRepository.findTenant(code).orElse(tenant));
    }

    /**
     * 更新租户状态或保留策略。
     *
     * @param tenantId 目标租户
     * @param request 更新请求
     * @param actor 平台管理员
     * @param meta 请求元数据
     * @return 更新后的租户摘要
     */
    @Transactional
    public TenantSummaryResponse updateTenant(
            String tenantId, TenantUpdateRequest request, RicconPrincipal actor, RequestMeta meta) {
        String code = Validation.requirePattern(tenantId, "tenantId", TENANT_CODE, 64);
        if (request.status() == null && request.retentionDays() == null) {
            throw new BadRequestException("at least one of status or retentionDays is required");
        }
        Integer retentionDays = Validation.requireRange(
                request.retentionDays(), "retentionDays", MIN_RETENTION_DAYS, MAX_RETENTION_DAYS);
        TenantRecord existing = adminRepository.findTenant(code).orElseThrow(() -> new NotFoundException("tenant"));
        if (PlatformTenant.ID.equals(code) && request.status() == TenantStatus.SUSPENDED) {
            throw new ConflictException("platform tenant cannot be suspended");
        }
        Instant now = clock.instant();
        adminRepository.updateTenant(code, request.status(), retentionDays, now);
        TenantRecord updated = adminRepository.findTenant(code).orElseThrow(() -> new NotFoundException("tenant"));
        Map<String, Object> details = new LinkedHashMap<>();
        if (request.status() != null) {
            details.put("status", request.status().name());
        }
        if (retentionDays != null) {
            details.put("retentionDays", retentionDays);
        }
        appendAudit(
                actor,
                PlatformTenant.ID,
                com.riccon.api.audit.domain.AdditionalAuditActions.TENANT_UPDATED,
                AuditTargetType.TENANT,
                code,
                AuditOutcome.SUCCESS,
                meta,
                details);
        return toSummary(updated);
    }

    /**
     * 校验目标租户存在并返回。
     *
     * @param tenantId 租户代码
     * @return 租户元数据
     */
    @Transactional(readOnly = true)
    public TenantRecord requireTenant(String tenantId) {
        return adminRepository.findTenant(tenantId).orElseThrow(() -> new NotFoundException("tenant"));
    }

    /**
     * 写审计；在事件租户上下文中执行。
     *
     * @param actor 操作者
     * @param auditTenantId 事件租户
     * @param action 动作
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param outcome 结果
     * @param meta 请求元数据
     * @param details 白名单字段
     */
    private void appendAudit(
            RicconPrincipal actor,
            String auditTenantId,
            String action,
            AuditTargetType targetType,
            String targetId,
            AuditOutcome outcome,
            RequestMeta meta,
            Map<String, Object> details) {
        TenantContext.runWithTenant(
                TenantId.of(auditTenantId),
                () -> auditService.append(AuditEvent.create(
                        clock.instant(),
                        auditTenantId,
                        AuditActorType.USER,
                        actor.userId().toString(),
                        action,
                        targetType,
                        targetId,
                        outcome,
                        meta.traceId(),
                        meta.requestId(),
                        null,
                        details)));
    }

    /**
     * 映射租户摘要。
     *
     * @param tenant 租户
     * @return 摘要
     */
    static TenantSummaryResponse toSummary(TenantRecord tenant) {
        return new TenantSummaryResponse(
                tenant.id(), tenant.name(), tenant.status(), tenant.plan(), tenant.retentionDays(), tenant.createdAt());
    }
}
