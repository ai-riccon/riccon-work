package com.riccon.worker.research.persistence;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 租户保留策略读取。
 *
 * <p>{@code tenant} 是 ADR-0012 第 4 节列出的全局根表，没有 {@code tenant_id} 列，因此不能走
 * {@link com.riccon.worker.persistence.TenantJdbc} 的租户条件守卫。本类只按当前租户 ID 读取单行，
 * 并在访问前校验线程租户，禁止把它扩展为跨租户查询入口。
 */
@Component
public class TenantRetentionJdbc {

    /** 默认保留天数，与合同 {@code data.retention} 一致。 */
    public static final int DEFAULT_RETENTION_DAYS = 180;

    /** 底层 JDBC 客户端。 */
    private final JdbcClient jdbcClient;

    /**
     * 创建租户保留策略读取。
     *
     * @param jdbcClient JDBC 客户端
     */
    public TenantRetentionJdbc(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    /**
     * 读取当前租户的保留天数。
     *
     * @param tenantId 当前租户
     * @return 保留天数；租户行缺失时为空
     */
    public Optional<Integer> findRetentionDays(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        TenantId current = TenantContext.require();
        if (!current.equals(tenantId)) {
            // 与 TenantJdbc 相同的防护：调用方传错租户时直接失败，不降级为跨租户读取。
            throw new IllegalStateException("tenant context does not match requested tenant");
        }
        return jdbcClient
                .sql("SELECT retention_days FROM tenant WHERE id = :tenantId")
                .param("tenantId", tenantId.value())
                .query(Integer.class)
                .optional();
    }
}
