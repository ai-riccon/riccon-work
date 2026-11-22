package com.riccon.api.admin.quota.repository;

import com.riccon.api.admin.quota.persistence.PlatformQuotaJdbc;
import com.riccon.api.quota.domain.QuotaLimitRow;
import com.riccon.api.quota.domain.QuotaScope;
import com.riccon.api.quota.domain.QuotaUsageRow;
import com.riccon.api.quota.domain.QuotaWindow;
import com.riccon.platform.common.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 平台配额仓储。
 *
 * <p>只做持久化编排；权限、租户状态与审计由 service 负责。
 */
@Repository
public class PlatformQuotaRepository {

    /** 平台配额 SQL。 */
    private final PlatformQuotaJdbc platformQuotaJdbc;

    /**
     * 创建仓储。
     *
     * @param platformQuotaJdbc 平台配额 SQL
     */
    public PlatformQuotaRepository(PlatformQuotaJdbc platformQuotaJdbc) {
        this.platformQuotaJdbc = Objects.requireNonNull(platformQuotaJdbc, "platformQuotaJdbc must not be null");
    }

    /**
     * 批量读取租户级显式限额。
     *
     * @param tenantIds 租户代码
     * @return 限额行
     */
    public List<QuotaLimitRow> findTenantLimits(List<String> tenantIds) {
        return platformQuotaJdbc.findTenantLimits(tenantIds);
    }

    /**
     * 批量读取租户级用量。
     *
     * @param tenantIds 租户代码
     * @param windowStarts 窗口起点集合
     * @return 用量行
     */
    public List<QuotaUsageRow> findTenantUsages(List<String> tenantIds, List<Instant> windowStarts) {
        return platformQuotaJdbc.findTenantUsages(tenantIds, windowStarts);
    }

    /**
     * 读取指定维度显式限额。
     *
     * @param tenantId 目标租户
     * @param scope 维度
     * @param scopeId 维度标识
     * @return 限额行
     */
    public List<QuotaLimitRow> findLimits(TenantId tenantId, QuotaScope scope, String scopeId) {
        return platformQuotaJdbc.findLimits(tenantId, scope, scopeId);
    }

    /**
     * 读取指定维度用量。
     *
     * @param tenantId 目标租户
     * @param scope 维度
     * @param scopeId 维度标识
     * @param windowStarts 窗口起点集合
     * @return 用量行
     */
    public List<QuotaUsageRow> findUsages(
            TenantId tenantId, QuotaScope scope, String scopeId, List<Instant> windowStarts) {
        return platformQuotaJdbc.findUsages(tenantId, scope, scopeId, windowStarts);
    }

    /**
     * 整体替换一个窗口限额。
     *
     * @param tenantId 目标租户
     * @param scope 维度
     * @param scopeId 维度标识
     * @param userId 用户维度必填
     * @param window 窗口
     * @param limit 限额；{@code null} 表示明确不限
     * @param updatedBy 平台管理员 ID
     * @param now 当前时间
     */
    public void upsertLimit(
            TenantId tenantId,
            QuotaScope scope,
            String scopeId,
            UUID userId,
            QuotaWindow window,
            Integer limit,
            UUID updatedBy,
            Instant now) {
        platformQuotaJdbc.upsertLimit(tenantId, scope, scopeId, userId, window, limit, updatedBy, now);
    }
}
