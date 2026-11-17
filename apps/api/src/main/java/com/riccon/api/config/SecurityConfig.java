package com.riccon.api.config;

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
import org.springframework.security.web.SecurityFilterChain;
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
 * <p>默认拒绝一切，只显式放行健康检查、CSRF 令牌与登录；其余路径要求会话认证，具体权限由
 * {@code PermissionInterceptor} 做方法级校验。会话使用 Spring Session JDBC，CSRF 令牌存 HttpSession
 * （非 JS 可读 Cookie），登录后由 controller 轮换会话 ID 与令牌（ADR-0013）。
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /**
     * 会话安全上下文仓储。
     *
     * <p>登录由 controller 显式保存，避免匿名请求无意创建会话。
     *
     * @return HttpSession 安全上下文仓储
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
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
            @Qualifier("tenantContextFilter") Filter tenantContextFilter)
            throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
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
                .addFilterAfter(tenantContextFilter, SecurityContextHolderFilter.class);
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
}
