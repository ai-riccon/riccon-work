package com.riccon.worker.persistence;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Worker 唯一的租户数据访问强制点。
 *
 * <p>所有读写必须显式传入 {@link TenantId}，且 SQL 必须包含 {@code tenant_id = :tenantId}
 * 条件；INSERT 必须包含 {@code tenant_id} 列。缺失条件直接失败，不降级为全租户查询。
 * 跨租户只有 outbox 领取例外，见 {@code OutboxClaimer}，不得复用本类。
 */
@Component
public final class TenantJdbc {

    /** 查询、更新、删除必须包含的租户条件。 */
    private static final Pattern TENANT_PREDICATE = Pattern.compile("(?i)\\btenant_id\\s*=\\s*:tenantId\\b");

    /** INSERT 语句必须把 tenant_id 列入列清单。 */
    private static final Pattern INSERT_TENANT_COLUMN =
            Pattern.compile("(?is)\\binsert\\s+into\\s+[\\w.`]+\\s*\\([^)]*\\btenant_id\\b[^)]*\\)");

    /** 底层 JDBC 客户端，只允许在本类与 outbox 领取器中使用。 */
    private final JdbcClient jdbcClient;

    /**
     * 创建租户数据访问层。
     *
     * @param jdbcClient Spring JDBC 客户端
     */
    public TenantJdbc(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    /**
     * 执行租户查询。
     *
     * @param tenantId 当前租户
     * @param sql 必须包含 tenant_id = :tenantId 的 SQL
     * @param params 其他命名参数
     * @param mapper 行映射器
     * @param <T> 行类型
     * @return 查询结果，绝不返回 null
     */
    public <T> List<T> query(TenantId tenantId, String sql, Map<String, ?> params, RowMapper<T> mapper) {
        requireTenantMatch(tenantId);
        requireTenantPredicate(sql);
        return jdbcClient
                .sql(sql)
                .params(parameters(tenantId, params))
                .query(mapper)
                .list();
    }

    /**
     * 执行租户单行查询。
     *
     * @param tenantId 当前租户
     * @param sql 必须包含 tenant_id = :tenantId 的 SQL
     * @param params 其他命名参数
     * @param mapper 行映射器
     * @param <T> 行类型
     * @return 单行结果；不存在时为空
     */
    public <T> Optional<T> queryOne(TenantId tenantId, String sql, Map<String, ?> params, RowMapper<T> mapper) {
        requireTenantMatch(tenantId);
        requireTenantPredicate(sql);
        return jdbcClient
                .sql(sql)
                .params(parameters(tenantId, params))
                .query(mapper)
                .optional();
    }

    /**
     * 执行租户更新或删除。
     *
     * @param tenantId 当前租户
     * @param sql 必须包含 tenant_id = :tenantId 的 SQL
     * @param params 其他命名参数
     * @return 受影响行数
     */
    public int update(TenantId tenantId, String sql, Map<String, ?> params) {
        requireTenantMatch(tenantId);
        requireTenantPredicate(sql);
        return jdbcClient.sql(sql).params(parameters(tenantId, params)).update();
    }

    /**
     * 执行租户插入。
     *
     * @param tenantId 当前租户
     * @param sql 必须包含 tenant_id 列与 :tenantId 参数的 INSERT
     * @param params 其他命名参数
     * @return 受影响行数
     */
    public int insert(TenantId tenantId, String sql, Map<String, ?> params) {
        requireTenantMatch(tenantId);
        if (!INSERT_TENANT_COLUMN.matcher(sql).find() || !sql.contains(":tenantId")) {
            throw new IllegalStateException("insert SQL must include tenant_id column and :tenantId parameter");
        }
        return jdbcClient.sql(sql).params(parameters(tenantId, params)).update();
    }

    /**
     * 校验传入租户与当前线程上下文一致，防止调用方传错租户造成越权。
     *
     * @param tenantId 调用方声明的租户
     */
    private static void requireTenantMatch(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        TenantId current = TenantContext.require();
        if (!current.equals(tenantId)) {
            throw new IllegalStateException("tenant context does not match requested tenant");
        }
    }

    /**
     * 校验非 INSERT SQL 带租户条件。
     *
     * @param sql 待执行 SQL
     */
    private static void requireTenantPredicate(String sql) {
        if (sql == null || !TENANT_PREDICATE.matcher(sql).find()) {
            throw new IllegalStateException("SQL must include tenant_id = :tenantId predicate");
        }
    }

    /**
     * 合并参数并强制写入当前租户，覆盖调用方可能传入的 tenantId。
     *
     * @param tenantId 当前租户
     * @param params 调用方参数
     * @return 新参数映射
     */
    private static Map<String, Object> parameters(TenantId tenantId, Map<String, ?> params) {
        Map<String, Object> merged = new HashMap<>();
        if (params != null) {
            merged.putAll(params);
        }
        merged.put("tenantId", tenantId.value());
        return merged;
    }
}
