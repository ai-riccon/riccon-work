package com.riccon.api.admin.quota.service;

import com.riccon.api.admin.quota.dto.PlatformQuotaPage;
import com.riccon.api.admin.quota.dto.PlatformQuotaRow;
import com.riccon.api.admin.quota.repository.PlatformQuotaRepository;
import com.riccon.api.admin.repository.AdminRepository;
import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.error.ConflictException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.domain.TenantRecord;
import com.riccon.api.identity.domain.UserAccount;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.quota.config.QuotaProperties;
import com.riccon.api.quota.domain.QuotaLimitRow;
import com.riccon.api.quota.domain.QuotaScope;
import com.riccon.api.quota.domain.QuotaUnavailableException;
import com.riccon.api.quota.domain.QuotaUsageRow;
import com.riccon.api.quota.domain.QuotaWindow;
import com.riccon.api.quota.domain.QuotaWindowBoundary;
import com.riccon.api.quota.domain.QuotaWindows;
import com.riccon.api.quota.dto.QuotaLimitUpdate;
import com.riccon.api.quota.dto.TenantQuota;
import com.riccon.api.quota.dto.UserQuota;
import com.riccon.api.quota.service.QuotaStateAssembler;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台管理员配额查看与整体替换（{@code platform.quota.manage}）。
 *
 * <p>平台列表只返回租户聚合，绝不返回用户明细；写操作按窗口整体替换，并在同一事务写
 * {@code quota.limit.changed}（审计失败则整体失败）。跨租户/不存在的目标统一 404，停用租户 409，
 * 不泄露对象存在性（ADR-0022 第 1.5/3.4 节）。
 */
