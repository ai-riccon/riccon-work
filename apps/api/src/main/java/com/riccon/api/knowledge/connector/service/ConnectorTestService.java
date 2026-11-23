package com.riccon.api.knowledge.connector.service;

import com.riccon.api.auth.repository.TenantDirectoryRepository;
import com.riccon.api.error.ForbiddenException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.knowledge.connector.config.ConnectorProperties;
import com.riccon.api.knowledge.connector.domain.ConnectorConfig;
import com.riccon.api.knowledge.connector.domain.ConnectorEndpointConfig;
import com.riccon.api.knowledge.connector.domain.ConnectorRecord;
import com.riccon.api.knowledge.connector.domain.ConnectorTestCategory;
import com.riccon.api.knowledge.connector.domain.ConnectorTestOutcome;
import com.riccon.api.knowledge.connector.repository.ConnectorRepository;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.PermissionCodes;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.net.ssl.SSLException;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 连接测试：一次只读 {@code ListObjectsV2(max-keys=1)}。
 *
 * <p>不写对象、不下载对象、不返回凭据或端点 URL；空 bucket 也算成功。失败仍返回 200，按
 * AUTH/NETWORK/ENDPOINT/CONFIG 分类给出中文原因（ADR-0023 第 6.3 节、RUNBOOK「M05b-5」）。
 *
 * <p>S3 客户端使用 Apache5 HTTP 客户端；该客户端禁用自动重定向，且调用前已按允许列表校验主机，
 * 防止 SDK 自行切换到未授权主机（TM-61）。
 */
@Service
public class ConnectorTestService {

    /** 连接器仓储。 */
    private final ConnectorRepository connectorRepository;

    /** 连接器配置。 */
    private final ConnectorProperties properties;

    /** 凭据解密。 */
    private final ConnectorCredentials credentials;

