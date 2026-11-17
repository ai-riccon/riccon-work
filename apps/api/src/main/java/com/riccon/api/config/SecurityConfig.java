package com.riccon.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * API 安全基线。
 *
 * <p>M01 尚未接入认证，因此默认拒绝全部业务请求，只放行健康检查端点。M02 接入认证、租户解析与 RBAC 时，
 * 必须通过独立的过滤器链扩展，禁止把 {@code anyRequest().permitAll()} 作为临时放行手段提交。
 *
 * <p>本类只定义“默认拒绝”的边界，不包含任何认证方式；无状态会话策略用于避免在认证机制落地前引入隐式会话。
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /**
     * 构建默认拒绝的安全过滤器链。
     *
     * @param http Spring Security 的 HTTP 配置入口
     * @return 安全过滤器链
     * @throws Exception 过滤器链构建失败
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .anyRequest()
                        .denyAll())
                // M01 没有浏览器表单与 Basic 认证；关闭它们以避免暴露默认登录入口。
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