@Service
public class PlatformQuotaService {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PlatformQuotaService.class);

    /** 平台保留租户；平台管理员管理事件的审计租户。 */
    private static final String PLATFORM_TENANT = "platform";

    /** 平台配额仓储。 */
    private final PlatformQuotaRepository platformQuotaRepository;

    /** 租户/账号目录。 */
    private final AdminRepository adminRepository;

    /** 部署默认值。 */
    private final QuotaProperties quotaProperties;

    /** 审计写入。 */
    private final AuditService auditService;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建平台配额服务。
     *
     * @param platformQuotaRepository 平台配额仓储
     * @param adminRepository 平台目录仓储
     * @param quotaProperties 部署默认值
     * @param auditService 审计写入
     * @param clock UTC 时钟
     */
    public PlatformQuotaService(
            PlatformQuotaRepository platformQuotaRepository,
            AdminRepository adminRepository,
            QuotaProperties quotaProperties,
            AuditService auditService,
            Clock clock) {
        this.platformQuotaRepository =
                Objects.requireNonNull(platformQuotaRepository, "platformQuotaRepository must not be null");
        this.adminRepository = Objects.requireNonNull(adminRepository, "adminRepository must not be null");
        this.quotaProperties = Objects.requireNonNull(quotaProperties, "quotaProperties must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 平台租户配额聚合分页。
     *
     * @param page 分页参数
     * @return 租户聚合页
     */
    @Transactional(readOnly = true)
    public PlatformQuotaPage listTenants(PageQuery page) {
        try {
            return listTenantsInternal(page);
        } catch (DataAccessException exception) {
            LOG.error("quota.admin.list_store_failure", exception);
            throw new QuotaUnavailableException();
        }
    }

    /**
     * 平台租户配额聚合分页的实际实现。
     *
     * @param page 分页参数
     * @return 租户聚合页
     */
    private PlatformQuotaPage listTenantsInternal(PageQuery page) {
        long total = adminRepository.countTenants();
        List<TenantRecord> tenants = adminRepository.listTenants(page.size(), page.offset());
        if (tenants.isEmpty()) {
            return new PlatformQuotaPage(page.page(), page.size(), total, page.totalPages(total), List.of());
        }
        List<String> tenantIds = tenants.stream().map(TenantRecord::id).toList();
        List<QuotaWindowBoundary> boundaries = QuotaWindows.boundariesAt(clock.instant());
        List<Instant> windowStarts =
                boundaries.stream().map(QuotaWindowBoundary::windowStart).toList();
        List<QuotaLimitRow> limits = platformQuotaRepository.findTenantLimits(tenantIds);
        List<QuotaUsageRow> usages = platformQuotaRepository.findTenantUsages(tenantIds, windowStarts);
        Map<String, List<QuotaLimitRow>> limitsByTenant = groupLimits(limits);
        Map<String, List<QuotaUsageRow>> usagesByTenant = groupUsages(usages);
        List<PlatformQuotaRow> items = new ArrayList<>(tenants.size());
        for (TenantRecord tenant : tenants) {
            items.add(new PlatformQuotaRow(
                    tenant.id(),
                    tenant.id(),
                    tenant.name(),
                    QuotaStateAssembler.assemble(
                            boundaries,
                            QuotaScope.TENANT,
                            limitsByTenant.getOrDefault(tenant.id(), List.of()),
                            usagesByTenant.getOrDefault(tenant.id(), List.of()),
                            quotaProperties)));
        }
        return new PlatformQuotaPage(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 整体替换租户级日/月限额。
     *
     * @param actor 平台管理员
     * @param tenantCode 目标租户
     * @param update 限额
     * @param meta 请求元数据
     * @return 更新后的租户配额
     */
    @Transactional
    public TenantQuota updateTenantQuota(
            RicconPrincipal actor, String tenantCode, QuotaLimitUpdate update, RequestMeta meta) {
        TenantRecord tenant = requireActiveTenant(tenantCode);
        TenantId tenantId = TenantId.of(tenant.id());
        Instant now = clock.instant();
        Map<QuotaWindow, Integer> previous = previousLimits(tenantId, QuotaScope.TENANT, tenantCode);
        upsert(tenantId, QuotaScope.TENANT, tenantCode, null, QuotaWindow.DAILY, update.daily(), actor, now);
        upsert(tenantId, QuotaScope.TENANT, tenantCode, null, QuotaWindow.MONTHLY, update.monthly(), actor, now);
        appendLimitAudit(actor, QuotaScope.TENANT, tenantCode, QuotaWindow.DAILY, previous, update.daily(), meta);
        appendLimitAudit(actor, QuotaScope.TENANT, tenantCode, QuotaWindow.MONTHLY, previous, update.monthly(), meta);
        List<QuotaWindowBoundary> boundaries = QuotaWindows.boundariesAt(now);
        List<QuotaLimitRow> limits = platformQuotaRepository.findLimits(tenantId, QuotaScope.TENANT, tenantCode);
        List<QuotaUsageRow> usages =
                platformQuotaRepository.findUsages(tenantId, QuotaScope.TENANT, tenantCode, windowStarts(boundaries));
        return new TenantQuota(
                tenant.id(),
                tenant.id(),
                tenant.name(),
                QuotaStateAssembler.assemble(boundaries, QuotaScope.TENANT, limits, usages, quotaProperties));
    }

    /**
     * 整体替换目标用户日/月限额。
     *
     * @param actor 平台管理员
     * @param tenantCode 目标租户
     * @param userId 目标用户
     * @param update 限额
     * @param meta 请求元数据
     * @return 更新后的用户配额
     */
    @Transactional
    public UserQuota updateUserQuota(
            RicconPrincipal actor, String tenantCode, UUID userId, QuotaLimitUpdate update, RequestMeta meta) {
        TenantRecord tenant = requireActiveTenant(tenantCode);
        TenantId tenantId = TenantId.of(tenant.id());
        UserAccount user = adminRepository.findUser(tenantId, userId).orElseThrow(() -> new NotFoundException("user"));
        if (!user.isActive()) {
            throw new ConflictException("user is not active");
        }
        String scopeId = userId.toString();
        Instant now = clock.instant();
        Map<QuotaWindow, Integer> previous = previousLimits(tenantId, QuotaScope.USER, scopeId);
        upsert(tenantId, QuotaScope.USER, scopeId, userId, QuotaWindow.DAILY, update.daily(), actor, now);
        upsert(tenantId, QuotaScope.USER, scopeId, userId, QuotaWindow.MONTHLY, update.monthly(), actor, now);
        appendLimitAudit(actor, QuotaScope.USER, scopeId, QuotaWindow.DAILY, previous, update.daily(), meta);
        appendLimitAudit(actor, QuotaScope.USER, scopeId, QuotaWindow.MONTHLY, previous, update.monthly(), meta);
        List<QuotaWindowBoundary> boundaries = QuotaWindows.boundariesAt(now);
        List<QuotaLimitRow> limits = platformQuotaRepository.findLimits(tenantId, QuotaScope.USER, scopeId);
        List<QuotaUsageRow> usages =
                platformQuotaRepository.findUsages(tenantId, QuotaScope.USER, scopeId, windowStarts(boundaries));
        return new UserQuota(
                user.id(),
                user.username(),
                user.displayName(),
                QuotaStateAssembler.assemble(boundaries, QuotaScope.USER, limits, usages, quotaProperties));
    }

    /**
     * 校验目标租户存在且可用。
     *
     * @param tenantCode 租户代码
     * @return 租户元数据
     */
    private TenantRecord requireActiveTenant(String tenantCode) {
        TenantRecord tenant = adminRepository.findTenant(tenantCode).orElseThrow(() -> new NotFoundException("tenant"));
        if (!tenant.isActive()) {
            throw new ConflictException("tenant is not active");
        }
        return tenant;
    }

    /**
     * 读取现有显式限额（用于审计前值）。
     *
     * @param tenantId 目标租户
     * @param scope 维度
     * @param scopeId 维度标识
     * @return 窗口到限额的映射
     */
    private Map<QuotaWindow, Integer> previousLimits(TenantId tenantId, QuotaScope scope, String scopeId) {
        Map<QuotaWindow, Integer> previous = new HashMap<>();
        for (QuotaLimitRow row : platformQuotaRepository.findLimits(tenantId, scope, scopeId)) {
            previous.put(row.window(), row.limit());
        }
        return previous;
    }

    /**
     * 写入一个窗口限额。
     *
     * @param tenantId 目标租户
     * @param scope 维度
     * @param scopeId 维度标识
     * @param userId 用户维度必填
     * @param window 窗口
     * @param limit 限额；{@code null} 表示明确不限
     * @param actor 平台管理员
     * @param now 当前时间
     */
    private void upsert(
            TenantId tenantId,
            QuotaScope scope,
            String scopeId,
            UUID userId,
            QuotaWindow window,
            Integer limit,
            RicconPrincipal actor,
            Instant now) {
        platformQuotaRepository.upsertLimit(tenantId, scope, scopeId, userId, window, limit, actor.userId(), now);
    }

    /**
     * 写一条 {@code quota.limit.changed}。
     *
     * <p>审计 details 的 {@code limit} 不允许 JSON null（{@code AuditDetailsPolicy} 只放行
     * {@code previousLimit} 为 null），因此“明确不限”写 0；响应仍返回 {@code limit=null}。
     *
     * @param actor 平台管理员
     * @param scope 维度
     * @param scopeId 维度标识
     * @param window 窗口
     * @param previous 前值映射
     * @param limit 新限额
     * @param meta 请求元数据
     */
    private void appendLimitAudit(
            RicconPrincipal actor,
            QuotaScope scope,
            String scopeId,
            QuotaWindow window,
            Map<QuotaWindow, Integer> previous,
            Integer limit,
            RequestMeta meta) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("scopeType", scope.name());
        details.put("scopeId", scopeId);
        details.put("window", window.name());
        // previousLimit 允许 JSON null：首次设置或“显式不限 → 数字”表示没有可比较的旧值。
        // AuditDetailsPolicy 已将 previousLimit 列为动作级 nullable，AuditEvent 支持 null 值并保持不可变。
        details.put("previousLimit", previous.get(window));
        // limit 为 null 表示显式不限；审计 details 的 limit 不允许 null（B1a REV-TASK-0100-001 F-003），
        // 继续用 0 表达“不限”，响应仍返回 limit=null。
        details.put("limit", limit == null ? 0 : limit);
        Instant now = clock.instant();
        TenantContext.runWithTenant(
                TenantId.of(PLATFORM_TENANT),
                () -> auditService.append(AuditEvent.create(
                        now,
                        PLATFORM_TENANT,
                        AuditActorType.USER,
                        actor.userId().toString(),
                        AuditActions.QUOTA_LIMIT_CHANGED,
                        AuditTargetType.QUOTA,
                        scopeId,
                        AuditOutcome.SUCCESS,
                        meta.traceId(),
                        meta.requestId(),
                        null,
                        details)));
    }

    /**
     * 提取窗口起点。
     *
     * @param boundaries 窗口边界
     * @return 窗口起点集合
     */
    private static List<Instant> windowStarts(List<QuotaWindowBoundary> boundaries) {
        return boundaries.stream().map(QuotaWindowBoundary::windowStart).toList();
    }

    /**
     * 按租户分组限额。
     *
     * @param limits 限额行
     * @return 分组
     */
    private static Map<String, List<QuotaLimitRow>> groupLimits(List<QuotaLimitRow> limits) {
        Map<String, List<QuotaLimitRow>> grouped = new HashMap<>();
        for (QuotaLimitRow limit : limits) {
            grouped.computeIfAbsent(limit.tenantId(), key -> new ArrayList<>()).add(limit);
        }
        return grouped;
    }

    /**
     * 按租户分组用量。
     *
     * @param usages 用量行
     * @return 分组
     */
    private static Map<String, List<QuotaUsageRow>> groupUsages(List<QuotaUsageRow> usages) {
        Map<String, List<QuotaUsageRow>> grouped = new HashMap<>();
        for (QuotaUsageRow usage : usages) {
            grouped.computeIfAbsent(usage.tenantId(), key -> new ArrayList<>()).add(usage);
        }
        return grouped;
    }
}
