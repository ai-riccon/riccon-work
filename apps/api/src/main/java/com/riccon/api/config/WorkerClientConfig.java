package com.riccon.api.config;

import java.net.http.HttpClient;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Worker 内部客户端配置。
 *
 * <p>固定 HTTP/1.1 与不跟随重定向：SSE 需要稳定的逐帧刷新，重定向会把服务令牌发送到非预期地址。
 * 看门狗线程只负责在空闲/总时长超限时关闭上游流，不执行业务逻辑，使用守护线程避免阻止 JVM 退出。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerClientProperties.class)
public class WorkerClientConfig {

    /**
     * 构建 Worker 专用 HTTP 客户端。
     *
     * @param properties 客户端配置
     * @return HTTP 客户端
     */
    @Bean
    public HttpClient workerHttpClient(WorkerClientProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * 对话流看门狗调度器。
     *
     * @return 单线程守护调度器
     */
    @Bean(name = "chatStreamWatchdog", destroyMethod = "shutdownNow")
    public ScheduledExecutorService chatStreamWatchdog() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chat-stream-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 对话流写线程池。
     *
     * <p>固定 16 线程，与 Worker 默认并发上限一致；SseEmitter 写入阻塞在模型输出上，不能占用 Tomcat 请求线程。
     *
     * @return 固定大小守护线程池
     */
    @Bean(name = "chatStreamExecutor", destroyMethod = "shutdownNow")
    public ExecutorService chatStreamExecutor() {
        return Executors.newFixedThreadPool(16, runnable -> {
            Thread thread = new Thread(runnable, "chat-stream-writer");
            thread.setDaemon(true);
            return thread;
        });
    }
}
