package com.riccon.api.admin.audit.repository;

import com.riccon.api.admin.audit.persistence.PlatformAuditJdbc;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/**
 * 平台审计导出审批的元数据仓储。
 *
 * <p>只提供目标租户存在性与 {@code TENANT_ADMIN} 账号数；审批与列表复用租户作用域的导出仓储，
 * 避免出现第二套状态机。平台管理员不读取目标租户审计明细（DEC-0013）。
 */
@Repository
public class PlatformAuditExportRepository {

    /** 平台审计元数据查询。 */
    private final PlatformAuditJdbc platformAuditJdbc;

    /**
     * 创建平台审计元数据仓储。
     *
     * @param platformAuditJdbc 平台审计元数据查询
     */
    public PlatformAuditExportRepository(PlatformAuditJdbc platformAuditJdbc) {
        this.platformAuditJdbc = Objects.requireNonNull(platformAuditJdbc, "platformAuditJdbc must not be null");
    }

    /**
     * 判断目标租户是否存在。
     *
     * @param tenantCode 租户代码
     * @return 存在时返回 {@code true}
     */
    public boolean tenantExists(String tenantCode) {
        return platformAuditJdbc.tenantExists(tenantCode);
    }

    /**
     * 统计目标租户启用的租户管理员账号数。
     *
     * @param tenantCode 目标租户
     * @return 账号数
     */
    public long countActiveTenantAdmins(String tenantCode) {
        return platformAuditJdbc.countActiveTenantAdmins(tenantCode);
    }
}
