package com.riccon.api.oidc.repository;

import com.riccon.api.identity.domain.UserAccount;
import com.riccon.api.oidc.persistence.OidcIdentityJdbc;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * OIDC 身份仓储。
 *
 * <p>只提供“按 issuer + subject 查已绑定账号”的只读能力；绑定/解绑与本地凭据变更属于 M10-B2。
 * 查询在显式租户上下文中执行，避免线程复用导致串租户（ADR-0009）。
 */
@Repository
public class OidcIdentityRepository {

    /** OIDC 身份持久化。 */
    private final OidcIdentityJdbc identityJdbc;

    /**
     * 创建仓储。
     *
     * @param identityJdbc OIDC 身份持久化
     */
    public OidcIdentityRepository(OidcIdentityJdbc identityJdbc) {
        this.identityJdbc = Objects.requireNonNull(identityJdbc, "identityJdbc must not be null");
    }

    /**
     * 查询已绑定账号。
     *
     * @param tenantId 租户
     * @param issuer 规范化 issuer
     * @param subject IdP 的 subject
     * @return 账号
     */
    public Optional<UserAccount> findBoundUser(TenantId tenantId, String issuer, String subject) {
        return TenantContext.callWithTenant(tenantId, () -> identityJdbc.findBoundUser(tenantId, issuer, subject));
    }
}
