package com.riccon.worker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Worker 安全基线。
 *
 * <p>Worker 只应被集群内部调用，不直接暴露给浏览器或公网。M01 仅放行健康检查；M02 起内部 API 使用服务间
 * 凭据或 mTLS，禁止把 Worker 端口直接暴露到公网。
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
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
