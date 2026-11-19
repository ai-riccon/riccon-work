package com.riccon.api.admin.identity.repository;

import com.riccon.api.admin.identity.OidcAccountIdentity;
import com.riccon.api.admin.identity.persistence.OidcBindingJdbc;
import com.riccon.api.identity.domain.UserAccount;
import com.riccon.platform.common.tenant.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * OIDC 账号开通/绑定仓储。
 *
 * <p>只服务管理端账号开通与绑定/解绑；登录期只读查询仍由 {@code OidcIdentityRepository} 负责。所有方法都要求
 * 调用方显式传入目标租户，平台管理员跨租户路径与租户管理员本租户路径因此共享同一实现。
 */
@Repository
public class OidcBindingRepository {

    /** OIDC 绑定持久化。 */
    private final OidcBindingJdbc bindingJdbc;

    /**
     * 创建绑定仓储。
     *
     * @param bindingJdbc OIDC 绑定持久化
     */
    public OidcBindingRepository(OidcBindingJdbc bindingJdbc) {
        this.bindingJdbc = Objects.requireNonNull(bindingJdbc, "bindingJdbc must not be null");
    }

    /**
     * 查询指定身份已绑定的账号。
     *
     * @param tenantId 目标租户
     * @param issuer 规范化 issuer
     * @param subject IdP subject
     * @return 已绑定账号
     */
    public Optional<UserAccount> findBoundUser(TenantId tenantId, String issuer, String subject) {
        return bindingJdbc.findBoundUser(tenantId, issuer, subject);
    }

    /**
     * 查询账号当前认证来源与外部身份。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @return 身份快照
     */
    public Optional<OidcAccountIdentity> findIdentity(TenantId tenantId, UUID userId) {
        return bindingJdbc.findIdentity(tenantId, userId);
    }

    /**
     * 直接创建 OIDC 账号（不写本地凭据）。
     *
     * @param tenantId 目标租户
     * @param user 账号
     * @param issuer 规范化 issuer
     * @param subject IdP subject
     */
    public void insertOidcUser(TenantId tenantId, UserAccount user, String issuer, String subject) {
        bindingJdbc.insertOidcUser(tenantId, user, issuer, subject);
    }

    /**
     * 把 LOCAL 账号转为 OIDC 账号。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @param issuer 规范化 issuer
     * @param subject IdP subject
     * @param now 更新时间
     * @return 更新成功时返回 {@code true}
     */
    public boolean bind(TenantId tenantId, UUID userId, String issuer, String subject, Instant now) {
        return bindingJdbc.bind(tenantId, userId, issuer, subject, now) == 1;
    }

    /**
     * 删除账号的本地密码凭据。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     */
    public void deleteCredential(TenantId tenantId, UUID userId) {
        bindingJdbc.deleteCredential(tenantId, userId);
    }

    /**
     * 把 OIDC 账号恢复为 LOCAL。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @param now 更新时间
     * @return 更新成功时返回 {@code true}
     */
    public boolean unbind(TenantId tenantId, UUID userId, Instant now) {
        return bindingJdbc.unbind(tenantId, userId, now) == 1;
    }

    /**
     * 写入或覆盖本地密码凭据。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @param passwordHash 带算法前缀的密码哈希
     * @param now 更新时间
     */
    public void upsertCredential(TenantId tenantId, UUID userId, String passwordHash, Instant now) {
        bindingJdbc.upsertCredential(tenantId, userId, passwordHash, now);
    }
}
