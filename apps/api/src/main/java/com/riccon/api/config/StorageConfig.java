package com.riccon.api.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 对象存储客户端配置。
 *
 * <p>上传与预签名共用同一端点、区域和凭据；超时与重试上限防止对象存储故障拖住 API 线程。S3 客户端只负责
 * 协议，租户前缀校验在 {@code S3FileStorage} 中强制。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    /**
     * 构建 S3 客户端。
     *
     * @param properties 对象存储配置
     * @return S3 客户端
     */
    @Bean
    S3Client s3Client(StorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .forcePathStyle(properties.pathStyle())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10))
                        .build())
                .build();
    }

    /**
     * 构建预签名器。
     *
     * <p>预签名器不发起网络请求，但必须与客户端使用相同的 path-style 与凭据，否则对象存储会拒绝签名。
     *
     * @param properties 对象存储配置
     * @return S3 预签名器
     */
    @Bean
    S3Presigner s3Presigner(StorageProperties properties) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyle())
                        .build())
                .build();
    }

    /**
     * 构建静态凭据提供器。
     *
     * @param properties 对象存储配置
     * @return 静态凭据提供器
     */
    private static StaticCredentialsProvider credentials(StorageProperties properties) {
        if (properties.accessKey() == null
                || properties.accessKey().isBlank()
                || properties.secretKey() == null
                || properties.secretKey().isBlank()) {
            throw new IllegalArgumentException("riccon.storage.access-key and secret-key must be configured");
        }
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
    }
}
