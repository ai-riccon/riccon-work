package com.riccon.api.config;

import com.riccon.api.oidc.infrastructure.OidcAwareSecurityContextRepository;
import com.riccon.api.web.JsonAccessDeniedHandler;
import com.riccon.api.web.JsonAuthenticationEntryPoint;
import jakarta.servlet.Filter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
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
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
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
 * <p>默认拒绝一切，只显式放行健康检查、受 {@code RICCON_OPS_ALLOWED_CIDRS} 来源限制的运维信息/指标端点、
 * CSRF 令牌、内置账号登录与 OIDC 的 providers/authorize/callback；其余路径要求会话认证，具体权限由
 * {@code PermissionInterceptor} 做方法级校验。会话使用 Spring Session JDBC，CSRF 令牌存 HttpSession
 * （非 JS 可读 Cookie），登录后由 controller 或 OIDC 成功处理器轮换会话 ID 与令牌（ADR-0013、ADR-0019）。
 *
 * <p>OIDC 约束：只使用 {@code oauth2Login} 做授权码 + PKCE + nonce；关闭默认登录页与自动重定向；
 * 不持久化 OIDC 主体或任何 token；授权入口/回调是顶层 GET，不使用 CSRF 令牌。
 */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.context.properties.EnableConfigurationProperties(SecurityConfig.OpsProperties.class)
public class SecurityConfig {

    /**
     * 运维观测配置。
     *
     * @param allowedCidrs 允许读取 info 与抓取 Prometheus 的来源 CIDR，逗号分隔；空值仅 loopback
     */
    @ConfigurationProperties(prefix = "riccon.ops")
    public record OpsProperties(@DefaultValue("") String allowedCidrs) {}

