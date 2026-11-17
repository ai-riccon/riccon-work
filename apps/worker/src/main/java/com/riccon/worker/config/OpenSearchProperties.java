package com.riccon.worker.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * OpenSearch 索引配置。
 *
 * <p>索引名由 {@code TenantContext} 派生，配置只提供前缀与版本；任何调用方都不能传入索引名，
 * 以免跨租户检索（ADR-0009 第 7 节）。
 *
 * @param url OpenSearch HTTP 端点
 * @param username 可选 Basic Auth 用户名
 * @param password 可选 Basic Auth 密码
 * @param connectTimeout 连接超时
 * @param requestTimeout 请求超时
 * @param indexPrefix 租户索引前缀，默认 riccon-kb
 * @param indexVersion 索引结构版本，默认 v1
 */
@ConfigurationProperties(prefix = "riccon.worker.opensearch")
public record OpenSearchProperties(
        @DefaultValue("http://127.0.0.1:19200") String url,
        @DefaultValue("") String username,
        @DefaultValue("") String password,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("30s") Duration requestTimeout,
        @DefaultValue("riccon-kb") String indexPrefix,
        @DefaultValue("v1") String indexVersion) {

    /** 校验端点、超时与索引前缀，防止空索引名或无限等待。 */
    public OpenSearchProperties {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("riccon.worker.opensearch.url must not be blank");
        }
        URI.create(url);
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("riccon.worker.opensearch.connect-timeout must be positive");
        }
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("riccon.worker.opensearch.request-timeout must be positive");
        }
        if (indexPrefix == null || !indexPrefix.matches("[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("riccon.worker.opensearch.index-prefix must be lowercase");
        }
        if (indexVersion == null || !indexVersion.matches("[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("riccon.worker.opensearch.index-version must be lowercase");
        }
    }
}
