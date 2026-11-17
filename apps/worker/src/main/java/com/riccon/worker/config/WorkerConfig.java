package com.riccon.worker.config;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    ParseProperties.class,
    CryptoProperties.class,
    ServiceTokenProperties.class,
    ModelGatewayProperties.class,
    ChatStreamProperties.class,
    McpProperties.class,
    AgentProperties.class
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

    /**
     * 对话流写线程池。
     *
     * <p>线程数与 {@code max-concurrent-streams} 一致：服务层信号量已限制活跃流数量，
     * 因此执行器不应成为额外瓶颈。写线程阻塞在模型输出与 SseEmitter.send 上，
     * 不能占用 Tomcat 请求线程；守护线程避免阻止 JVM 退出。
     *
     * @param properties 对话流配置
     * @return 固定大小守护线程池
     */
    @Bean(name = "chatStreamExecutor", destroyMethod = "shutdownNow")
    ExecutorService chatStreamExecutor(ChatStreamProperties properties) {
        return Executors.newFixedThreadPool(properties.maxConcurrentStreams(), runnable -> {
            Thread thread = new Thread(runnable, "chat-stream-writer");
            thread.setDaemon(true);
            return thread;
        });
    }
}
