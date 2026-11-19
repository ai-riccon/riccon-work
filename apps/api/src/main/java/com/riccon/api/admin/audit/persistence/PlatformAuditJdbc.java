package com.riccon.api.admin.audit.persistence;

import com.riccon.api.admin.persistence.PlatformJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 平台审计导出审批的元数据查询（ADR-0020 第 2/3 节）。
 *
 * <p>只查询目标租户是否存在、启用状态与 {@code TENANT_ADMIN} 账号数；不读取目标租户审计内容。
 * 单管理员判断必须是目标租户作用域的实时统计，不做跨请求缓存。
 */
@Component
public class PlatformAuditJdbc {

    /** 统计目标租户启用且担任 TENANT_ADMIN 的账号数。 */
    private static final String COUNT_ACTIVE_TENANT_ADMINS = """
            SELECT COUNT(DISTINCT u.id)
              FROM app_user u
              JOIN user_role ur ON ur.tenant_id = u.tenant_id AND ur.user_id = u.id
              JOIN role r ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
             WHERE u.tenant_id = :tenantId
               AND u.status = 'ACTIVE'
               AND r.code = 'TENANT_ADMIN'
            """;

    /** 平台管理 JDBC 入口；跨租户只允许出现在 {@code ..admin..} 包。 */
    private final PlatformJdbc platformJdbc;

    /**
     * 创建平台审计元数据查询。
     *
     * @param platformJdbc 平台管理 JDBC 入口
     */
    public PlatformAuditJdbc(PlatformJdbc platformJdbc) {
        this.platformJdbc = Objects.requireNonNull(platformJdbc, "platformJdbc must not be null");
    }

    /**
     * 判断租户是否存在。
     *
     * @param tenantCode 租户代码
     * @return 存在时返回 {@code true}
     */
    public boolean tenantExists(String tenantCode) {
        return platformJdbc
                .queryOneGlobal(
                        "SELECT id FROM tenant WHERE id = :tenantId",
                        Map.of("tenantId", tenantCode),
                        (rs, rowNum) -> rs.getString("id"))
                .isPresent();
    }

    /**
     * 统计目标租户启用的租户管理员账号数。
     *
     * @param tenantCode 目标租户
     * @return 账号数
     */
    public long countActiveTenantAdmins(String tenantCode) {
        return platformJdbc.countTenant(TenantId.of(tenantCode), COUNT_ACTIVE_TENANT_ADMINS, Map.of());
    }
}
