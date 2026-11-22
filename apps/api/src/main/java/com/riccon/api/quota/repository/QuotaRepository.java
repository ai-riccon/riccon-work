package com.riccon.api.quota.repository;

import com.riccon.api.quota.domain.QuotaLimitRow;
import com.riccon.api.quota.domain.QuotaScope;
import com.riccon.api.quota.domain.QuotaUsageRow;
import com.riccon.api.quota.domain.QuotaUser;
import com.riccon.api.quota.domain.QuotaWindow;
import com.riccon.api.quota.persistence.QuotaJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 配额租户侧仓储。
 *
 * <p>只做持久化编排，不包含准入顺序、限额解析或 HTTP 语义；这些规则在 service/domain 层。
 */
@Repository
public class QuotaRepository {

    /** 配额 SQL。 */
    private final QuotaJdbc quotaJdbc;

    /**
     * 创建仓储。
     *
     * @param quotaJdbc 配额 SQL
     */
    public QuotaRepository(QuotaJdbc quotaJdbc) {
        this.quotaJdbc = Objects.requireNonNull(quotaJdbc, "quotaJdbc must not be null");
    }

    /**
     * 读取全局配额执行开关。
     *
     * @return 原始开关值；缺失时为空
     */
    public Optional<String> findEnforcementSwitch() {
        return quotaJdbc.findEnforcementSwitch();
    }

    /**
     * 锁定租户行作为准入串行点。
     *
     * @param tenantId 当前租户
     * @return 租户行存在时返回 true
     */
    public boolean lockTenant(TenantId tenantId) {
        return quotaJdbc.lockTenant(tenantId);
    }

    /**
     * 读取准入所需的租户级与用户级显式限额。
     *
     * @param tenantId 当前租户
     * @param userId 当前用户
     * @return 显式限额行
     */
    public List<QuotaLimitRow> findAdmissionLimits(TenantId tenantId, UUID userId) {
        return quotaJdbc.findAdmissionLimits(tenantId, userId);
    }

    /**
     * 读取指定维度显式限额。
     *
     * @param tenantId 当前租户
     * @param scope 维度
     * @param scopeId 维度标识
     * @return 显式限额行
     */
    public List<QuotaLimitRow> findLimits(TenantId tenantId, QuotaScope scope, String scopeId) {
        return quotaJdbc.findLimits(tenantId, scope, scopeId);
    }

    /**
     * 读取用户集合显式限额。
     *
     * @param tenantId 当前租户
     * @param scopeIds 用户 ID 字符串
     * @return 显式限额行
     */
    public List<QuotaLimitRow> findUserLimits(TenantId tenantId, List<String> scopeIds) {
        return quotaJdbc.findUserLimits(tenantId, scopeIds);
    }

    /**
     * 确保窗口行存在。
     *
     * @param tenantId 当前租户
     * @param scope 维度
     * @param scopeId 维度标识
     * @param window 窗口
     * @param windowStart 窗口起点
     */
    public void ensureUsageRow(
            TenantId tenantId, QuotaScope scope, String scopeId, QuotaWindow window, Instant windowStart) {
        quotaJdbc.ensureUsageRow(tenantId, scope, scopeId, window, windowStart);
    }

    /**
     * 条件累加一次。
     *
     * @param tenantId 当前租户
     * @param scope 维度
     * @param scopeId 维度标识
     * @param window 窗口
     * @param windowStart 窗口起点
     * @param limit 生效限额
     * @param now 当前时间
     * @return 受影响行数；0 表示已满
     */
    public int incrementConditional(
            TenantId tenantId,
            QuotaScope scope,
            String scopeId,
            QuotaWindow window,
            Instant windowStart,
            int limit,
            Instant now) {
        return quotaJdbc.incrementConditional(tenantId, scope, scopeId, window, windowStart, limit, now);
    }

    /**
     * 无条件累加一次。
     *
     * @param tenantId 当前租户
     * @param scope 维度
     * @param scopeId 维度标识
     * @param window 窗口
     * @param windowStart 窗口起点
     * @param now 当前时间
     * @return 受影响行数
     */
    public int incrementUnconditional(
            TenantId tenantId, QuotaScope scope, String scopeId, QuotaWindow window, Instant windowStart, Instant now) {
        return quotaJdbc.incrementUnconditional(tenantId, scope, scopeId, window, windowStart, now);
    }

    /**
     * 读取单个窗口已用量。
     *
     * @param tenantId 当前租户
     * @param scope 维度
     * @param scopeId 维度标识
     * @param window 窗口
     * @param windowStart 窗口起点
     * @return 已用量；缺失时为空
     */
    public Optional<Integer> findUsed(
            TenantId tenantId, QuotaScope scope, String scopeId, QuotaWindow window, Instant windowStart) {
        return quotaJdbc.findUsed(tenantId, scope, scopeId, window, windowStart);
    }

    /**
     * 读取单维度多窗口用量。
     *
     * @param tenantId 当前租户
     * @param scope 维度
     * @param scopeId 维度标识
     * @param windowStarts 窗口起点集合
     * @return 用量行
     */
    public List<QuotaUsageRow> findUsages(
            TenantId tenantId, QuotaScope scope, String scopeId, List<Instant> windowStarts) {
        return quotaJdbc.findUsages(tenantId, scope, scopeId, windowStarts);
    }

    /**
     * 读取用户集合用量。
     *
     * @param tenantId 当前租户
     * @param scopeIds 用户 ID 字符串
     * @param windowStarts 窗口起点集合
     * @return 用量行
     */
    public List<QuotaUsageRow> findUserUsages(TenantId tenantId, List<String> scopeIds, List<Instant> windowStarts) {
        return quotaJdbc.findUserUsages(tenantId, scopeIds, windowStarts);
    }

    /**
     * 分页读取本租户用户。
     *
     * @param tenantId 当前租户
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 用户投影
     */
    public List<QuotaUser> listUsers(TenantId tenantId, int limit, int offset) {
        return quotaJdbc.listUsers(tenantId, limit, offset);
    }

    /**
     * 统计本租户用户数。
     *
     * @param tenantId 当前租户
     * @return 用户数
     */
    public long countUsers(TenantId tenantId) {
        return quotaJdbc.countUsers(tenantId);
    }
}
