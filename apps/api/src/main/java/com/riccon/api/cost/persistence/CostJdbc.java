package com.riccon.api.cost.persistence;

import com.riccon.api.cost.domain.CostBucketRow;
import com.riccon.api.cost.domain.CostInvocationRow;
import com.riccon.api.cost.domain.CostRange;
import com.riccon.api.cost.domain.CostTotalsRow;
import com.riccon.api.cost.domain.TenantCostGroupBy;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 租户成本聚合与明细 SQL。
 *
 * <p>所有查询都经过 {@link TenantJdbc} 强制 {@code tenant_id = :tenantId}；时间范围按
 * {@code Asia/Shanghai} 自然日转换为 UTC 半开区间。MySQL 8.4 使用
 * {@code /*+ MAX_EXECUTION_TIME(3000) *}{@code /} 优化器提示限制单条只读查询 3 秒，
 * 超时由服务层映射为 503，不返回部分结果（ADR-0022 §2.5）。
 *
 * <p>统计口径：只统计终态调用（SUCCEEDED/FAILED/CANCELLED）；{@code cost_day IS NULL} 的历史/回滚窗口
 * 行只计入 totals 的 {@code unbucketedCalls}，不进入 items。金额只汇总 PRICED 且币种匹配的调用。
 */
@Component
public class CostJdbc {

    /** 查询保护：单条只读 SQL 最大执行 3000 ms。 */
    private static final String TIMEOUT_HINT = "/*+ MAX_EXECUTION_TIME(3000) */";

    /** 终态过滤；STARTED 尚未产生成本，不计入报告。 */
    private static final String TERMINAL_STATUSES = "('SUCCEEDED', 'FAILED', 'CANCELLED')";

    /** 金额与三类状态计数；仅用于 cost_day 非空的桶。 */
    private static final String BUCKET_AGGREGATES = """
            COALESCE(SUM(CASE WHEN mi.cost_status = 'PRICED' AND mi.price_currency = :currency
                              THEN mi.cost_amount ELSE 0 END), 0) AS amount,
            COALESCE(SUM(CASE WHEN mi.cost_status = 'PRICED' AND mi.price_currency = :currency
                              THEN 1 ELSE 0 END), 0) AS priced_calls,
            COALESCE(SUM(CASE WHEN mi.cost_status = 'UNPRICED' THEN 1 ELSE 0 END), 0) AS unpriced_calls,
            COALESCE(SUM(CASE WHEN mi.cost_status = 'USAGE_UNKNOWN' THEN 1 ELSE 0 END), 0) AS usage_unknown_calls
            """;

    /** 合计：五个互斥计数覆盖所有终态调用，未分桶与币种不一致都不计金额。 */
    private static final String TOTALS_SQL = """
            SELECT %s
                   COALESCE(SUM(CASE WHEN mi.cost_day IS NOT NULL
                                      AND mi.cost_status = 'PRICED'
                                      AND mi.price_currency = :currency
                                     THEN mi.cost_amount ELSE 0 END), 0) AS amount,
                   COALESCE(SUM(CASE WHEN mi.cost_day IS NOT NULL
                                      AND mi.cost_status = 'PRICED'
                                      AND mi.price_currency = :currency
                                     THEN 1 ELSE 0 END), 0) AS priced_calls,
                   COALESCE(SUM(CASE WHEN mi.cost_day IS NOT NULL
                                      AND mi.cost_status = 'UNPRICED'
                                     THEN 1 ELSE 0 END), 0) AS unpriced_calls,
                   COALESCE(SUM(CASE WHEN mi.cost_day IS NOT NULL
                                      AND mi.cost_status = 'USAGE_UNKNOWN'
                                     THEN 1 ELSE 0 END), 0) AS usage_unknown_calls,
                   COALESCE(SUM(CASE WHEN mi.cost_day IS NOT NULL
                                      AND mi.cost_status = 'PRICED'
                                      AND (mi.price_currency IS NULL OR mi.price_currency <> :currency)
                                     THEN 1 ELSE 0 END), 0) AS currency_mismatch_calls,
                   COALESCE(SUM(CASE WHEN mi.cost_day IS NULL THEN 1 ELSE 0 END), 0) AS unbucketed_calls
              FROM model_invocation mi
             WHERE mi.tenant_id = :tenantId
               AND mi.status IN %s
               AND mi.started_at >= :fromInstant
               AND mi.started_at < :toInstant
            """.formatted(TIMEOUT_HINT, TERMINAL_STATUSES);

    /** 按日分桶。 */
    private static final String DAY_BUCKETS_SQL = """
            SELECT %s
                   DATE_FORMAT(mi.cost_day, '%%Y-%%m-%%d') AS bucket_key,
                   %s
              FROM model_invocation mi
             WHERE mi.tenant_id = :tenantId
               AND mi.status IN %s
               AND mi.started_at >= :fromInstant
               AND mi.started_at < :toInstant
               AND mi.cost_day IS NOT NULL
             GROUP BY DATE_FORMAT(mi.cost_day, '%%Y-%%m-%%d')
             ORDER BY amount DESC, bucket_key ASC
             LIMIT :limit OFFSET :offset
            """.formatted(TIMEOUT_HINT, BUCKET_AGGREGATES, TERMINAL_STATUSES);

    /** 按模型分桶；模型展示名来自全局目录，价格不参与查询。 */
    private static final String MODEL_BUCKETS_SQL = """
            SELECT %s
                   mi.model_id AS bucket_key,
                   m.display_name AS model_display_name,
                   %s
              FROM model_invocation mi
              JOIN model m ON m.id = mi.model_id
             WHERE mi.tenant_id = :tenantId
               AND mi.status IN %s
               AND mi.started_at >= :fromInstant
               AND mi.started_at < :toInstant
               AND mi.cost_day IS NOT NULL
             GROUP BY mi.model_id, m.display_name
             ORDER BY amount DESC, bucket_key ASC
             LIMIT :limit OFFSET :offset
            """.formatted(TIMEOUT_HINT, BUCKET_AGGREGATES, TERMINAL_STATUSES);

    /** 按用户分桶；只返回本租户账号，user_id 为空的历史行不进入 USER 桶。 */
    private static final String USER_BUCKETS_SQL = """
            SELECT %s
                   mi.user_id AS bucket_key,
                   u.display_name AS user_display_name,
                   %s
              FROM model_invocation mi
              LEFT JOIN app_user u ON u.tenant_id = mi.tenant_id AND u.id = mi.user_id
             WHERE mi.tenant_id = :tenantId
               AND mi.status IN %s
               AND mi.started_at >= :fromInstant
               AND mi.started_at < :toInstant
               AND mi.cost_day IS NOT NULL
               AND mi.user_id IS NOT NULL
             GROUP BY mi.user_id, u.display_name
             ORDER BY amount DESC, bucket_key ASC
             LIMIT :limit OFFSET :offset
            """.formatted(TIMEOUT_HINT, BUCKET_AGGREGATES, TERMINAL_STATUSES);

    /** 桶总数前缀，按维度拼接后缀。 */
    private static final String BUCKET_COUNT_PREFIX = """
            SELECT %s COUNT(DISTINCT %s) AS bucket_count
              FROM model_invocation mi
             WHERE mi.tenant_id = :tenantId
               AND mi.status IN %s
               AND mi.started_at >= :fromInstant
               AND mi.started_at < :toInstant
               AND mi.cost_day IS NOT NULL
            """;

    /** 明细列；金额与价格只读快照列。 */
    private static final String INVOCATION_COLUMNS = """
            SELECT mi.id, mi.model_id,
                   COALESCE(m.display_name, mi.model_key_snapshot) AS model_display_name,
                   mi.user_id, u.display_name AS user_display_name, mi.started_at,
                   mi.prompt_tokens, mi.completion_tokens,
                   mi.input_price_per_1m, mi.output_price_per_1m, mi.price_currency,
                   mi.cost_amount, mi.cost_status
              FROM model_invocation mi
              JOIN model m ON m.id = mi.model_id
              LEFT JOIN app_user u ON u.tenant_id = mi.tenant_id AND u.id = mi.user_id
             WHERE mi.tenant_id = :tenantId
               AND mi.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
               AND mi.started_at >= :fromInstant
               AND mi.started_at < :toInstant
            """;

    /** 租户数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建成本 SQL 组件。
     *
     * @param tenantJdbc 租户数据访问入口
     */
    public CostJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 查询合计。
     *
     * @param tenantId 当前租户
     * @param range 时间范围
     * @param currency 部署币种
     * @return 合计
     */
    public CostTotalsRow totals(TenantId tenantId, CostRange range, String currency) {
        Map<String, Object> params = baseParams(range);
        params.put("currency", currency);
        return tenantJdbc
                .queryOne(tenantId, TOTALS_SQL, params, CostJdbc::mapTotals)
                .orElse(new CostTotalsRow(java.math.BigDecimal.ZERO, 0, 0, 0, 0, 0));
    }

    /**
     * 查询分页桶。
     *
     * @param tenantId 当前租户
     * @param range 时间范围
     * @param groupBy 聚合维度
     * @param currency 部署币种
     * @param limit 每页大小
     * @param offset 偏移
     * @return 桶列表
     */
    public List<CostBucketRow> buckets(
            TenantId tenantId, CostRange range, TenantCostGroupBy groupBy, String currency, int limit, int offset) {
        Map<String, Object> params = baseParams(range);
        params.put("currency", currency);
        params.put("limit", limit);
        params.put("offset", offset);
        String sql = switch (groupBy) {
            case DAY -> DAY_BUCKETS_SQL;
            case MODEL -> MODEL_BUCKETS_SQL;
            case USER -> USER_BUCKETS_SQL;
        };
        return tenantJdbc.query(tenantId, sql, params, (rs, rowNum) -> mapBucket(rs, groupBy));
    }

    /**
     * 统计桶总数。
     *
     * @param tenantId 当前租户
     * @param range 时间范围
     * @param groupBy 聚合维度
     * @return 桶总数
     */
    public long bucketCount(TenantId tenantId, CostRange range, TenantCostGroupBy groupBy) {
        String expression = switch (groupBy) {
            case DAY -> "mi.cost_day";
            case MODEL -> "mi.model_id";
            case USER -> "mi.user_id";
        };
        String userFilter = groupBy == TenantCostGroupBy.USER ? " AND mi.user_id IS NOT NULL" : "";
        String sql = BUCKET_COUNT_PREFIX.formatted(TIMEOUT_HINT, expression, TERMINAL_STATUSES) + userFilter;
        return tenantJdbc.count(tenantId, sql, baseParams(range));
    }

    /**
     * 查询分页明细。
     *
     * @param tenantId 当前租户
     * @param range 时间范围
     * @param userId 用户过滤，可为空
     * @param modelId 模型过滤，可为空
     * @param limit 每页大小
     * @param offset 偏移
     * @return 明细行
     */
    public List<CostInvocationRow> invocations(
            TenantId tenantId, CostRange range, UUID userId, UUID modelId, int limit, int offset) {
        Map<String, Object> params = baseParams(range);
        String sql = INVOCATION_COLUMNS + invocationFilters(userId, modelId)
                + " ORDER BY mi.started_at DESC, mi.id DESC LIMIT :limit OFFSET :offset";
        addInvocationFilters(params, userId, modelId);
        params.put("limit", limit);
        params.put("offset", offset);
        return tenantJdbc.query(tenantId, sql, params, CostJdbc::mapInvocation);
    }

    /**
     * 统计明细总数。
     *
     * @param tenantId 当前租户
     * @param range 时间范围
     * @param userId 用户过滤，可为空
     * @param modelId 模型过滤，可为空
     * @return 总行数
     */
    public long invocationCount(TenantId tenantId, CostRange range, UUID userId, UUID modelId) {
        Map<String, Object> params = baseParams(range);
        String sql = "SELECT " + TIMEOUT_HINT + " COUNT(*) FROM model_invocation mi"
                + " WHERE mi.tenant_id = :tenantId"
                + " AND mi.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')"
                + " AND mi.started_at >= :fromInstant"
                + " AND mi.started_at < :toInstant"
                + invocationFilters(userId, modelId);
        addInvocationFilters(params, userId, modelId);
        return tenantJdbc.count(tenantId, sql, params);
    }

    /**
     * 判断用户是否属于本租户。
     *
     * @param tenantId 当前租户
     * @param userId 用户 ID
     * @return 是否存在
     */
    public boolean userExists(TenantId tenantId, UUID userId) {
        long count = tenantJdbc.count(
                tenantId,
                "SELECT COUNT(*) FROM app_user WHERE tenant_id = :tenantId AND id = :id",
                Map.of("id", BinaryUuids.toBytes(userId)));
        return count > 0;
    }

    /**
     * 构造基础参数；时间统一转 UTC。
     *
     * @param range 时间范围
     * @return 参数
     */
    private static Map<String, Object> baseParams(CostRange range) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fromInstant", SqlTimestamps.toDatabase(range.fromInstant()));
        params.put("toInstant", SqlTimestamps.toDatabase(range.toExclusiveInstant()));
        return params;
    }

    /**
     * 明细过滤条件片段；只拼接固定 SQL，用户输入全部参数绑定。
     *
     * @param userId 用户过滤
     * @param modelId 模型过滤
     * @return SQL 片段
     */
    private static String invocationFilters(UUID userId, UUID modelId) {
        StringBuilder sql = new StringBuilder();
        if (userId != null) {
            sql.append(" AND mi.user_id = :userId");
        }
        if (modelId != null) {
            sql.append(" AND mi.model_id = :modelId");
        }
        return sql.toString();
    }

    /**
     * 绑定明细过滤参数。
     *
     * @param params 参数
     * @param userId 用户过滤
     * @param modelId 模型过滤
     */
    private static void addInvocationFilters(Map<String, Object> params, UUID userId, UUID modelId) {
        if (userId != null) {
            params.put("userId", BinaryUuids.toBytes(userId));
        }
        if (modelId != null) {
            params.put("modelId", BinaryUuids.toBytes(modelId));
        }
    }

    /**
     * 映射合计。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 合计
     * @throws SQLException 列读取失败
     */
    private static CostTotalsRow mapTotals(ResultSet rs, int rowNum) throws SQLException {
        return new CostTotalsRow(
                rs.getBigDecimal("amount"),
                rs.getLong("priced_calls"),
                rs.getLong("unpriced_calls"),
                rs.getLong("usage_unknown_calls"),
                rs.getLong("currency_mismatch_calls"),
                rs.getLong("unbucketed_calls"));
    }

    /**
     * 映射分桶行；不同维度只填充对应字段。
     *
     * @param rs 结果集
     * @param groupBy 聚合维度
     * @return 分桶行
     * @throws SQLException 列读取失败
     */
    private static CostBucketRow mapBucket(ResultSet rs, TenantCostGroupBy groupBy) throws SQLException {
        String key;
        String modelId = null;
        String modelDisplayName = null;
        String userId = null;
        String userDisplayName = null;
        String day = null;
        switch (groupBy) {
            case DAY -> {
                key = rs.getString("bucket_key");
                day = key;
            }
            case MODEL -> {
                UUID id = BinaryUuids.fromBytes(rs.getBytes("bucket_key"));
                key = id == null ? null : id.toString();
                modelId = key;
                modelDisplayName = rs.getString("model_display_name");
            }
            case USER -> {
                UUID id = BinaryUuids.fromBytes(rs.getBytes("bucket_key"));
                key = id == null ? null : id.toString();
                userId = key;
                userDisplayName = rs.getString("user_display_name");
            }
            default -> throw new IllegalStateException("unsupported groupBy: " + groupBy);
        }
        return new CostBucketRow(
                key,
                null,
                modelId,
                modelDisplayName,
                userId,
                userDisplayName,
                day,
                rs.getBigDecimal("amount"),
                rs.getLong("priced_calls"),
                rs.getLong("unpriced_calls"),
                rs.getLong("usage_unknown_calls"));
    }

    /**
     * 映射明细行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 明细行
     * @throws SQLException 列读取失败
     */
    private static CostInvocationRow mapInvocation(ResultSet rs, int rowNum) throws SQLException {
        LocalDateTime startedAt = rs.getObject("started_at", LocalDateTime.class);
        return new CostInvocationRow(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                BinaryUuids.fromBytes(rs.getBytes("model_id")),
                rs.getString("model_display_name"),
                BinaryUuids.fromBytes(rs.getBytes("user_id")),
                rs.getString("user_display_name"),
                SqlTimestamps.toInstant(startedAt),
                rs.getObject("prompt_tokens", Integer.class),
                rs.getObject("completion_tokens", Integer.class),
                rs.getBigDecimal("input_price_per_1m"),
                rs.getBigDecimal("output_price_per_1m"),
                rs.getString("price_currency"),
                rs.getBigDecimal("cost_amount"),
                rs.getString("cost_status"));
    }
}
