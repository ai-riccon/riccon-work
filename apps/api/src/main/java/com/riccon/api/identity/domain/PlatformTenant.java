package com.riccon.api.identity.domain;

import com.riccon.platform.common.tenant.TenantId;

/**
 * 平台保留租户常量。
 *
 * <p>V2 迁移固定插入 {@code platform} 租户，承载平台管理员与平台级审计事件；租户代码在
 * {@code TenantId} 中合法，但禁止通过租户开通接口再次创建。
 */
public final class PlatformTenant {

    /** 保留租户代码。 */
    public static final String ID = "platform";

    /** 保留租户的强类型标识。 */
    public static final TenantId TENANT_ID = TenantId.of(ID);

    /** 工具类禁止实例化。 */
    private PlatformTenant() {}
}
