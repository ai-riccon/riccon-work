package com.riccon.api.oidc.service;

import com.riccon.api.oidc.config.OidcProperties;
import com.riccon.api.oidc.domain.OidcConfigurationParser;
import com.riccon.api.oidc.domain.OidcDiscoveryException;
import com.riccon.api.oidc.domain.OidcIdpConfig;
import com.riccon.api.oidc.domain.OidcProviderMetadata;
import com.riccon.api.oidc.domain.OidcRegistrationIds;
import com.riccon.api.oidc.infrastructure.OidcDiscoveryClient;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 租户 IdP 注册表。
 *
 * <p>启动时解析并校验 {@code RICCON_OIDC_IDPS}（fail-fast，未配置时正常启动）。运行期按 registrationId
 * 提供 {@link ClientRegistration}：发现文档懒加载并缓存，失败返回稳定原因码，不影响内置账号（ADR-0019）。
 *
 * <p>注册 ID 固定 {@code tenant-{tenantCode}}；每租户最多 1 个 IdP；issuer 规范化后全局唯一。
 */
@Service
public class OidcIdpRegistry {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(OidcIdpRegistry.class);

    /** 回调路径前缀。 */
    private static final String CALLBACK_PATH = "/api/v1/auth/oidc/callback/";

    /** 已配置 IdP（包含 enabled=false）按租户索引。 */
    private final Map<String, OidcIdpConfig> byTenantCode;

    /** 已配置 IdP（包含 enabled=false）按注册 ID 索引。 */
    private final Map<String, OidcIdpConfig> byRegistrationId;

    /** 发现客户端。 */
    private final OidcDiscoveryClient discoveryClient;

    /** 对外基础地址，已去尾部斜杠。 */
    private final String publicBaseUrl;

