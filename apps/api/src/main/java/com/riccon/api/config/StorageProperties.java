package com.riccon.api.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * S3 兼容对象存储配置。
 *
 * <p>API 只实现 S3 协议，不感知 RustFS/MinIO；凭据由环境变量注入，禁止写入仓库、镜像或日志。
 * 下载地址 TTL 按 OpenAPI 约束不超过 300 秒，配置不合法时启动失败，避免运行时才发现越界。
 *
 * @param endpoint S3 端点，例如 {@code http://127.0.0.1:19000}
 * @param region 签名区域；S3 兼容实现通常使用 {@code us-east-1}
 * @param accessKey 访问键
 * @param secretKey 访问密钥
 * @param bucket 私有桶名
 * @param pathStyle 是否强制 path-style；S3 兼容实现一般需要 {@code true}
 * @param downloadUrlTtl 预签名下载地址有效期
 */
@ConfigurationProperties(prefix = "riccon.storage")
public record StorageProperties(
        @DefaultValue("http://127.0.0.1:19000") String endpoint,
        @DefaultValue("us-east-1") String region,
        @DefaultValue("") String accessKey,
        @DefaultValue("") String secretKey,
        @DefaultValue("riccon") String bucket,
        @DefaultValue("true") boolean pathStyle,
        @DefaultValue("300s") Duration downloadUrlTtl) {

    /** 合同允许的最长下载地址有效期。 */
    private static final Duration MAX_DOWNLOAD_TTL = Duration.ofSeconds(300);

    /** 校验端点、桶名与 TTL，避免启动后才发现配置不可用。 */
    public StorageProperties {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("riccon.storage.endpoint must not be blank");
        }
        URI.create(endpoint);
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("riccon.storage.bucket must not be blank");
        }
        if (downloadUrlTtl == null || downloadUrlTtl.isNegative() || downloadUrlTtl.isZero()) {
            throw new IllegalArgumentException("riccon.storage.download-url-ttl must be positive");
        }
        if (downloadUrlTtl.compareTo(MAX_DOWNLOAD_TTL) > 0) {
            throw new IllegalArgumentException("riccon.storage.download-url-ttl must not exceed 300s");
        }
    }
}
