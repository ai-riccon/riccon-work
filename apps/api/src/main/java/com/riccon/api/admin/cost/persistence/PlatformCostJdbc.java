package com.riccon.api.admin.cost.persistence;

import com.riccon.api.admin.persistence.PlatformJdbc;
import com.riccon.api.cost.domain.CostBucketRow;
import com.riccon.api.cost.domain.CostRange;
import com.riccon.api.cost.domain.CostTotalsRow;
import com.riccon.api.cost.domain.PlatformCostGroupBy;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 平台跨租户成本 SQL。
 *
 * <p>只有平台管理员路径使用 {@link PlatformJdbc}；查询只读 {@code model_invocation} 与全局模型目录，
 * **不 join** {@code app_user}，因此不会返回用户维度。可选 {@code tenantId} 过滤仍是聚合视图。
 * 单条只读查询使用 MySQL {@code MAX_EXECUTION_TIME(3000)} 提示限制 3 秒，超时由服务层映射为 503。
 */
@Component
public class PlatformCostJdbc {

    /** 查询保护：单条只读 SQL 最大执行 3000 ms。 */
    private static final String TIMEOUT_HINT = "/*+ MAX_EXECUTION_TIME(3000) */";

    /** 终态过滤。 */
    private static final String TERMINAL_STATUSES = "('SUCCEEDED', 'FAILED', 'CANCELLED')";

    /** 桶内金额与三类计数。 */
    private static final String BUCKET_AGGREGATES = """
            COALESCE(SUM(CASE WHEN mi.cost_status = 'PRICED' AND mi.price_currency = :currency
                              THEN mi.cost_amount ELSE 0 END), 0) AS amount,
            COALESCE(SUM(CASE WHEN mi.cost_status = 'PRICED' AND mi.price_currency = :currency
                              THEN 1 ELSE 0 END), 0) AS priced_calls,
            COALESCE(SUM(CASE WHEN mi.cost_status = 'UNPRICED' THEN 1 ELSE 0 END), 0) AS unpriced_calls,
            COALESCE(SUM(CASE WHEN mi.cost_status = 'USAGE_UNKNOWN' THEN 1 ELSE 0 END), 0) AS usage_unknown_calls
            """;

    /** 平台数据访问入口。 */
    private final PlatformJdbc platformJdbc;

    /**
     * 创建平台成本 SQL 组件。
     *
     * @param platformJdbc 平台数据访问入口
     */
    public PlatformCostJdbc(PlatformJdbc platformJdbc) {
        this.platformJdbc = Objects.requireNonNull(platformJdbc, "platformJdbc must not be null");
    }

    /**
     * 查询跨租户合计。
     *
     * @param range 时间范围
     * @param currency 部署币种
     * @param tenantId 可选租户过滤
     * @return 合计
     */
    public CostTotalsRow totals(CostRange range, String currency, String tenantId) {
        Map<String, Object> params = baseParams(range);
        params.put("currency", currency);
        String sql = "SELECT " + TIMEOUT_HINT + """
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
                 WHERE mi.status IN %s
                   AND mi.started_at >= :fromInstant
                   AND mi.started_at < :toInstant
                """.formatted(TERMINAL_STATUSES) + tenantFilter(tenantId, params);
        return platformJdbc
                .queryOneGlobal(sql, params, PlatformCostJdbc::mapTotals)
                .orElse(new CostTotalsRow(java.math.BigDecimal.ZERO, 0, 0, 0, 0, 0));
    }

    /**
     * 查询跨租户分页桶。
     *
     * @param range 时间范围
     * @param groupBy 聚合维度
     * @param currency 部署币种
     * @param tenantId 可选租户过滤
     * @param limit 每页大小
     * @param offset 偏移
     * @return 桶列表
     */
    public List<CostBucketRow> buckets(
            CostRange range, PlatformCostGroupBy groupBy, String currency, String tenantId, int limit, int offset) {
        Map<String, Object> params = baseParams(range);
        params.put("currency", currency);
        params.put("limit", limit);
        params.put("offset", offset);
        String sql = bucketSql(groupBy, tenantFilter(tenantId, params));
        return platformJdbc.queryGlobal(sql, params, (rs, rowNum) -> mapBucket(rs, groupBy));
    }

