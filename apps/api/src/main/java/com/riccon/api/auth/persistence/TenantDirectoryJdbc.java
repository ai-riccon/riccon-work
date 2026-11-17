package com.riccon.api.auth.persistence;

import com.riccon.api.identity.domain.TenantRecord;
import com.riccon.api.identity.domain.TenantStatus;
import com.riccon.api.persistence.SqlTimestamps;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 全局租户目录查询。
 *
 * <p>{@code tenant} 是 ADR-0012 第 4 节列出的全局表，不参与 {@code TenantJdbc} 的租户过滤；登录阶段在建立
 * TenantContext 之前必须能按租户代码找到租户，因此是本包唯一直接使用 {@link JdbcClient} 的查询。
 * 对租户业务表的访问仍必须经过 {@code TenantJdbc}。
 */
@Component
public class TenantDirectoryJdbc {

    /** 按租户代码查询。 */
    private static final String FIND_BY_ID =
            "SELECT id, name, status, plan, retention_days, created_at FROM tenant WHERE id = :id";

    /** Spring JDBC 客户端。 */
    private final JdbcClient jdbcClient;

    /**
     * 创建租户目录查询。
     *
     * @param jdbcClient Spring JDBC 客户端
     */
    public TenantDirectoryJdbc(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    /**
     * 按租户代码查询租户元数据。
     *
     * @param tenantCode 租户代码
     * @return 租户元数据
     */
    public Optional<TenantRecord> findById(String tenantCode) {
        return jdbcClient
                .sql(FIND_BY_ID)
                .params(Map.of("id", tenantCode))
                .query(TenantDirectoryJdbc::mapTenant)
                .optional();
    }

    /**
     * 映射租户行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 租户元数据
     * @throws SQLException 读取失败
     */
    private static TenantRecord mapTenant(ResultSet rs, int rowNum) throws SQLException {
        LocalDateTime createdAt = rs.getObject("created_at", LocalDateTime.class);
        return new TenantRecord(
                rs.getString("id"),
                rs.getString("name"),
                TenantStatus.valueOf(rs.getString("status")),
                rs.getString("plan"),
                rs.getInt("retention_days"),
                SqlTimestamps.toInstant(createdAt));
    }
}
