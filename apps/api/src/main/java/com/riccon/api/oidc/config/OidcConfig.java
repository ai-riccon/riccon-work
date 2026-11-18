package com.riccon.api.oidc.config;

import com.riccon.api.oidc.infrastructure.NoopOAuth2AuthorizedClientRepository;
import com.riccon.api.oidc.infrastructure.OidcDiscoveryClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * OIDC 基础设施装配。
 *
 * <p>这里显式提供三个安全关键 Bean：
 *
 * <ul>
 *   <li>发现客户端（进程内缓存、可注入时钟）；
 *   <li>{@link NoopOAuth2AuthorizedClientRepository}——禁止任何 token 落会话；
 *   <li>会话内授权请求仓储——state/nonce/PKCE verifier 只存服务端会话，回调一次后删除。
 * </ul>
 *
 * <p>安全上下文仓储由 {@code SecurityConfig} 包装为 OIDC 感知实现；本类不重复定义，避免两套仓储。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OidcProperties.class)
public class OidcConfig {

    /**
     * 发现客户端。
     *
     * @param properties OIDC 配置
     * @param objectMapper JSON 解析器
     * @param clock 可注入时钟
     * @return 发现客户端
     */
    @Bean
    public OidcDiscoveryClient oidcDiscoveryClient(OidcProperties properties, ObjectMapper objectMapper, Clock clock) {
        OidcProperties.Oidc oidc = properties.getOidc();
        return new OidcDiscoveryClient(
                objectMapper,
                clock,
                oidc.getConnectTimeout(),
                oidc.getReadTimeout(),
                oidc.getDiscoveryCacheTtl(),
                oidc.isAllowHttp());
    }

    /**
     * 不保存 token 的授权客户端仓储。
     *
     * @return 空操作仓储
     */
    @Bean
    public OAuth2AuthorizedClientRepository oidcAuthorizedClientRepository() {
        return new NoopOAuth2AuthorizedClientRepository();
    }

    /**
     * 会话内授权请求仓储。
     *
     * @return HttpSession 授权请求仓储
     */
    @Bean
    public AuthorizationRequestRepository<OAuth2AuthorizationRequest> oidcAuthorizationRequestRepository() {
        return new HttpSessionOAuth2AuthorizationRequestRepository();
    }
}
