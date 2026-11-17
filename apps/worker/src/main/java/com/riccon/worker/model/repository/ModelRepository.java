package com.riccon.worker.model.repository;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.crypto.domain.CredentialRecord;
import com.riccon.worker.crypto.domain.TenantDataKeyRecord;
import com.riccon.worker.model.domain.ModelCatalogEntry;
import com.riccon.worker.model.persistence.CredentialJdbc;
import com.riccon.worker.model.persistence.ModelCatalogJdbc;
import com.riccon.worker.persistence.PlatformTenant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 模型目录与凭据仓储。
 *
 * <p>目录是全局表；凭据优先读取当前租户，M02 不存在租户级记录时回退到 {@code platform}
 * 部署级凭据。回退会显式打开 platform 租户作用域，不能把 platform 当成业务租户传递。
 */
@Component
public class ModelRepository {

    /** 模型目录访问。 */
    private final ModelCatalogJdbc catalogJdbc;

    /** 凭据访问。 */
    private final CredentialJdbc credentialJdbc;

    /**
     * 创建模型仓储。
     *
     * @param catalogJdbc 模型目录访问
     * @param credentialJdbc 凭据访问
     */
    public ModelRepository(ModelCatalogJdbc catalogJdbc, CredentialJdbc credentialJdbc) {
        this.catalogJdbc = Objects.requireNonNull(catalogJdbc, "catalogJdbc must not be null");
        this.credentialJdbc = Objects.requireNonNull(credentialJdbc, "credentialJdbc must not be null");
    }

    /**
     * 读取可用的模型与供应商。
     *
     * @return 目录条目；没有可用模型时为空
     */
    public Optional<ModelCatalogEntry> findActiveCatalog() {
        return catalogJdbc.findActive();
    }

    /**
     * 读取供应商凭据：先租户级，后平台级。
     *
     * @param tenantId 当前租户
     * @param providerId 供应商 ID
     * @return 凭据；两级都不存在时为空
     */
    public Optional<CredentialRecord> findActiveCredential(TenantId tenantId, UUID providerId) {
        Optional<CredentialRecord> tenantCredential = credentialJdbc.findActive(tenantId, providerId, "API_KEY");
        if (tenantCredential.isPresent() || tenantId.equals(PlatformTenant.ID)) {
            return tenantCredential;
        }
        return TenantContext.callWithTenant(
                PlatformTenant.ID, () -> credentialJdbc.findActive(PlatformTenant.ID, providerId, "API_KEY"));
    }

    /**
     * 读取凭据引用的数据密钥。
     *
     * @param credentialTenant 凭据所属租户（tenant 或 platform）
     * @param keyId DEK 标识
     * @return DEK；不存在时为空
     */
    public Optional<TenantDataKeyRecord> findDataKey(String credentialTenant, String keyId) {
        TenantId tenantId = TenantId.of(credentialTenant);
        return TenantContext.callWithTenant(tenantId, () -> credentialJdbc.findDataKey(tenantId, keyId));
    }
}
