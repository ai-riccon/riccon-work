package com.riccon.api.auth.service;

import com.riccon.api.identity.domain.UserAccount;
import com.riccon.api.oidc.domain.OidcIdpConfig;
import com.riccon.api.oidc.domain.OidcRegistrationIds;
import com.riccon.api.oidc.service.OidcIdpRegistry;
import com.riccon.platform.domain.identity.AuthSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 来源 IdP 的脱敏展示名解析。
 *
 * <p>用于会话摘要与账号管理列表的 {@code identityProvider} 字段。规则：
 *
 * <ul>
 *   <li>LOCAL 账号返回 {@code null}，界面按“本地账号”展示，不伪造来源；
 *   <li>OIDC 账号优先使用租户当前配置的 {@code providerName}，但仅当账号存储的 issuer 与配置 issuer 完全一致；
 *   <li>配置缺失、已替换或不匹配时回退为 issuer 的 host（含非默认端口，如 {@code idp.example.com}）；
 * </ul>
 *
 * <p>脱敏边界：只输出可公开的展示名或 host。完整 issuer 路径、subject、token、邮箱、客户端 ID/密钥永不进入该
 * 字段。同一 issuer 在同一租户内结果稳定，不同 issuer 的账号可通过 host 或配置名区分。
 */
@Service
public class IdentityProviderResolver {

    /** IdP 注册表；只读，不触发发现请求。 */
    private final OidcIdpRegistry oidcIdpRegistry;

    /**
     * 创建来源 IdP 解析器。
     *
     * @param oidcIdpRegistry IdP 注册表
     */
    public IdentityProviderResolver(OidcIdpRegistry oidcIdpRegistry) {
        this.oidcIdpRegistry = Objects.requireNonNull(oidcIdpRegistry, "oidcIdpRegistry must not be null");
    }

    /**
     * 解析账号的来源 IdP 展示名。
     *
     * @param user 账号快照
     * @return 脱敏展示名；LOCAL 或无法解析时为 {@code null}
     */
    public String resolve(UserAccount user) {
        Objects.requireNonNull(user, "user must not be null");
        return resolve(user.tenantId(), user.authSource(), user.externalIssuer());
    }

    /**
     * 解析来源 IdP 展示名。
     *
     * @param tenantCode 租户代码
     * @param authSource 认证来源
     * @param externalIssuer 账号存储的 issuer（可为空）
     * @return 脱敏展示名；非 OIDC、issuer 缺失或无法解析时为 {@code null}
     */
    public String resolve(String tenantCode, AuthSource authSource, String externalIssuer) {
        if (authSource != AuthSource.OIDC || externalIssuer == null || externalIssuer.isBlank()) {
            return null;
        }
        String issuer = externalIssuer.trim();
        String configuredName = configuredProviderName(tenantCode, issuer);
        if (configuredName != null) {
            return configuredName;
        }
        return hostLabel(issuer);
    }

    /**
     * 读取租户配置的 provider 展示名。
     *
     * <p>只有 issuer 与配置完全一致时才使用配置名：配置换新 issuer 后，历史账号仍显示自己的 host，避免把两个
     * 不同 IdP 误标成同一个名字。已禁用的配置也参与匹配，保证列表展示不因运维操作跳变。
     *
     * @param tenantCode 租户代码
     * @param issuer 账号存储的 issuer
     * @return 非空展示名；未配置或不匹配时返回 {@code null}
     */
    private String configuredProviderName(String tenantCode, String issuer) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return null;
        }
        return oidcIdpRegistry
                .findConfigured(OidcRegistrationIds.registrationId(tenantCode))
                .filter(idp -> issuer.equals(idp.issuer()))
                .map(OidcIdpConfig::providerName)
                .filter(name -> !name.isBlank())
                .orElse(null);
    }

    /**
     * 从 issuer 提取脱敏 host（含非默认端口）。
     *
     * <p>只保留 host 与非默认端口；路径、查询参数、用户信息、fragment 一律丢弃，避免泄露租户或 IdP 内部结构。
     * 非法或缺少 host 的 issuer 返回 {@code null}，不输出原始字符串。
     *
     * @param issuer 规范化 issuer
     * @return 小写 host（必要时带端口）；无法解析时为 {@code null}
     */
    private static String hostLabel(String issuer) {
        URI uri;
        try {
            uri = new URI(issuer);
        } catch (URISyntaxException exception) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return null;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (port == -1 || isDefaultPort(uri.getScheme(), port)) {
            return normalized;
        }
        return normalized + ":" + port;
    }

    /**
     * 判断端口是否为 scheme 默认端口。
     *
     * @param scheme URL scheme
     * @param port 端口
     * @return 默认 http 80 / https 443 时返回 {@code true}
     */
    private static boolean isDefaultPort(String scheme, int port) {
        return ("http".equalsIgnoreCase(scheme) && port == 80) || ("https".equalsIgnoreCase(scheme) && port == 443);
    }
}
