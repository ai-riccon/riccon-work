package com.riccon.api.admin.quota.persistence;

import com.riccon.api.admin.persistence.PlatformJdbc;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.quota.domain.QuotaLimitRow;
import com.riccon.api.quota.domain.QuotaScope;
import com.riccon.api.quota.domain.QuotaUsageRow;
import com.riccon.api.quota.domain.QuotaWindow;
import com.riccon.platform.common.tenant.TenantId;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 平台管理员跨租户配额 SQL。
 *
 * <p>只有显式管理路径可以使用 {@link PlatformJdbc}；目标租户来自路径参数并经过 service 的存在性/状态校验。
 * 这里不读取租户业务内容，只读写 {@code quota_limit}/{@code quota_usage}。
 */
@Component
public class PlatformQuotaJdbc {

    /** 批量读取租户级显式限额。 */
    private static final String FIND_TENANT_LIMITS = """
            SELECT tenant_id, scope_type, scope_id, window_kind, limit_count
              FROM quota_limit
             WHERE tenant_id IN (:tenantIds)
               AND scope_type = 'TENANT'
            """;

    /** 批量读取租户级用量。 */
    private static final String FIND_TENANT_USAGES = """
            SELECT tenant_id, scope_type, scope_id, window_kind, window_start, used_count
              FROM quota_usage
             WHERE tenant_id IN (:tenantIds)
               AND scope_type = 'TENANT'
               AND window_start IN (:windowStarts)
            """;

    /** 读取单个租户指定维度的限额。 */
    private static final String FIND_LIMITS = """
            SELECT tenant_id, scope_type, scope_id, window_kind, limit_count
              FROM quota_limit
             WHERE tenant_id = :tenantId
               AND scope_type = :scopeType
               AND scope_id = :scopeId
            """;

    /** 读取单个租户指定维度的用量。 */
    private static final String FIND_USAGES = """
            SELECT tenant_id, scope_type, scope_id, window_kind, window_start, used_count
              FROM quota_usage
             WHERE tenant_id = :tenantId
               AND scope_type = :scopeType
               AND scope_id = :scopeId
               AND window_start IN (:windowStarts)
            """;

    /** 整体替换单个窗口的限额；不存在则插入。 */
    private static final String UPSERT_LIMIT = """
            INSERT INTO quota_limit (
                tenant_id, scope_type, scope_id, user_id, window_kind, limit_count, updated_by, updated_at
            ) VALUES (
                :tenantId, :scopeType, :scopeId, :userId, :windowKind, :limitCount, :updatedBy, :now
            ) AS incoming
            ON DUPLICATE KEY UPDATE
                limit_count = incoming.limit_count,
                user_id = incoming.user_id,
                updated_by = incoming.updated_by,
                updated_at = incoming.updated_at
            """;

    /** 平台数据访问入口。 */
    private final PlatformJdbc platformJdbc;

    /**
     * 创建平台配额 SQL 组件。
     *
     * @param platformJdbc 平台数据访问入口
     */
    public PlatformQuotaJdbc(PlatformJdbc platformJdbc) {
        this.platformJdbc = Objects.requireNonNull(platformJdbc, "platformJdbc must not be null");
    }

    /**
     * 批量读取租户级显式限额。
     *
     * @param tenantIds 租户代码
     * @return 限额行
     */
    public List<QuotaLimitRow> findTenantLimits(List<String> tenantIds) {
        if (tenantIds.isEmpty()) {
            return List.of();
        }
        return platformJdbc.queryGlobal(
                FIND_TENANT_LIMITS, Map.of("tenantIds", tenantIds), PlatformQuotaJdbc::mapLimit);
    }

    /**
     * 批量读取租户级用量。
     *
     * @param tenantIds 租户代码
     * @param windowStarts 窗口起点集合
     * @return 用量行
     */
    public List<QuotaUsageRow> findTenantUsages(List<String> tenantIds, List<Instant> windowStarts) {
        if (tenantIds.isEmpty() || windowStarts.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantIds", tenantIds);
        params.put(
                "windowStarts",
                windowStarts.stream().map(SqlTimestamps::toDatabase).toList());
        return platformJdbc.queryGlobal(FIND_TENANT_USAGES, params, PlatformQuotaJdbc::mapUsage);
    }

    /**
     * 读取单个租户指定维度的显式限额。
     *
     * @param tenantId 目标租户
     * @param scope 维度
     * @param scopeId 维度标识
     * @return 限额行
     */
    public List<QuotaLimitRow> findLimits(TenantId tenantId, QuotaScope scope, String scopeId) {
        return platformJdbc.queryTenant(
                tenantId,
                FIND_LIMITS,
                Map.of("scopeType", scope.name(), "scopeId", scopeId),
                PlatformQuotaJdbc::mapLimit);
    }

    /**
     * 读取单个租户指定维度的用量。
     *
     * @param tenantId 目标租户
     * @param scope 维度
     * @param scopeId 维度标识
     * @param windowStarts 窗口起点集合
     * @return 用量行
     */
    public List<QuotaUsageRow> findUsages(
            TenantId tenantId, QuotaScope scope, String scopeId, List<Instant> windowStarts) {
        if (windowStarts.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("scopeType", scope.name());
        params.put("scopeId", scopeId);
        params.put(
                "windowStarts",
                windowStarts.stream().map(SqlTimestamps::toDatabase).toList());
        return platformJdbc.queryTenant(tenantId, FIND_USAGES, params, PlatformQuotaJdbc::mapUsage);
    }

    /**
     * 整体替换一个窗口的限额。
     *
     * @param tenantId 目标租户
     * @param scope 维度
     * @param scopeId 维度标识
     * @param userId 用户维度必填，租户维度为空
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
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("scopeType", scope.name());
        params.put("scopeId", scopeId);
        params.put("userId", userId == null ? null : BinaryUuids.toBytes(userId));
        params.put("windowKind", window.name());
        params.put("limitCount", limit);
        params.put("updatedBy", BinaryUuids.toBytes(updatedBy));
        params.put("now", SqlTimestamps.toDatabase(now));
        platformJdbc.insertTenant(tenantId, UPSERT_LIMIT, params);
    }

    /**
     * 映射限额行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 限额行
     */
    private static QuotaLimitRow mapLimit(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new QuotaLimitRow(
                rs.getString("tenant_id"),
                QuotaScope.valueOf(rs.getString("scope_type")),
                rs.getString("scope_id"),
                QuotaWindow.valueOf(rs.getString("window_kind")),
                rs.getObject("limit_count", Integer.class));
    }

    /**
     * 映射用量行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 用量行
     */
    private static QuotaUsageRow mapUsage(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        LocalDateTime windowStart = rs.getObject("window_start", LocalDateTime.class);
        return new QuotaUsageRow(
                rs.getString("tenant_id"),
                QuotaScope.valueOf(rs.getString("scope_type")),
                rs.getString("scope_id"),
                QuotaWindow.valueOf(rs.getString("window_kind")),
                SqlTimestamps.toInstant(windowStart),
                rs.getInt("used_count"));
    }
}
