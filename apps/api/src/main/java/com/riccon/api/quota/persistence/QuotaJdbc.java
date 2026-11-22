package com.riccon.api.quota.persistence;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.api.quota.domain.QuotaLimitRow;
import com.riccon.api.quota.domain.QuotaScope;
import com.riccon.api.quota.domain.QuotaUsageRow;
import com.riccon.api.quota.domain.QuotaUser;
import com.riccon.api.quota.domain.QuotaWindow;
import com.riccon.platform.common.tenant.TenantId;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 配额租户侧 SQL。
 *
 * <p>{@code quota_limit}/{@code quota_usage} 的租户范围读写全部经过 {@link TenantJdbc}，由它强制
 * {@code tenant_id = :tenantId} 与当前租户上下文一致。唯一例外是全局控制表 {@code model_gateway_switch}：
 * 它没有租户维度，且不属于 PlatformJdbc 的管理读路径，因此直接使用 {@link JdbcClient} 读取单个开关值；
 * 准入串行锁也直接锁全局 {@code tenant} 行（同样无租户业务内容），不读取任何租户业务数据。
 *
 * <p>准入所需的“确保行存在 + 条件累加”拆成两条 SQL：先
 * {@code INSERT ... ON DUPLICATE KEY UPDATE used_count = used_count} 取得排他行锁，再执行带
 * {@code used_count < :limit} 的 UPDATE；不使用 {@code INSERT IGNORE}（ADR-0022 第 1.3 节）。
 */
@Component
public class QuotaJdbc {

    /** 全局配额执行开关键；值由 V11 种子与 B3 管理端写入。 */
    private static final String ENFORCEMENT_SWITCH_KEY = "quotaEnforcementEnabled";

    /** 读取全局开关值。 */
    private static final String FIND_ENFORCEMENT_SWITCH =
            "SELECT switch_value FROM model_gateway_switch WHERE switch_key = :switchKey";

    /**
     * 租户级准入串行点。
     *
     * <p>并发首次创建同一 {@code quota_usage} 行时，MySQL 的 {@code INSERT ... ON DUPLICATE KEY UPDATE}
     * 会先取共享锁再升级排他锁，多个事务互相等待会触发死锁。先在 {@code tenant} 行上取排他锁，让同一租户的
     * 准入严格串行（它们本来也会在租户日窗口热点行上串行），再按固定顺序创建/累加配额行，可消除这一升级死锁。
     * 锁随 run 创建事务提交/回滚自动释放，不需要额外解锁。
     */
    private static final String LOCK_TENANT = "SELECT id FROM tenant WHERE id = :tenantId FOR UPDATE";

    /** 读取租户级 + 当前用户级显式限额。 */
    private static final String FIND_LIMITS = """
            SELECT tenant_id, scope_type, scope_id, window_kind, limit_count
              FROM quota_limit
             WHERE tenant_id = :tenantId
               AND ((scope_type = 'TENANT' AND scope_id = :tenantId)
                    OR (scope_type = 'USER' AND scope_id = :userId))
            """;

    /** 读取指定用户集合的显式限额。 */
    private static final String FIND_USER_LIMITS = """
            SELECT tenant_id, scope_type, scope_id, window_kind, limit_count
              FROM quota_limit
             WHERE tenant_id = :tenantId
               AND scope_type = 'USER'
               AND scope_id IN (:scopeIds)
            """;

    /** 不含租户条件的限额查询由 PlatformQuotaJdbc 负责；这里只服务当前租户。 */
    private static final String FIND_SCOPE_LIMITS = """
            SELECT tenant_id, scope_type, scope_id, window_kind, limit_count
              FROM quota_limit
             WHERE tenant_id = :tenantId
               AND scope_type = :scopeType
               AND scope_id = :scopeId
            """;

    /** 确保窗口行存在；ON DUPLICATE 只取锁，不改变计数。 */
    private static final String ENSURE_USAGE_ROW = """
            INSERT INTO quota_usage (tenant_id, scope_type, scope_id, window_kind, window_start, used_count)
            VALUES (:tenantId, :scopeType, :scopeId, :windowKind, :windowStart, 0)
            ON DUPLICATE KEY UPDATE used_count = used_count
            """;

