package com.riccon.api.admin.persistence;

import com.riccon.platform.common.tenant.TenantId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 平台管理与全局表的 JDBC 入口。
 *
 * <p>ADR-0009 只允许 {@code ..admin..} 包使用本类：平台管理员需要跨租户管理租户、账号和角色，普通业务代码
 * 必须使用 {@link com.riccon.api.persistence.TenantJdbc}。跨租户方法必须显式传入目标租户，SQL 必须包含
 * {@code tenant_id = :tenantId}；全局表方法只用于 {@code tenant}/{@code permission} 等平台目录。
 */
@Component
public class PlatformJdbc {

    /** 租户条件片段。 */
    private static final String TENANT_CONDITION = "tenant_id = :tenantid";

    /** Spring JDBC 客户端。 */
    private final JdbcClient jdbcClient;

    /**
     * 创建平台管理 JDBC 入口。
     *
     * @param jdbcClient Spring JDBC 客户端
     */
    public PlatformJdbc(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    /**
     * 查询全局表。
     *
     * @param sql 不允许包含租户过滤的全局表 SQL
     * @param params 命名参数
     * @param rowMapper 行映射
     * @param <T> 行类型
     * @return 查询结果
     */
    public <T> List<T> queryGlobal(String sql, Map<String, Object> params, RowMapper<T> rowMapper) {
        return jdbcClient.sql(sql).params(params).query(rowMapper).list();
    }

    /**
     * 查询全局表单行。
     *
     * @param sql 全局表 SQL
     * @param params 命名参数
     * @param rowMapper 行映射
     * @param <T> 行类型
     * @return 查询结果
     */
    public <T> Optional<T> queryOneGlobal(String sql, Map<String, Object> params, RowMapper<T> rowMapper) {
        return jdbcClient.sql(sql).params(params).query(rowMapper).optional();
    }

    /**
     * 统计全局表行数。
     *
     * @param sql 全局表 SQL
     * @param params 命名参数
     * @return 行数
     */
    public long countGlobal(String sql, Map<String, Object> params) {
        Long value = jdbcClient.sql(sql).params(params).query(Long.class).single();
        return value == null ? 0L : value;
    }

    /**
     * 更新全局表。
     *
     * @param sql 全局表 SQL
     * @param params 命名参数
     * @return 受影响行数
     */
    public int updateGlobal(String sql, Map<String, Object> params) {
        return jdbcClient.sql(sql).params(params).update();
    }

    /**
     * 插入全局表。
     *
     * @param sql 全局表 SQL
     * @param params 命名参数
     * @return 受影响行数
     */
    public int insertGlobal(String sql, Map<String, Object> params) {
        return jdbcClient.sql(sql).params(params).update();
    }

    /**
     * 查询指定租户的数据。
     *
     * @param tenantId 目标租户
     * @param sql 必须包含 {@code tenant_id = :tenantId}
     * @param params 命名参数
     * @param rowMapper 行映射
     * @param <T> 行类型
     * @return 查询结果
     */
    public <T> List<T> queryTenant(TenantId tenantId, String sql, Map<String, Object> params, RowMapper<T> rowMapper) {
        requireTenantCondition(sql);
        return jdbcClient
                .sql(sql)
                .params(bindTenant(tenantId, params))
                .query(rowMapper)
                .list();
    }

    /**
     * 查询指定租户的单行数据。
     *
     * @param tenantId 目标租户
     * @param sql 必须包含 {@code tenant_id = :tenantId}
     * @param params 命名参数
     * @param rowMapper 行映射
     * @param <T> 行类型
     * @return 查询结果
     */
    public <T> Optional<T> queryOneTenant(
            TenantId tenantId, String sql, Map<String, Object> params, RowMapper<T> rowMapper) {
        requireTenantCondition(sql);
        return jdbcClient
                .sql(sql)
                .params(bindTenant(tenantId, params))
                .query(rowMapper)
                .optional();
    }

    /**
     * 统计指定租户的行数。
     *
     * @param tenantId 目标租户
     * @param sql 必须包含 {@code tenant_id = :tenantId}
     * @param params 命名参数
     * @return 行数
     */
    public long countTenant(TenantId tenantId, String sql, Map<String, Object> params) {
        requireTenantCondition(sql);
        Long value = jdbcClient
                .sql(sql)
                .params(bindTenant(tenantId, params))
                .query(Long.class)
                .single();
        return value == null ? 0L : value;
    }

    /**
     * 更新指定租户的数据。
     *
     * @param tenantId 目标租户
     * @param sql 必须包含 {@code tenant_id = :tenantId}
     * @param params 命名参数
     * @return 受影响行数
     */
    public int updateTenant(TenantId tenantId, String sql, Map<String, Object> params) {
        requireTenantCondition(sql);
        return jdbcClient.sql(sql).params(bindTenant(tenantId, params)).update();
    }

    /**
     * 向指定租户插入数据。
     *
     * @param tenantId 目标租户
     * @param sql 必须同时包含 {@code tenant_id} 列与 {@code :tenantId} 参数
     * @param params 命名参数
     * @return 受影响行数
     */
    public int insertTenant(TenantId tenantId, String sql, Map<String, Object> params) {
        String normalized = normalize(sql);
        if (!normalized.contains("tenant_id") || !normalized.contains(":tenantid")) {
            throw new IllegalStateException("tenant insert must include tenant_id and :tenantId");
        }
        return jdbcClient.sql(sql).params(bindTenant(tenantId, params)).update();
    }

    /**
     * 校验 SQL 含租户条件。
     *
     * @param sql 调用方 SQL
     */
    private void requireTenantCondition(String sql) {
        if (!normalize(sql).contains(TENANT_CONDITION)) {
            throw new IllegalStateException("tenant-scoped SQL must include tenant_id = :tenantId");
        }
    }

    /**
     * 绑定目标租户参数并拒绝调用方覆盖。
     *
     * @param tenantId 目标租户
     * @param params 调用方参数
     * @return 包含 {@code tenantId} 的参数副本
     */
    private Map<String, Object> bindTenant(TenantId tenantId, Map<String, Object> params) {
        Map<String, Object> bound = new LinkedHashMap<>();
        if (params != null) {
            bound.putAll(params);
        }
        bound.put("tenantId", tenantId.value());
        return bound;
    }

    /**
     * 统一大小写与空白，保证条件检查不受格式影响。
     *
     * @param sql 原始 SQL
     * @return 小写并压缩空白的 SQL
     */
    private String normalize(String sql) {
        Objects.requireNonNull(sql, "sql must not be null");
        return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
