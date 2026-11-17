package com.riccon.api.auth.repository;

import com.riccon.api.auth.persistence.TenantDirectoryJdbc;
import com.riccon.api.identity.domain.TenantRecord;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 全局租户目录仓储。
 *
 * <p>只提供租户元数据读取，不包含跨租户业务查询；平台管理写入由 {@code ..admin..} 包负责。
 */
@Repository
public class TenantDirectoryRepository {

    /** 租户目录查询。 */
    private final TenantDirectoryJdbc tenantDirectoryJdbc;

    /**
     * 创建租户目录仓储。
     *
     * @param tenantDirectoryJdbc 租户目录查询
     */
    public TenantDirectoryRepository(TenantDirectoryJdbc tenantDirectoryJdbc) {
        this.tenantDirectoryJdbc = Objects.requireNonNull(tenantDirectoryJdbc, "tenantDirectoryJdbc must not be null");
    }

    /**
     * 按租户代码查询租户元数据。
     *
     * @param tenantCode 租户代码
     * @return 租户元数据
     */
    public Optional<TenantRecord> findById(String tenantCode) {
        return tenantDirectoryJdbc.findById(tenantCode);
    }
}
