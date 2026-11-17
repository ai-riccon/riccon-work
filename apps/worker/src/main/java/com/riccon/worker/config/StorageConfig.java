package com.riccon.worker.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3 客户端配置。
 *
 * <p>对象存储只用于读取文件原文；key 由 API 生成并带租户前缀，Worker 读取前再次校验前缀。
 * 客户端设置调用超时，避免对象存储故障拖住租约。
 */
@Configuration(proxyBeanMethods = false)
public class StorageConfig {

    /**
     * 构建 S3 客户端。
     *
     * @param properties 对象存储配置
     * @return S3 客户端
     */
    @Bean
    S3Client s3Client(StorageProperties properties) {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(properties.accessKey(), properties.secretKey());
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .forcePathStyle(properties.pathStyle())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10))
                        .build())
                .build();
    }
}
