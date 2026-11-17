package com.riccon.api.persistence;

import com.riccon.platform.common.tenant.TenantContext;
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
 * 租户数据访问唯一强制点。
 *
 * <p>ADR-0009 要求：所有租户业务表的读写都必须经过本类，调用方必须显式传入 {@link TenantId}，SQL 必须包含
 * 租户条件。缺失条件时立即抛 {@link IllegalStateException}，不执行 SQL；当前线程的 {@link TenantContext}
 * 与传入租户不一致时同样拒绝，避免线程池串租户。
 *
 * <p>本类不拼接表名、不接受调用方提供的排序字段；跨租户管理查询只能使用 {@code ..admin..} 包中的
 * {@code PlatformJdbc}。
 */
@Component
public class TenantJdbc {

    /** 租户条件片段；所有单表查询必须原样包含它。 */
    private static final String TENANT_CONDITION = "tenant_id = :tenantid";

    /** Spring JDBC 客户端；只允许在 {@code ..persistence..} 出现。 */
    private final JdbcClient jdbcClient;

    /**
     * 创建租户数据访问入口。
     *
     * @param jdbcClient Spring JDBC 客户端
     */
    public TenantJdbc(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    /**
     * 执行租户范围内的查询。
     *
     * @param tenantId 当前租户
     * @param sql 必须包含 {@code tenant_id = :tenantId}
     * @param params 命名参数，不能包含 {@code tenantId}（由本类统一绑定）
     * @param rowMapper 行映射
     * @param <T> 行类型
     * @return 查询结果
     * @throws IllegalStateException SQL 缺少租户条件，或与当前线程租户不一致
     */
    public <T> List<T> query(TenantId tenantId, String sql, Map<String, Object> params, RowMapper<T> rowMapper) {
        requireTenantCondition(sql);
        return jdbcClient
                .sql(sql)
                .params(bindTenant(tenantId, params))
                .query(rowMapper)
                .list();
    }

    /**
     * 执行租户范围内的单行查询。
     *
     * @param tenantId 当前租户
     * @param sql 必须包含 {@code tenant_id = :tenantId}
     * @param params 命名参数
     * @param rowMapper 行映射
     * @param <T> 行类型
     * @return 查询结果
     */
    public <T> Optional<T> queryOne(TenantId tenantId, String sql, Map<String, Object> params, RowMapper<T> rowMapper) {
        requireTenantCondition(sql);
        return jdbcClient
                .sql(sql)
                .params(bindTenant(tenantId, params))
                .query(rowMapper)
                .optional();
    }

    /**
     * 执行租户范围内的计数查询。
     *
     * @param tenantId 当前租户
     * @param sql 必须包含 {@code tenant_id = :tenantId}
     * @param params 命名参数
     * @return 计数结果
     */
    public long count(TenantId tenantId, String sql, Map<String, Object> params) {
        requireTenantCondition(sql);
        Long value = jdbcClient
                .sql(sql)
                .params(bindTenant(tenantId, params))
                .query(Long.class)
                .single();
        return value == null ? 0L : value;
    }

    /**
     * 执行租户范围内的更新或删除。
     *
     * @param tenantId 当前租户
     * @param sql 必须包含 {@code tenant_id = :tenantId}
     * @param params 命名参数
     * @return 受影响行数
     */
    public int update(TenantId tenantId, String sql, Map<String, Object> params) {
        requireTenantCondition(sql);
        return jdbcClient.sql(sql).params(bindTenant(tenantId, params)).update();
    }

    /**
     * 执行租户范围内的插入。
     *
     * @param tenantId 当前租户
     * @param sql 必须同时包含 {@code tenant_id} 列与 {@code :tenantId} 参数
     * @param params 命名参数
     * @return 受影响行数
     */
    public int insert(TenantId tenantId, String sql, Map<String, Object> params) {
        String normalized = normalize(sql);
        if (!normalized.contains("tenant_id") || !normalized.contains(":tenantid")) {
            throw new IllegalStateException("tenant insert must include tenant_id and :tenantId");
        }
        return jdbcClient.sql(sql).params(bindTenant(tenantId, params)).update();
    }

    /**
     * 校验 SQL 含租户条件，并确认当前线程存在租户上下文。
     *
     * @param sql 调用方 SQL
     */
    private void requireTenantCondition(String sql) {
        String normalized = normalize(sql);
        if (!normalized.contains(TENANT_CONDITION)) {
            throw new IllegalStateException("tenant-scoped SQL must include tenant_id = :tenantId");
        }
        requireContextTenant();
    }

    /**
     * 校验当前线程存在租户上下文。
     *
     * @return 当前线程租户
     */
    private TenantId requireContextTenant() {
        return TenantContext.require();
    }

    /**
     * 绑定租户参数并拒绝调用方覆盖。
     *
     * @param tenantId 当前租户
     * @param params 调用方参数
     * @return 包含 {@code tenantId} 的参数副本
     */
    private Map<String, Object> bindTenant(TenantId tenantId, Map<String, Object> params) {
        if (!requireContextTenant().equals(tenantId)) {
            throw new IllegalStateException("tenant argument does not match current tenant context");
        }
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
