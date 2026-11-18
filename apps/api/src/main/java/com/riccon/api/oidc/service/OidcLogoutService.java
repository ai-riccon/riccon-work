package com.riccon.api.oidc.service;

import com.riccon.api.auth.repository.UserAccountRepository;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.domain.UserAccount;
import com.riccon.api.oidc.domain.OidcIdpConfig;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.AuthSource;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

/**
 * OIDC 联合登出地址计算。
 *
 * <p>本地登出先执行且是权威结果（ADR-0019 第 5 节、DEC-0017 A）：本服务只负责在
 * “账号为 OIDC + 租户配置了 end-session 端点”时构造响应头地址，从不调用 IdP。因为不保存 ID Token，
 * 链接只带 {@code client_id} 与编码后的 {@code post_logout_redirect_uri}，不使用 {@code id_token_hint}。
 */
@Service
public class OidcLogoutService {

    /** 联合登出地址响应头；不含 token。 */
    public static final String FEDERATED_LOGOUT_HEADER = "X-Riccon-Federated-Logout-Url";

    /** 回跳登录页参数。 */
    private static final String POST_LOGOUT_PATH = "/login?loggedOut=1&federated=1";

    /** IdP 注册表。 */
    private final OidcIdpRegistry registry;

    /** 账号仓储（只读取认证来源）。 */
    private final UserAccountRepository userAccountRepository;

    /**
     * 创建登出服务。
     *
     * @param registry IdP 注册表
     * @param userAccountRepository 账号仓储
     */
    public OidcLogoutService(OidcIdpRegistry registry, UserAccountRepository userAccountRepository) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.userAccountRepository =
                Objects.requireNonNull(userAccountRepository, "userAccountRepository must not be null");
    }

    /**
     * 计算联合登出地址。
     *
     * <p>LOCAL 账号或租户未配置 end-session 时返回空，前端只显示“仅退出本地会话”。
     *
     * @param principal 当前会话主体
     * @return 绝对联登地址；不适用时为空
     */
    public Optional<String> federatedLogoutUrl(RicconPrincipal principal) {
        OidcIdpConfig idp =
                registry.findEnabledByTenantCode(principal.tenantId()).orElse(null);
        if (idp == null
                || idp.endSessionEndpoint() == null
                || idp.endSessionEndpoint().isBlank()) {
            return Optional.empty();
        }
        TenantId tenantId = TenantId.of(principal.tenantId());
        UserAccount user = TenantContext.callWithTenant(
                        tenantId, () -> userAccountRepository.findById(tenantId, principal.userId()))
                .orElse(null);
        if (user == null || user.authSource() != AuthSource.OIDC) {
            return Optional.empty();
        }
        String postLogoutRedirectUri =
                UriUtils.encodeQueryParam(registry.publicBaseUrl() + POST_LOGOUT_PATH, StandardCharsets.UTF_8);
        String separator = idp.endSessionEndpoint().contains("?") ? "&" : "?";
        return Optional.of(idp.endSessionEndpoint()
                + separator
                + "client_id="
                + UriUtils.encodeQueryParam(idp.clientId(), StandardCharsets.UTF_8)
                + "&post_logout_redirect_uri="
                + postLogoutRedirectUri);
    }
}
