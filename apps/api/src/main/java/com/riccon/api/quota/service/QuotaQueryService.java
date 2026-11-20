package com.riccon.api.quota.service;

import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.quota.config.QuotaProperties;
import com.riccon.api.quota.domain.QuotaLimitRow;
import com.riccon.api.quota.domain.QuotaScope;
import com.riccon.api.quota.domain.QuotaUnavailableException;
import com.riccon.api.quota.domain.QuotaUsageRow;
import com.riccon.api.quota.domain.QuotaUser;
import com.riccon.api.quota.domain.QuotaWindowBoundary;
import com.riccon.api.quota.domain.QuotaWindows;
import com.riccon.api.quota.dto.TenantQuota;
import com.riccon.api.quota.dto.UserQuota;
import com.riccon.api.quota.dto.UserQuotaPage;
import com.riccon.api.quota.repository.QuotaRepository;
import com.riccon.platform.common.tenant.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户端配额读取（{@code quota.read}）。
 *
 * <p>只返回当前租户的限额、用量与重置时间；租户名不读取全局租户表，避免租户端越权读取平台目录。
 * 读取失败由全局异常处理映射为 500/503，不返回部分结果。
 */
@Service
public class QuotaQueryService {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(QuotaQueryService.class);

    /** 配额仓储。 */
    private final QuotaRepository quotaRepository;

    /** 部署默认值。 */
    private final QuotaProperties quotaProperties;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建读取服务。
     *
     * @param quotaRepository 配额仓储
     * @param quotaProperties 部署默认值
     * @param clock UTC 时钟
     */
    public QuotaQueryService(QuotaRepository quotaRepository, QuotaProperties quotaProperties, Clock clock) {
        this.quotaRepository = Objects.requireNonNull(quotaRepository, "quotaRepository must not be null");
        this.quotaProperties = Objects.requireNonNull(quotaProperties, "quotaProperties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 读取本租户日/月配额。
     *
     * @param tenantId 当前租户
     * @return 租户配额
     */
    @Transactional(readOnly = true)
    public TenantQuota getTenantQuota(TenantId tenantId) {
        try {
            List<QuotaWindowBoundary> boundaries = QuotaWindows.boundariesAt(clock.instant());
            List<QuotaLimitRow> limits = quotaRepository.findLimits(tenantId, QuotaScope.TENANT, tenantId.value());
            List<QuotaUsageRow> usages =
                    quotaRepository.findUsages(tenantId, QuotaScope.TENANT, tenantId.value(), windowStarts(boundaries));
            return new TenantQuota(
                    tenantId.value(),
                    tenantId.value(),
                    null,
                    QuotaStateAssembler.assemble(boundaries, QuotaScope.TENANT, limits, usages, quotaProperties));
        } catch (DataAccessException exception) {
            LOG.error("quota.read.store_failure tenantId={}", tenantId.value(), exception);
            throw new QuotaUnavailableException();
        }
    }

    /**
     * 分页读取本租户用户配额。
     *
     * @param tenantId 当前租户
     * @param page 分页参数
     * @return 用户配额分页
     */
    @Transactional(readOnly = true)
    public UserQuotaPage listUserQuotas(TenantId tenantId, PageQuery page) {
        try {
            return listUserQuotasInternal(tenantId, page);
        } catch (DataAccessException exception) {
            LOG.error("quota.read.users_store_failure tenantId={}", tenantId.value(), exception);
            throw new QuotaUnavailableException();
        }
    }

    /**
     * 读取本租户用户配额的实际实现。
     *
     * @param tenantId 当前租户
     * @param page 分页参数
     * @return 用户配额分页
     */
    private UserQuotaPage listUserQuotasInternal(TenantId tenantId, PageQuery page) {
        long total = quotaRepository.countUsers(tenantId);
        List<QuotaUser> users = quotaRepository.listUsers(tenantId, page.size(), page.offset());
        if (users.isEmpty()) {
            return new UserQuotaPage(page.page(), page.size(), total, page.totalPages(total), List.of());
        }
        List<QuotaWindowBoundary> boundaries = QuotaWindows.boundariesAt(clock.instant());
        List<String> scopeIds =
                users.stream().map(user -> user.userId().toString()).toList();
        List<QuotaLimitRow> limits = quotaRepository.findUserLimits(tenantId, scopeIds);
        List<QuotaUsageRow> usages = quotaRepository.findUserUsages(tenantId, scopeIds, windowStarts(boundaries));
        Map<String, List<QuotaLimitRow>> limitsByScope = groupLimits(limits);
        Map<String, List<QuotaUsageRow>> usagesByScope = groupUsages(usages);
        List<UserQuota> items = new ArrayList<>(users.size());
        for (QuotaUser user : users) {
            String scopeId = user.userId().toString();
            items.add(new UserQuota(
                    user.userId(),
                    user.username(),
                    user.displayName(),
                    QuotaStateAssembler.assemble(
                            boundaries,
                            QuotaScope.USER,
                            limitsByScope.getOrDefault(scopeId, List.of()),
                            usagesByScope.getOrDefault(scopeId, List.of()),
                            quotaProperties)));
        }
        return new UserQuotaPage(page.page(), page.size(), total, page.totalPages(total), items);
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
     * 按 scopeId 分组限额。
     *
     * @param limits 限额行
     * @return 分组
     */
    private static Map<String, List<QuotaLimitRow>> groupLimits(List<QuotaLimitRow> limits) {
        Map<String, List<QuotaLimitRow>> grouped = new HashMap<>();
        for (QuotaLimitRow limit : limits) {
            grouped.computeIfAbsent(limit.scopeId(), key -> new ArrayList<>()).add(limit);
        }
        return grouped;
    }

    /**
     * 按 scopeId 分组用量。
     *
     * @param usages 用量行
     * @return 分组
     */
    private static Map<String, List<QuotaUsageRow>> groupUsages(List<QuotaUsageRow> usages) {
        Map<String, List<QuotaUsageRow>> grouped = new HashMap<>();
        for (QuotaUsageRow usage : usages) {
            grouped.computeIfAbsent(usage.scopeId(), key -> new ArrayList<>()).add(usage);
        }
        return grouped;
    }
}
