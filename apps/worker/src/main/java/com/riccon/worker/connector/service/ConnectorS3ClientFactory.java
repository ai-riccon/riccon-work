package com.riccon.worker.connector.service;

import com.riccon.worker.connector.config.ConnectorProperties;
import com.riccon.worker.connector.domain.ConnectorConfig;
import com.riccon.worker.connector.domain.ConnectorCredential;
import com.riccon.worker.connector.domain.ConnectorEndpointConfig;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 按连接器端点构建一次性 S3 客户端。
 *
 * <p>端点来自部署允许列表；凭据只来自解密后的内存。客户端不使用 SDK 的默认凭据链、不跨端点复用，
 * 只配置 path-style 与调用超时。AWS SDK 默认 HTTP 客户端禁用自动重定向，并在策略层再次校验重定向目标。
 */
@Component
public class ConnectorS3ClientFactory {

    /** 配置。 */
    private final ConnectorProperties properties;

    /**
     * 创建工厂。
     *
     * @param properties 连接器配置
     */
    public ConnectorS3ClientFactory(ConnectorProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * 构建 S3 客户端。
     *
     * @param endpoint 端点
     * @param config 连接器配置
     * @param credential 明文凭据
     * @return S3 客户端
     */
    public S3Client build(ConnectorEndpointConfig endpoint, ConnectorConfig config, ConnectorCredential credential) {
        String region =
                config.region() == null || config.region().isBlank() ? properties.defaultRegion() : config.region();
        char[] accessKeyId = credential.accessKeyId();
        char[] secretAccessKey = credential.secretAccessKey();
        try {
            AwsBasicCredentials credentials =
                    AwsBasicCredentials.create(new String(accessKeyId), new String(secretAccessKey));
            Duration objectTimeout = properties.objectTimeout();
            return S3Client.builder()
                    .endpointOverride(URI.create(endpoint.url()))
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .forcePathStyle(properties.pathStyle())
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .apiCallTimeout(objectTimeout)
                            .apiCallAttemptTimeout(objectTimeout)
                            .build())
                    .build();
        } finally {
            Arrays.fill(accessKeyId, '\0');
            Arrays.fill(secretAccessKey, '\0');
        }
    }
}
