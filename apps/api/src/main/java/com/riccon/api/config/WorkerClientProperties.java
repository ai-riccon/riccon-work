package com.riccon.api.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * API → Worker 内部对话流客户端配置。
 *
 * <p>共享 HMAC 密钥只由部署环境注入；缺失或过短时 API 拒绝启动，避免运行期才发现所有对话都失败。
 * 令牌有效期上限固定 300 秒（ADR-0014 第 5 节），不能通过配置放宽。空闲超时与总时长上限对应
 * ADR-0014 第 4 节与编码规范第 13 节。
 *
 * @param baseUrl Worker 内部地址，只允许 http/https；生产必须使用集群内地址
 * @param connectTimeout 建立连接超时
 * @param tokenTtl 服务令牌有效期，1–300 秒
 * @param serviceTokenSecret HMAC-SHA256 共享密钥，至少 32 个字符
 * @param cancelTimeout 取消请求超时
 * @param idleTimeout SSE 空闲上限，超过后 API 取消上游并发送 failed
 * @param maxDuration SSE 单次回答总时长上限
 */
@ConfigurationProperties(prefix = "riccon.worker.client")
public record WorkerClientProperties(
        @DefaultValue("http://127.0.0.1:8081") String baseUrl,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("300s") Duration tokenTtl,
        @DefaultValue("") String serviceTokenSecret,
        @DefaultValue("10s") Duration cancelTimeout,
        @DefaultValue("60s") Duration idleTimeout,
        @DefaultValue("10m") Duration maxDuration) {

    /** 服务令牌允许的最长有效期。 */
    private static final Duration MAX_TOKEN_TTL = Duration.ofSeconds(300);

    /** 校验地址、密钥与超时，防止错误配置被带入运行期。 */
    public WorkerClientProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("riccon.worker.client.base-url must not be blank");
        }
        URI uri = URI.create(baseUrl);
        if (uri.getScheme() == null
                || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("riccon.worker.client.base-url must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("riccon.worker.client.base-url must include a host");
        }
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(cancelTimeout, "cancel-timeout");
        requirePositive(idleTimeout, "idle-timeout");
        requirePositive(maxDuration, "max-duration");
        if (tokenTtl == null || tokenTtl.isNegative() || tokenTtl.isZero() || tokenTtl.compareTo(MAX_TOKEN_TTL) > 0) {
            throw new IllegalArgumentException("riccon.worker.client.token-ttl must be between 1s and 300s");
        }
        if (serviceTokenSecret == null || serviceTokenSecret.length() < 32) {
            throw new IllegalArgumentException(
                    "riccon.worker.client.service-token-secret must be at least 32 characters (injected at deploy)");
        }
    }

    /**
     * 校验时长为正。
     *
     * @param value 时长
     * @param name 配置名
     */
    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("riccon.worker.client." + name + " must be positive");
        }
    }
}