    /** 有限额时条件累加；affectedRows=0 表示已满。 */
    private static final String INCREMENT_CONDITIONAL = """
            UPDATE quota_usage
               SET used_count = used_count + 1, updated_at = :now
             WHERE tenant_id = :tenantId
               AND scope_type = :scopeType
               AND scope_id = :scopeId
               AND window_kind = :windowKind
               AND window_start = :windowStart
               AND used_count < :limit
            """;

    /** 不限量或关闭执行时无条件累加。 */
    private static final String INCREMENT_UNCONDITIONAL = """
            UPDATE quota_usage
               SET used_count = used_count + 1, updated_at = :now
             WHERE tenant_id = :tenantId
               AND scope_type = :scopeType
               AND scope_id = :scopeId
               AND window_kind = :windowKind
               AND window_start = :windowStart
            """;

    /** 读取单个窗口已用量。 */
    private static final String FIND_USED = """
            SELECT used_count
              FROM quota_usage
             WHERE tenant_id = :tenantId
               AND scope_type = :scopeType
               AND scope_id = :scopeId
               AND window_kind = :windowKind
               AND window_start = :windowStart
            """;

    /** 读取某一维度多个窗口的用量。 */
    private static final String FIND_USAGES = """
            SELECT tenant_id, scope_type, scope_id, window_kind, window_start, used_count
              FROM quota_usage
             WHERE tenant_id = :tenantId
               AND scope_type = :scopeType
               AND scope_id = :scopeId
               AND window_start IN (:windowStarts)
            """;

    /** 读取本租户用户分页。 */
    private static final String LIST_USERS = """
            SELECT id, username, display_name
              FROM app_user
             WHERE tenant_id = :tenantId
             ORDER BY created_at ASC, id ASC
             LIMIT :limit OFFSET :offset
            """;

    /** 统计本租户用户数。 */
    private static final String COUNT_USERS = "SELECT COUNT(*) FROM app_user WHERE tenant_id = :tenantId";

    /** 读取用户集合的用量。 */
    private static final String FIND_USER_USAGES = """
            SELECT tenant_id, scope_type, scope_id, window_kind, window_start, used_count
              FROM quota_usage
             WHERE tenant_id = :tenantId
               AND scope_type = 'USER'
               AND scope_id IN (:scopeIds)
               AND window_start IN (:windowStarts)
            """;

    /** 租户数据访问唯一强制点。 */
    private final TenantJdbc tenantJdbc;

    /** 仅用于读取无租户维度的全局开关。 */
    private final JdbcClient jdbcClient;

    /**
     * 创建配额 SQL 组件。
     *
     * @param tenantJdbc 租户数据访问
     * @param jdbcClient 全局开关读取
     */
    public QuotaJdbc(TenantJdbc tenantJdbc, JdbcClient jdbcClient) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    /**
     * 读取全局配额执行开关。
     *
     * @return 原始开关值；行缺失时为空
     */
    public Optional<String> findEnforcementSwitch() {
        return jdbcClient
                .sql(FIND_ENFORCEMENT_SWITCH)
                .param("switchKey", ENFORCEMENT_SWITCH_KEY)
                .query(String.class)
                .optional();
    }

    /**
     * 在当前事务内锁定租户行，作为准入串行点。
     *
     * @param tenantId 当前租户
     * @return 租户行存在时返回 true
     */
    public boolean lockTenant(TenantId tenantId) {
        return jdbcClient
                .sql(LOCK_TENANT)
                .param("tenantId", tenantId.value())
                .query(String.class)
                .optional()
                .isPresent();
    }

    /**
     * 读取租户级与当前用户级显式限额。
     *
     * @param tenantId 当前租户
     * @param userId 当前用户
     * @return 显式限额行
     */
    public List<QuotaLimitRow> findAdmissionLimits(TenantId tenantId, UUID userId) {
        return tenantJdbc.query(tenantId, FIND_LIMITS, Map.of("userId", userId.toString()), QuotaJdbc::mapLimit);
    }

    /**
     * 读取指定维度与 scopeId 的显式限额。
     *
     * @param tenantId 当前租户
     * @param scope 维度
     * @param scopeId 维度标识
     * @return 显式限额行
     */
    public List<QuotaLimitRow> findLimits(TenantId tenantId, QuotaScope scope, String scopeId) {
        return tenantJdbc.query(
                tenantId,
                FIND_SCOPE_LIMITS,
                Map.of("scopeType", scope.name(), "scopeId", scopeId),
                QuotaJdbc::mapLimit);
    }