    /**
     * 统计跨租户桶总数。
     *
     * @param range 时间范围
     * @param groupBy 聚合维度
     * @param tenantId 可选租户过滤
     * @return 桶总数
     */
    public long bucketCount(CostRange range, PlatformCostGroupBy groupBy, String tenantId) {
        Map<String, Object> params = baseParams(range);
        String expression = switch (groupBy) {
            case TENANT -> "mi.tenant_id";
            case MODEL -> "mi.model_id";
            case DAY -> "mi.cost_day";
        };
        String sql = "SELECT "
                + TIMEOUT_HINT
                + " COUNT(DISTINCT "
                + expression
                + ") AS bucket_count FROM model_invocation mi"
                + " WHERE mi.status IN "
                + TERMINAL_STATUSES
                + " AND mi.started_at >= :fromInstant"
                + " AND mi.started_at < :toInstant"
                + " AND mi.cost_day IS NOT NULL"
                + tenantFilter(tenantId, params);
        return platformJdbc
                .queryOneGlobal(sql, params, (rs, rowNum) -> rs.getLong("bucket_count"))
                .orElse(0L);
    }

    /**
     * 按维度构造桶 SQL。
     *
     * @param groupBy 维度
     * @param tenantCondition 租户过滤片段
     * @return SQL
     */
    private static String bucketSql(PlatformCostGroupBy groupBy, String tenantCondition) {
        String select;
        String groupByClause;
        String join = "";
        switch (groupBy) {
            case TENANT -> {
                select = "mi.tenant_id AS bucket_key,";
                groupByClause = "GROUP BY mi.tenant_id";
            }
            case MODEL -> {
                select = "mi.model_id AS bucket_key, m.display_name AS model_display_name,";
                groupByClause = "GROUP BY mi.model_id, m.display_name";
                join = " JOIN model m ON m.id = mi.model_id";
            }
            case DAY -> {
                select = "DATE_FORMAT(mi.cost_day, '%Y-%m-%d') AS bucket_key,";
                groupByClause = "GROUP BY DATE_FORMAT(mi.cost_day, '%Y-%m-%d')";
            }
            default -> throw new IllegalStateException("unsupported groupBy: " + groupBy);
        }
        return "SELECT "
                + TIMEOUT_HINT
                + " "
                + select
                + BUCKET_AGGREGATES
                + " FROM model_invocation mi"
                + join
                + " WHERE mi.status IN "
                + TERMINAL_STATUSES
                + " AND mi.started_at >= :fromInstant"
                + " AND mi.started_at < :toInstant"
                + " AND mi.cost_day IS NOT NULL"
                + tenantCondition
                + " "
                + groupByClause
                + " ORDER BY amount DESC, bucket_key ASC LIMIT :limit OFFSET :offset";
    }

    /**
     * 可选租户过滤；参数绑定，不拼接用户输入。
     *
     * @param tenantId 租户代码，可为空
     * @param params 参数
     * @return SQL 片段
     */
    private static String tenantFilter(String tenantId, Map<String, Object> params) {
        if (tenantId == null) {
            return "";
        }
        params.put("tenantId", tenantId);
        return " AND mi.tenant_id = :tenantId";
    }

    /**
     * 构造基础参数。
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
     * 映射平台桶；不读取任何用户列。
     *
     * @param rs 结果集
     * @param groupBy 维度
     * @return 桶
     * @throws SQLException 列读取失败
     */
    private static CostBucketRow mapBucket(ResultSet rs, PlatformCostGroupBy groupBy) throws SQLException {
        String key;
        String tenantId = null;
        String modelId = null;
        String modelDisplayName = null;
        String day = null;
        switch (groupBy) {
            case TENANT -> {
                tenantId = rs.getString("bucket_key");
                key = tenantId;
            }
            case MODEL -> {
                UUID id = BinaryUuids.fromBytes(rs.getBytes("bucket_key"));
                key = id == null ? null : id.toString();
                modelId = key;
                modelDisplayName = rs.getString("model_display_name");
            }
            case DAY -> {
                key = rs.getString("bucket_key");
                day = key;
            }
            default -> throw new IllegalStateException("unsupported groupBy: " + groupBy);
        }
        return new CostBucketRow(
                key,
                tenantId,
                modelId,
                modelDisplayName,
                null,
                null,
                day,
                rs.getBigDecimal("amount"),
                rs.getLong("priced_calls"),
                rs.getLong("unpriced_calls"),
                rs.getLong("usage_unknown_calls"));
    }
}
