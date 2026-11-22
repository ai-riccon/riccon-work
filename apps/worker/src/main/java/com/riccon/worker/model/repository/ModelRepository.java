package com.riccon.worker.model.repository;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.crypto.domain.CredentialRecord;
import com.riccon.worker.crypto.domain.TenantDataKeyRecord;
import com.riccon.worker.model.domain.ModelCatalogEntry;
import com.riccon.worker.model.domain.ModelCredential;
import com.riccon.worker.model.persistence.CredentialJdbc;
import com.riccon.worker.model.persistence.ModelCatalogJdbc;
import com.riccon.worker.persistence.PlatformTenant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 模型目录与平台凭据池仓储。
 *
 * <p>M04 的模型凭据只能是平台托管：`provider_credential.tenant_id='platform'`。本类**不再**
 * 先查请求租户、再回退 platform（ADR-0021 §6.1）；请求租户的遗留 BYO 凭据被忽略，并有
 * 负向测试覆盖。所有 key 池读写都在 `platform` 租户作用域内执行，查询必须带 `tenant_id`。
 */
@Component
public class ModelRepository {

    /** 模型凭据类型。 */
    public static final String CREDENTIAL_TYPE = "API_KEY";

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
     * 读取可用的第一个模型与供应商（BASELINE 兼容语义）。
     *
     * @return 目录条目；没有可用模型时为空
     */
    public Optional<ModelCatalogEntry> findActiveCatalog() {
        return catalogJdbc.findActive();
    }

    /**
     * 读取平台 key 池中的第一把 ACTIVE 凭据（M02 兼容入口）。
     *
     * <p>请求租户参数只用于日志上下文，不再参与查询；租户级凭据永远不会被使用。
     *
     * @param providerId 供应商 ID
     * @return 凭据；平台池没有可用 key 时为空
     */
    public Optional<CredentialRecord> findActivePlatformCredential(UUID providerId) {
        return TenantContext.callWithTenant(
                PlatformTenant.ID, () -> credentialJdbc.findActive(PlatformTenant.ID, providerId, CREDENTIAL_TYPE));
    }

    /**
     * 读取平台 key 池中指定 provider 的全部可用 key。
     *
     * @param providerId 供应商 ID
     * @return 按 priority/created_at/id 排序的可用 key
     */
    public List<ModelCredential> findPlatformPool(UUID providerId) {
        return TenantContext.callWithTenant(
                PlatformTenant.ID,
                () -> credentialJdbc.findAvailablePool(PlatformTenant.ID, providerId, CREDENTIAL_TYPE));
    }

    /**
     * 读取凭据引用的 DEK。
     *
     * @param credentialTenant 凭据所属租户（M04 固定 platform）
     * @param keyId DEK 标识
     * @return DEK；不存在时为空
     */
    public Optional<TenantDataKeyRecord> findDataKey(String credentialTenant, String keyId) {
        TenantId tenantId = TenantId.of(credentialTenant);
        return TenantContext.callWithTenant(tenantId, () -> credentialJdbc.findDataKey(tenantId, keyId));
    }

    /**
     * 读取 key 的连续失败次数。
     *
     * @param credentialId 凭据 ID
     * @return 连续失败次数；行不存在时为 0
     */
    public int currentCredentialFailures(UUID credentialId) {
        return TenantContext.callWithTenant(
                        PlatformTenant.ID, () -> credentialJdbc.currentFailures(PlatformTenant.ID, credentialId))
                .orElse(0);
    }

    /**
     * 记录一次可重试失败；连续 3 次写冷却截止（由服务层计算）。
     *
     * @param credentialId 凭据 ID
     * @param cooldownUntil 冷却截止（UTC）
     */
    public void recordCredentialFailure(UUID credentialId, LocalDateTime cooldownUntil) {
        TenantContext.callWithTenant(
                PlatformTenant.ID, () -> credentialJdbc.recordFailure(PlatformTenant.ID, credentialId, cooldownUntil));
    }

    /**
     * 记录一次 429 冷却。
     *
     * @param credentialId 凭据 ID
     * @param cooldownUntil 冷却截止（UTC）
     */
    public void recordCredentialRateLimited(UUID credentialId, LocalDateTime cooldownUntil) {
        TenantContext.callWithTenant(
                PlatformTenant.ID,
                () -> credentialJdbc.recordRateLimited(PlatformTenant.ID, credentialId, cooldownUntil));
    }

    /**
     * 401/403 条件失效一把 key。
     *
     * @param credentialId 凭据 ID
     * @return 是否由 ACTIVE 迁移为 INVALID
     */
    public boolean invalidateCredential(UUID credentialId) {
        int updated = TenantContext.callWithTenant(
                PlatformTenant.ID, () -> credentialJdbc.markInvalid(PlatformTenant.ID, credentialId));
        return updated > 0;
    }

    /**
     * 记录成功使用时间；失败不阻断已成功的回答（best-effort）。
     *
     * @param credentialId 凭据 ID
     */
    public void markCredentialUsed(UUID credentialId) {
        TenantContext.callWithTenant(PlatformTenant.ID, () -> credentialJdbc.markUsed(PlatformTenant.ID, credentialId));
    }
}
