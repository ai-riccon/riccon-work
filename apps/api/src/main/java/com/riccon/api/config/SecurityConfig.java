package com.riccon.api.config;

import com.riccon.api.oidc.service.OidcAwareSecurityContextRepository;
import com.riccon.api.web.JsonAccessDeniedHandler;
import com.riccon.api.web.JsonAuthenticationEntryPoint;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * API 安全过滤器链。
 *
 * <p>默认拒绝一切，只显式放行健康检查、CSRF 令牌、内置账号登录与 OIDC 的 providers/authorize/callback；
 * 其余路径要求会话认证，具体权限由 {@code PermissionInterceptor} 做方法级校验。会话使用 Spring Session JDBC，
 * CSRF 令牌存 HttpSession（非 JS 可读 Cookie），登录后由 controller 或 OIDC 成功处理器轮换会话 ID 与令牌
 * （ADR-0013、ADR-0019）。
 *
 * <p>OIDC 约束：只使用 {@code oauth2Login} 做授权码 + PKCE + nonce；关闭默认登录页与自动重定向；
 * 不持久化 OIDC 主体或任何 token；授权入口/回调是顶层 GET，不使用 CSRF 令牌。
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /**
     * 会话安全上下文仓储。
     *
     * <p>登录由 controller 显式保存，避免匿名请求无意创建会话；OIDC 主体永远不会落会话（ADR-0019 第 2.1 节）。
     *
     * @return OIDC 感知的 HttpSession 安全上下文仓储
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new OidcAwareSecurityContextRepository(new HttpSessionSecurityContextRepository());
    }

    /**
     * CSRF 令牌仓储：令牌存在服务端会话，响应 JSON 返回，不下发 JS 可读 Cookie。
     *
     * @return HttpSession CSRF 令牌仓储
     */
    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    /**
     * 构建安全过滤器链。
     *
     * @param http Spring Security 配置入口
     * @param csrfTokenRepository CSRF 令牌仓储
     * @param securityContextRepository 会话安全上下文仓储
     * @param authenticationEntryPoint 401 响应
     * @param accessDeniedHandler 403 响应
     * @param originCheckFilter 同源校验过滤器
     * @param tenantContextFilter 租户上下文过滤器
     * @param clientRegistrationRepository 动态 OIDC 客户端注册
     * @param oidcAuthorizedClientRepository 不保存 token 的授权客户端仓储
     * @param oidcAuthorizationRequestRepository 会话内授权请求仓储
     * @param oidcUserService 只从 ID Token 构建用户的 OIDC 用户服务
     * @param oidcLoginSuccessHandler OIDC 成功处理器
     * @param oidcLoginFailureHandler OIDC 失败处理器
     * @param oidcAuthorizePreflightFilter 授权入口预检
     * @param oidcLoginRateLimitFilter 回调限流
     * @return 安全过滤器链
     * @throws Exception 过滤器链构建失败
     */
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository securityContextRepository,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler,
            @Qualifier("originCheckFilter") Filter originCheckFilter,
            @Qualifier("tenantContextFilter") Filter tenantContextFilter,
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientRepository oidcAuthorizedClientRepository,
            AuthorizationRequestRepository<OAuth2AuthorizationRequest> oidcAuthorizationRequestRepository,
            OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService,
            AuthenticationSuccessHandler oidcLoginSuccessHandler,
            AuthenticationFailureHandler oidcLoginFailureHandler,
            @Qualifier("oidcAuthorizePreflightFilter") Filter oidcAuthorizePreflightFilter,
            @Qualifier("oidcLoginRateLimitFilter") Filter oidcLoginRateLimitFilter)
            throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
                        .permitAll()
                        // OIDC 三个入口都是顶层 GET；授权入口/回调不使用 CSRF 令牌（ADR-0019 第 2 节）。
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/auth/oidc/providers",
                                "/api/v1/auth/oidc/authorize/**",
                                "/api/v1/auth/oidc/callback/**")
                        .permitAll()
                        .requestMatchers("/api/v1/auth/session", "/api/v1/auth/logout")
                        .authenticated()
                        .requestMatchers("/api/v1/**")
                        .authenticated()
                        // 任何未列出的路径继续默认拒绝，禁止用 permitAll 临时放行。
                        .anyRequest()
                        .denyAll())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository).csrfTokenRequestHandler(csrfRequestHandler))
                .securityContext(securityContext -> securityContext
                        .securityContextRepository(securityContextRepository)
                        .requireExplicitSave(true))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                // 自定义 loginPage 关闭默认登录页与“单 IdP 自动重定向”；JSON 401 由显式入口点覆盖。
                .oauth2Login(oauth2 -> oauth2.loginPage("/login")
                        .clientRegistrationRepository(clientRegistrationRepository)
                        .authorizedClientRepository(oidcAuthorizedClientRepository)
                        .securityContextRepository(securityContextRepository)
                        .authorizationEndpoint(authorization -> authorization
                                .baseUri("/api/v1/auth/oidc/authorize")
                                .authorizationRequestRepository(oidcAuthorizationRequestRepository))
                        .redirectionEndpoint(redirection -> redirection.baseUri("/api/v1/auth/oidc/callback/*"))
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService))
                        .successHandler(oidcLoginSuccessHandler)
                        .failureHandler(oidcLoginFailureHandler))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // 纯 JSON API 不需要表单登录、Basic 认证、默认登出页或请求缓存。
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                // 同源校验在 CSRF 令牌之前执行，形成纵深；租户上下文在安全上下文加载之后建立。
                .addFilterBefore(originCheckFilter, CsrfFilter.class)
                .addFilterAfter(tenantContextFilter, SecurityContextHolderFilter.class)
                // OIDC：预检在授权重定向之前；限流在回调认证之前。
                .addFilterBefore(oidcAuthorizePreflightFilter, OAuth2AuthorizationRequestRedirectFilter.class)
                .addFilterBefore(oidcLoginRateLimitFilter, OAuth2LoginAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 禁止 Servlet 容器重复注册租户过滤器；它只应运行在安全过滤器链中。
     *
     * @param filter 租户上下文过滤器
     * @return 被禁用的注册
     */
    @Bean
    FilterRegistrationBean<Filter> tenantContextFilterRegistration(@Qualifier("tenantContextFilter") Filter filter) {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * 禁止 Servlet 容器重复注册同源校验过滤器；它只应运行在安全过滤器链中。
     *
     * @param filter 同源校验过滤器
     * @return 被禁用的注册
     */
    @Bean
    FilterRegistrationBean<Filter> originCheckFilterRegistration(@Qualifier("originCheckFilter") Filter filter) {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * 禁止 Servlet 容器重复注册 OIDC 授权预检过滤器。
     *
     * @param filter 授权预检过滤器
     * @return 被禁用的注册
     */
    @Bean
    FilterRegistrationBean<Filter> oidcAuthorizePreflightFilterRegistration(
            @Qualifier("oidcAuthorizePreflightFilter") Filter filter) {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * 禁止 Servlet 容器重复注册 OIDC 回调限流过滤器。
     *
     * @param filter 回调限流过滤器
     * @return 被禁用的注册
     */
    @Bean
    FilterRegistrationBean<Filter> oidcLoginRateLimitFilterRegistration(
            @Qualifier("oidcLoginRateLimitFilter") Filter filter) {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
