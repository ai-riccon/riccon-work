package com.riccon.worker.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Worker 基础设施配置。
 *
 * <p>启用调度、配置属性绑定与 UTC 时钟。所有时间写入 MySQL 前按 UTC 处理（ADR-0012），
 * 注入 {@link Clock} 便于测试重试/租约边界。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({
    OutboxProperties.class,
    StorageProperties.class,
    OpenSearchProperties.class,
    EmbeddingProperties.class,
    ParseProperties.class
})
public class WorkerConfig {

    /**
     * 提供 UTC 时钟；业务代码不得直接调用 {@code System.currentTimeMillis()}。
     *
     * @return UTC 时钟
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
