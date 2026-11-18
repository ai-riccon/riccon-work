package com.riccon.api.oidc.service;

import com.riccon.api.oidc.domain.OidcDiscoveryException;
import java.util.Objects;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/**
 * 按部署配置动态解析租户客户端注册。
 *
 * <p>registrationId 固定 {@code tenant-{tenantCode}}；发现失败时返回 {@code null} 并由登录失败处理器结合
 * 注册表状态映射为 {@code idp_unavailable}，避免把 IdP 原始错误暴露给浏览器。
 *
 * <p>不实现 {@code Iterable<ClientRegistration>}：默认登录页已关闭，任何枚举都会触发全部租户的发现请求，
 * 既浪费出网也不必要地暴露租户列表。
 */
@Component
public class DynamicClientRegistrationRepository implements ClientRegistrationRepository {

    /** IdP 注册表。 */
    private final OidcIdpRegistry registry;

    /**
     * 创建动态仓储。
     *
     * @param registry IdP 注册表
     */
    public DynamicClientRegistrationRepository(OidcIdpRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * 按注册 ID 查找客户端注册。
     *
     * @param registrationId 注册 ID
     * @return 客户端注册；未配置、禁用或发现失败时返回 {@code null}
     */
    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        try {
            return registry.clientRegistration(registrationId).orElse(null);
        } catch (OidcDiscoveryException exception) {
            // 发现失败已由 OidcDiscoveryClient 记录脱敏日志；这里返回 null 让登录失败处理器统一清理会话。
            return null;
        }
    }
}