    /** 租户目录。 */
    private final TenantDirectoryRepository tenantDirectoryRepository;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建测试服务。
     *
     * @param connectorRepository 连接器仓储
     * @param properties 连接器配置
     * @param credentials 凭据解密
     * @param tenantDirectoryRepository 租户目录
     * @param clock 可注入时钟
     */
    public ConnectorTestService(
            ConnectorRepository connectorRepository,
            ConnectorProperties properties,
            ConnectorCredentials credentials,
            TenantDirectoryRepository tenantDirectoryRepository,
            Clock clock) {
        this.connectorRepository = Objects.requireNonNull(connectorRepository, "connectorRepository must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.tenantDirectoryRepository =
                Objects.requireNonNull(tenantDirectoryRepository, "tenantDirectoryRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 执行连接测试。
     *
     * @param actor 当前主体
     * @param connectorId 连接器 ID
     * @return 测试结果与时间
     */
    public TestResult test(RicconPrincipal actor, UUID connectorId) {
        if (!properties.enabled()) {
            throw new ConnectorUnavailableException("connector feature is disabled");
        }
        TenantId tenantId = TenantId.of(actor.tenantId());
        boolean activeTenant = tenantDirectoryRepository
                .findById(actor.tenantId())
                .map(tenant -> tenant.isActive())
                .orElse(false);
        if (!activeTenant) {
            throw new ForbiddenException(PermissionCodes.KNOWLEDGE_CONNECTOR_MANAGE);
        }
        ConnectorRecord connector = connectorRepository
                .findActiveById(tenantId, connectorId)
                .orElseThrow(() -> new NotFoundException("connector"));
        Instant testedAt = clock.instant();
        ConnectorTestOutcome outcome = runTest(tenantId, connector);
        return new TestResult(outcome, testedAt);
    }

    /**
     * 执行只读测试。
     *
     * @param tenantId 租户
     * @param connector 连接器
     * @return 测试结果
     */
    private ConnectorTestOutcome runTest(TenantId tenantId, ConnectorRecord connector) {
        ConnectorEndpointConfig endpoint = properties.registry() == null
                ? null
                : properties.registry().findById(connector.endpointId()).orElse(null);
        if (endpoint == null) {
            return ConnectorTestOutcome.failed(
                    ConnectorTestCategory.ENDPOINT, "连接器端点不在部署允许列表中，请联系运维检查 RICCON_CONNECTOR_ENDPOINTS。");
        }
        try {
            // 每次连接前重新解析主机，防止 DNS 在启动校验后被改写为元数据/链路本地地址。
            properties.policy().verifyResolvedHost(URI.create(endpoint.url()), endpoint.id());
        } catch (IllegalArgumentException exception) {
            return ConnectorTestOutcome.failed(ConnectorTestCategory.ENDPOINT, "连接器端点解析到禁止的地址，请联系运维检查端点 DNS。");
        }
        ConnectorConfig config = connector.config();
        if (!connector.hasCredential()) {
            return ConnectorTestOutcome.failed(ConnectorTestCategory.CONFIG, "连接器尚未配置凭据，请先写入访问密钥。");
        }
        String region =
                config.region() == null || config.region().isBlank() ? properties.defaultRegion() : config.region();
        ConnectorCredentials.PlainCredential plain;
        try {
            plain = credentials.decrypt(tenantId, connector);
        } catch (RuntimeException exception) {
            // 解密失败不区分主密钥/密文原因，避免成为探测信号；管理员按 RUNBOOK 重新写入凭据。
            return ConnectorTestOutcome.failed(ConnectorTestCategory.CONFIG, "连接器凭据不可用，请重新写入凭据后再试。");
        }
        try (plain) {
            char[] accessKeyId = plain.accessKeyId();
            char[] secretAccessKey = plain.secretAccessKey();
            AwsBasicCredentials awsCredentials;
            try {
                awsCredentials = AwsBasicCredentials.create(new String(accessKeyId), new String(secretAccessKey));
            } finally {
                java.util.Arrays.fill(accessKeyId, '\0');
                java.util.Arrays.fill(secretAccessKey, '\0');
            }
            try (S3Client client = buildClient(endpoint, region, awsCredentials)) {
                client.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(config.bucket())
                        .prefix(config.prefix())
                        .maxKeys(1)
                        .build());
                return ConnectorTestOutcome.succeeded();
            } catch (S3Exception exception) {
                return mapS3Failure(exception);
            } catch (SdkClientException exception) {
                return mapClientFailure(exception);
            } catch (RuntimeException exception) {
                return ConnectorTestOutcome.failed(ConnectorTestCategory.NETWORK, "连接测试失败，请检查网络与端点配置。");
            }
        }
    }

    /**
     * 构建一次性 S3 客户端；禁用重定向、限制调用超时。
     *
     * @param endpoint 端点
     * @param region 区域
     * @param awsCredentials 凭据
     * @return S3 客户端
     */
    private S3Client buildClient(ConnectorEndpointConfig endpoint, String region, AwsBasicCredentials awsCredentials) {
        Duration timeout = properties.testTimeout();
        // AWS SDK 默认 HTTP 客户端（Apache5）禁用自动重定向；这里只设置总调用与单次尝试超时，
        // 不引入额外 HTTP 客户端依赖（ADR-0023 第 6.5 节）。
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint.url()))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(awsCredentials))
                .forcePathStyle(properties.pathStyle())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(timeout)
                        .apiCallAttemptTimeout(timeout)
                        .build())
                .build();
    }

    /**
     * 映射 S3 错误。
     *
     * @param exception S3 异常
     * @return 结果
     */
    private static ConnectorTestOutcome mapS3Failure(S3Exception exception) {
        String code = exception.awsErrorDetails() == null
                ? ""
                : exception.awsErrorDetails().errorCode();
        int status = exception.statusCode();
        if (status == 401
                || status == 403
                || "InvalidAccessKeyId".equals(code)
                || "SignatureDoesNotMatch".equals(code)
                || "AccessDenied".equals(code)
                || "ExpiredToken".equals(code)
                || "InvalidToken".equals(code)) {
            return ConnectorTestOutcome.failed(ConnectorTestCategory.AUTH, "源端拒绝了凭据，请检查访问键与密钥是否有效。");
        }
        if ("NoSuchBucket".equals(code) || "InvalidBucketName".equals(code) || status == 404) {
            return ConnectorTestOutcome.failed(ConnectorTestCategory.CONFIG, "bucket 不存在或名称非法，请检查连接器配置。");
        }
        if (status == 301 || status == 302 || status == 307 || status == 308) {
            return ConnectorTestOutcome.failed(ConnectorTestCategory.ENDPOINT, "源端要求重定向；连接器不跟随重定向。");
        }
        return ConnectorTestOutcome.failed(ConnectorTestCategory.NETWORK, "源端返回异常响应，请稍后重试或检查端点。");
    }

    /**
     * 映射网络错误；只返回分类与中文原因，不包含主机或异常栈。
     *
     * @param exception SDK 客户端异常
     * @return 结果
     */
    private static ConnectorTestOutcome mapClientFailure(SdkClientException exception) {
        Throwable cause = exception.getCause();
        if (hasCause(cause, UnknownHostException.class)) {
            return ConnectorTestOutcome.failed(ConnectorTestCategory.ENDPOINT, "无法解析连接器端点主机，请检查 DNS 与允许列表。");
        }
        if (cause instanceof SSLException || hasCause(cause, SSLException.class)) {
            return ConnectorTestOutcome.failed(ConnectorTestCategory.NETWORK, "TLS 握手失败，请检查端点证书与协议配置。");
        }
        if (cause instanceof ConnectException
                || hasCause(cause, ConnectException.class)
                || cause instanceof java.net.SocketTimeoutException
                || hasCause(cause, java.net.SocketTimeoutException.class)
                || cause instanceof java.net.http.HttpTimeoutException) {
            return ConnectorTestOutcome.failed(ConnectorTestCategory.NETWORK, "连接器端点不可达或超时，请检查网络与防火墙。");
        }
        return ConnectorTestOutcome.failed(ConnectorTestCategory.NETWORK, "连接器端点不可达，请检查网络与端点配置。");
    }

    /**
     * 判断异常链是否包含指定类型。
     *
     * @param throwable 异常
     * @param type 类型
     * @return 命中时返回 true
     */
    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 8) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    /**
     * 测试结果与测试时间。
     *
     * @param outcome 结果
     * @param testedAt 测试时间
     */
    public record TestResult(ConnectorTestOutcome outcome, Instant testedAt) {}
}