    /**
     * 读取用户集合的显式限额。
     *
     * @param tenantId 当前租户
     * @param scopeIds 用户 ID 字符串
     * @return 显式限额行
     */
    public List<QuotaLimitRow> findUserLimits(TenantId tenantId, List<String> scopeIds) {
        if (scopeIds.isEmpty()) {
            return List.of();
        }
        return tenantJdbc.query(tenantId, FIND_USER_LIMITS, Map.of("scopeIds", scopeIds), QuotaJdbc::mapLimit);
    }

    /**
     * 确保窗口行存在并持有排他行锁。
     *
     * @param tenantId 当前租户
     * @param scope 维度
     * @param scopeId 维度标识
     * @param window 窗口
     * @param windowStart 窗口起点（UTC）
     */
    public void ensureUsageRow(
            TenantId tenantId, QuotaScope scope, String scopeId, QuotaWindow window, Instant windowStart) {
        tenantJdbc.insert(tenantId, ENSURE_USAGE_ROW, usageParams(tenantId, scope, scopeId, window, windowStart));
    }

    /**
     * 有限额时条件累加。
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
        Map<String, Object> params = usageParams(tenantId, scope, scopeId, window, windowStart);
        params.put("limit", limit);
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, INCREMENT_CONDITIONAL, params);
    }

    /**
     * 不限量或关闭执行时无条件累加。
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
        Map<String, Object> params = usageParams(tenantId, scope, scopeId, window, windowStart);
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, INCREMENT_UNCONDITIONAL, params);
    }

    /**
     * 读取单个窗口已用量。
     *
     * @param tenantId 当前租户
     * @param scope 维度
     * @param scopeId 维度标识
     * @param window 窗口
     * @param windowStart 窗口起点
     * @return 已用量；行不存在时为空
     */
    public Optional<Integer> findUsed(
            TenantId tenantId, QuotaScope scope, String scopeId, QuotaWindow window, Instant windowStart) {
        return tenantJdbc.queryOne(
                tenantId,
                FIND_USED,
                usageParams(tenantId, scope, scopeId, window, windowStart),
                (rs, rowNum) -> rs.getInt("used_count"));
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
        if (windowStarts.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("scopeType", scope.name());
        params.put("scopeId", scopeId);
        params.put(
                "windowStarts",
                windowStarts.stream().map(SqlTimestamps::toDatabase).toList());
        return tenantJdbc.query(tenantId, FIND_USAGES, params, QuotaJdbc::mapUsage);
    }

    /**
     * 读取用户集合的多窗口用量。
     *
     * @param tenantId 当前租户
     * @param scopeIds 用户 ID 字符串
     * @param windowStarts 窗口起点集合
     * @return 用量行
     */
    public List<QuotaUsageRow> findUserUsages(TenantId tenantId, List<String> scopeIds, List<Instant> windowStarts) {
        if (scopeIds.isEmpty() || windowStarts.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("scopeIds", scopeIds);
        params.put(
                "windowStarts",
                windowStarts.stream().map(SqlTimestamps::toDatabase).toList());
        return tenantJdbc.query(tenantId, FIND_USER_USAGES, params, QuotaJdbc::mapUsage);
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
        return tenantJdbc.query(
                tenantId,
                LIST_USERS,
                Map.of("limit", limit, "offset", offset),
                (rs, rowNum) -> new QuotaUser(
                        BinaryUuids.fromBytes(rs.getBytes("id")),
                        rs.getString("username"),
                        rs.getString("display_name")));
    }

    /**
     * 统计本租户用户数。
     *
     * @param tenantId 当前租户
     * @return 用户数
     */
    public long countUsers(TenantId tenantId) {
        return tenantJdbc.count(tenantId, COUNT_USERS, Map.of());
    }

    /**
     * 组装窗口参数。
     *
     * @param tenantId 当前租户
     * @param scope 维度
     * @param scopeId 维度标识
     * @param window 窗口
     * @param windowStart 窗口起点
     * @return 可变参数映射（调用方可能追加 limit/now）
     */
    private static Map<String, Object> usageParams(
            TenantId tenantId, QuotaScope scope, String scopeId, QuotaWindow window, Instant windowStart) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("scopeType", scope.name());
        params.put("scopeId", scopeId);
        params.put("windowKind", window.name());
        params.put("windowStart", SqlTimestamps.toDatabase(windowStart));
        return params;
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
