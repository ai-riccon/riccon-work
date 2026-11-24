package com.riccon.api.config;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 应用基础 Bean。
 *
 * <p>时钟显式注入，避免业务代码依赖系统默认时区与不可测试的 {@code Instant.now()}；密码编码器固定
 * {@code {bcrypt}} 前缀与强度 12（ADR-0013 第 3 节），便于未来按 {@code algorithm} 列轮换。
 *
 * <p>M05 起启用 Spring 调度：保留到期清理等知识库任务由 M05-B3 以固定延迟实现，调度线程池上界
 * 由部署配置控制，不在请求线程执行业务清理。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({RicconBootstrapProperties.class, KnowledgeAclProperties.class, WorkflowProperties.class
})
public class AppConfig {

    /**
     * UTC 时钟。
     *
     * @return 系统 UTC 时钟
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * 密码编码器：默认 bcrypt，强度 12，输出带 {@code {bcrypt}} 前缀。
     *
     * @return 委托式密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put("bcrypt", new BCryptPasswordEncoder(12));
        return new DelegatingPasswordEncoder("bcrypt", encoders);
    }
}