    /**
     * 创建注册表并执行启动校验。
     *
     * @param properties OIDC 配置
     * @param objectMapper JSON 解析器
     * @param discoveryClient 发现客户端
     * @throws IllegalStateException 配置非法；消息已脱敏
     */
    public OidcIdpRegistry(OidcProperties properties, ObjectMapper objectMapper, OidcDiscoveryClient discoveryClient) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.discoveryClient = Objects.requireNonNull(discoveryClient, "discoveryClient must not be null");
        this.publicBaseUrl = normalizePublicBaseUrl(properties.getPublicBaseUrl());
        List<OidcIdpConfig> idps = OidcConfigurationParser.parse(
                properties.getOidc().getIdps(), properties.getOidc().isAllowHttp(), objectMapper);
        Map<String, OidcIdpConfig> tenants = new HashMap<>();
        Map<String, OidcIdpConfig> registrations = new HashMap<>();
        for (OidcIdpConfig idp : idps) {
            tenants.put(idp.tenantCode(), idp);
            registrations.put(idp.registrationId(), idp);
        }
        this.byTenantCode = Map.copyOf(tenants);
        this.byRegistrationId = Map.copyOf(registrations);
        LOG.info("oidc.idp.configured count={} enabled={}", byTenantCode.size(), enabledCount());
    }

    /**
     * 按注册 ID 查找已配置 IdP（包含 enabled=false）。
     *
     * @param registrationId 注册 ID
     * @return 配置
     */
    public Optional<OidcIdpConfig> findConfigured(String registrationId) {
        if (registrationId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byRegistrationId.get(registrationId));
    }

    /**
     * 按租户代码查找启用的 IdP。
     *
     * @param tenantCode 租户代码
     * @return 启用的配置
     */
    public Optional<OidcIdpConfig> findEnabledByTenantCode(String tenantCode) {
        if (tenantCode == null) {
            return Optional.empty();
        }
        OidcIdpConfig idp = byTenantCode.get(tenantCode);
        return idp != null && idp.enabled() ? Optional.of(idp) : Optional.empty();
    }

    /**
     * 按注册 ID 查找启用的 IdP。
     *
     * @param registrationId 注册 ID
     * @return 启用的配置
     */
    public Optional<OidcIdpConfig> findEnabledByRegistrationId(String registrationId) {
        return findConfigured(registrationId).filter(OidcIdpConfig::enabled);
    }

    /**
     * 从注册 ID 解析已配置的租户代码；未配置或已禁用时为空。
     *
     * @param registrationId 注册 ID
     * @return 租户代码
     */
    public Optional<String> tenantCodeOf(String registrationId) {
        return findEnabledByRegistrationId(registrationId).map(OidcIdpConfig::tenantCode);
    }

    /**
     * 构建 Spring Security 的客户端注册。
     *
     * <p>显式不设置 userinfo 端点，且 scope 只有 {@code openid}；OIDC 用户只从 ID Token 构建。
     *
     * @param registrationId 注册 ID
     * @return 客户端注册；未配置或禁用时为空
     * @throws OidcDiscoveryException 发现失败或 issuer 不一致
     */
    public Optional<ClientRegistration> clientRegistration(String registrationId) {
        Optional<OidcIdpConfig> idp = findEnabledByRegistrationId(registrationId);
        if (idp.isEmpty()) {
            return Optional.empty();
        }
        OidcProviderMetadata metadata = discoveryClient.resolve(idp.get());
        return Optional.of(ClientRegistration.withRegistrationId(registrationId)
                .clientId(idp.get().clientId())
                .clientSecret(idp.get().clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(callbackRedirectUri(registrationId))
                .scope(idp.get().scopes())
                .authorizationUri(metadata.authorizationEndpoint())
                .tokenUri(metadata.tokenEndpoint())
                .jwkSetUri(metadata.jwkSetUri())
                .issuerUri(idp.get().issuer())
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .clientName(idp.get().providerName())
                // PKCE(S256) 强制开启；由默认 resolver 生成 verifier/challenge。
                .clientSettings(ClientRegistration.ClientSettings.builder()
                        .requireProofKey(true)
                        .build())
                .build());
    }

    /**
     * 判断注册 ID 对应的 IdP 是否处于发现失败退避窗口。
     *
     * @param registrationId 注册 ID
     * @return 处于失败窗口时返回 {@code true}
     */
    public boolean isDiscoveryUnavailable(String registrationId) {
        return findEnabledByRegistrationId(registrationId)
                .map(idp -> discoveryClient.isUnavailable(idp.issuer()))
                .orElse(false);
    }

    /**
     * 返回回调 URI（绝对地址，必须与 IdP 注册值完全一致）。
     *
     * @param registrationId 注册 ID
     * @return 回调 URI
     */
    public String callbackRedirectUri(String registrationId) {
        return publicBaseUrl + CALLBACK_PATH + registrationId;
    }

    /**
     * 返回对外基础地址。
     *
     * @return 基础地址，不含尾部斜杠
     */
    public String publicBaseUrl() {
        return publicBaseUrl;
    }

    /**
     * 统计启用配置数量。
     *
     * @return 启用数量
     */
    private long enabledCount() {
        return byTenantCode.values().stream().filter(OidcIdpConfig::enabled).count();
    }

    /**
     * 规范化对外基础地址：绝对 http(s)、无查询/片段、去尾部斜杠。
     *
     * @param value 配置值
     * @return 规范化地址
     */
    private static String normalizePublicBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("riccon.public-base-url is required");
        }
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("riccon.public-base-url must be an absolute URL");
        }
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw new IllegalStateException("riccon.public-base-url must be an absolute URL");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalStateException("riccon.public-base-url must use http or https");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException("riccon.public-base-url must not contain query or fragment");
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int port = uri.getPort();
        if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) {
            port = -1;
        }
        String authority = port == -1 ? uri.getHost() : uri.getHost() + ":" + port;
        return scheme + "://" + authority + path;
    }

    /**
     * 判断注册 ID 是否符合固定格式。
     *
     * @param registrationId 注册 ID
     * @return 合法时返回 {@code true}
     */
    public static boolean isValidRegistrationId(String registrationId) {
        return OidcRegistrationIds.tenantCode(registrationId).isPresent();
    }
}