    /**
     * 返回 Actuator 运维端点（info/prometheus）的来源授权规则。
     *
     * <p>{@code RICCON_OPS_ALLOWED_CIDRS} 为空时仅允许 loopback；配置存在时只允许列出的 CIDR，
     * 不额外放行 loopback，避免“空配置”与“显式配置”语义混淆。非法 CIDR 在启动时失败。
     * 该规则不改变 {@code anyRequest().denyAll()} 默认拒绝，也不为运维端点添加 permitAll（ADR-0021 §2.2/§10.6）。
     *
     * @param allowedCidrs 逗号分隔的 CIDR 配置
     * @return 访问决策
     */
    static AuthorizationManager<RequestAuthorizationContext> opsEndpointAccess(String allowedCidrs) {
        AllowedCidrs cidrs = AllowedCidrs.parse(allowedCidrs);
        return (authentication, context) ->
                new AuthorizationDecision(cidrs.permits(context.getRequest().getRemoteAddr()));
    }

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
     * @param workflowPlatformTenantFilter 平台租户工作流端点 404 守卫（M06）
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
            @Qualifier("workflowPlatformTenantFilter") Filter workflowPlatformTenantFilter,
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientRepository oidcAuthorizedClientRepository,
            AuthorizationRequestRepository<OAuth2AuthorizationRequest> oidcAuthorizationRequestRepository,
            OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService,
            AuthenticationSuccessHandler oidcLoginSuccessHandler,
            AuthenticationFailureHandler oidcLoginFailureHandler,
            @Qualifier("oidcAuthorizePreflightFilter") Filter oidcAuthorizePreflightFilter,
            @Qualifier("oidcLoginRateLimitFilter") Filter oidcLoginRateLimitFilter,
            OpsProperties opsProperties)
            throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        // M04：info 与 Prometheus 只允许 RICCON_OPS_ALLOWED_CIDRS（空=loopback）的来源；不是业务 RBAC。
                        .requestMatchers("/actuator/info", "/actuator/prometheus")
                        .access(opsEndpointAccess(opsProperties.allowedCidrs()))
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
                // M06：平台租户工作流端点必须在 MVC 权限拦截器之前 404，否则会先返回 403（ADR-0024 §6.1）。
                // 锚点用租户过滤器的运行时类型，保持“租户解析之后、同源校验之前”的顺序；config 层不能
                // import controller 层的 TenantContextFilter（ArchUnit 分层规则），而上一行已把该类型
                // 以同一 Filter 实例注册进本 HttpSecurity 的过滤器顺序表。
                .addFilterAfter(workflowPlatformTenantFilter, tenantContextFilter.getClass())
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
     * 禁止 Servlet 容器重复注册平台租户工作流守卫；它只应运行在安全过滤器链中。
     *
     * @param filter 平台租户工作流守卫
     * @return 被禁用的注册
     */
    @Bean
    FilterRegistrationBean<Filter> workflowPlatformTenantFilterRegistration(
            @Qualifier("workflowPlatformTenantFilter") Filter filter) {
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

    /**
     * 运维来源 CIDR 允许列表。
     *
     * <p>纯 JDK 解析，API 与 Worker 各自持有同语义实现（两处仅用于 Actuator 来源过滤，不进入业务包）：
     * 空配置只允许 loopback；非空配置只允许列出的 CIDR。IPv4 与 IPv6 都支持；非法配置在启动时抛出。
     */
    static final class AllowedCidrs {

        /** 是否只允许 loopback（空配置）。 */
        private final boolean loopbackOnly;

        /** 允许网段。 */
        private final List<Cidr> cidrs;

        /**
         * 创建来源允许列表。
         *
         * @param loopbackOnly 是否只允许 loopback
         * @param cidrs 允许网段
         */
        private AllowedCidrs(boolean loopbackOnly, List<Cidr> cidrs) {
            this.loopbackOnly = loopbackOnly;
            this.cidrs = List.copyOf(cidrs);
        }

        /**
         * 解析逗号分隔的 CIDR；空白视为空配置。
         *
         * @param raw 原始配置
         * @return 允许列表
         * @throws IllegalArgumentException CIDR 非法
         */
        static AllowedCidrs parse(String raw) {
            String value = raw == null ? "" : raw.strip();
            if (value.isEmpty()) {
                return new AllowedCidrs(true, List.of());
            }
            List<Cidr> parsed = new ArrayList<>();
            for (String part : value.split(",")) {
                String token = part.strip();
                if (token.isEmpty()) {
                    continue;
                }
                parsed.add(Cidr.parse(token));
            }
            if (parsed.isEmpty()) {
                return new AllowedCidrs(true, List.of());
            }
            return new AllowedCidrs(false, parsed);
        }

        /**
         * 判断来源地址是否允许。
         *
         * @param remoteAddress 请求来源地址
         * @return 允许返回 true
         */
        boolean permits(String remoteAddress) {
            if (remoteAddress == null || remoteAddress.isBlank()) {
                return false;
            }
            InetAddress address;
            try {
                address = InetAddress.getByName(remoteAddress);
            } catch (UnknownHostException exception) {
                return false;
            }
            if (loopbackOnly) {
                return address.isLoopbackAddress();
            }
            for (Cidr cidr : cidrs) {
                if (cidr.contains(address)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 单个 CIDR 网段。 */
    static final class Cidr {

        /** 网络地址字节。 */
        private final byte[] network;

        /** 前缀长度。 */
        private final int prefix;

        /**
         * 创建网段。
         *
         * @param network 网络地址
         * @param prefix 前缀长度
         */
        private Cidr(byte[] network, int prefix) {
            this.network = network.clone();
            this.prefix = prefix;
        }

        /**
         * 解析 {@code address/prefix}。
         *
         * @param token 网段文本
         * @return 网段
         * @throws IllegalArgumentException 格式或地址非法
         */
        static Cidr parse(String token) {
            int slash = token.lastIndexOf('/');
            if (slash <= 0 || slash == token.length() - 1) {
                throw new IllegalArgumentException("ops.allowed-cidrs entry must be address/prefix");
            }
            String addressText = token.substring(0, slash).strip();
            String prefixText = token.substring(slash + 1).strip();
            InetAddress address;
            try {
                address = InetAddress.getByName(addressText);
            } catch (UnknownHostException exception) {
                throw new IllegalArgumentException("ops.allowed-cidrs entry has an invalid address", exception);
            }
            int bits = address.getAddress().length * 8;
            int prefix;
            try {
                prefix = Integer.parseInt(prefixText);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("ops.allowed-cidrs entry has an invalid prefix", exception);
            }
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("ops.allowed-cidrs prefix must be between 0 and " + bits);
            }
            return new Cidr(address.getAddress(), prefix);
        }

        /**
         * 判断地址是否落在网段内。
         *
         * @param address 来源地址
         * @return 命中返回 true
         */
        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            int length = Math.min(candidate.length, network.length);
            // 地址族不同（IPv4 vs IPv6）时不匹配；IPv4-mapped 由 JDK 归一化为 IPv4。
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int index = 0; index < fullBytes; index++) {
                if (candidate[index] != network[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xff << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
