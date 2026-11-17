package com.riccon.worker.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * S3 兼容对象存储配置。
 *
 * <p>应用只依赖 S3 协议，不感知 RustFS/MinIO；凭据只从环境变量注入，禁止写入仓库与镜像。
 * 对象 key 的租户前缀校验在 {@code S3FileContentReader} 中执行，这里只负责连接参数。
 *
 * @param endpoint S3 端点（例如 http://127.0.0.1:19000）
 * @param region 签名区域；S3 兼容实现通常使用 us-east-1
 * @param accessKey 访问键
 * @param secretKey 访问密钥
 * @param bucket 私有桶名
 * @param pathStyle 是否强制 path-style；S3 兼容实现一般需要 true
 */
@ConfigurationProperties(prefix = "riccon.worker.storage")
public record StorageProperties(
        @DefaultValue("http://127.0.0.1:19000") String endpoint,
        @DefaultValue("us-east-1") String region,
        @DefaultValue("") String accessKey,
        @DefaultValue("") String secretKey,
        @DefaultValue("riccon") String bucket,
        @DefaultValue("true") boolean pathStyle) {

    /** 校验端点与桶名，避免启动后才发现配置不可用。 */
    public StorageProperties {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("riccon.worker.storage.endpoint must not be blank");
        }
        URI.create(endpoint);
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("riccon.worker.storage.bucket must not be blank");
        }
    }
}
