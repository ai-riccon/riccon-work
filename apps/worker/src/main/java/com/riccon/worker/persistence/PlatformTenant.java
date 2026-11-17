package com.riccon.worker.persistence;

import com.riccon.platform.common.tenant.TenantId;

/**
 * 平台保留租户常量。
 *
 * <p>M02 模型凭据为部署级，{@code provider_credential.tenant_id} 与对应 DEK 使用 {@code platform}
 * （ADR-0012 第 4 节）。业务请求不得把 {@code platform} 当作可访问租户，只有凭据解析内部使用。
 */
public final class PlatformTenant {

    /** 平台保留租户 ID。 */
    public static final String TENANT_ID = "platform";

    /** 解析后的租户标识。 */
    public static final TenantId ID = TenantId.of(TENANT_ID);

    /** 工具类禁止实例化。 */
    private PlatformTenant() {}
}
