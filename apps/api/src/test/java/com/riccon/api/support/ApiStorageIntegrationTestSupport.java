package com.riccon.api.support;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 需要真实对象存储的 API 集成测试基类。
 *
 * <p>按 ADR-0002 使用 RustFS（S3 协议）容器；与 MySQL 基类共享数据库，但动态属性不同会创建独立 Spring
 * 上下文。所有测试断言都通过真实 S3 客户端或预签名 URL 完成，不使用内存替身。
 */
public abstract class ApiStorageIntegrationTestSupport extends ApiIntegrationTestSupport {

    /** 测试桶名。 */
    protected static final String BUCKET = "riccon-test";

    /** 测试访问键，仅用于容器。 */
    private static final String ACCESS_KEY = "riccon_test_only";

    /** 测试密钥，仅用于容器。 */
    private static final String SECRET_KEY = "riccon_test_only_secret";

    /** RustFS 容器。 */
    private static final GenericContainer<?> OBJECT_STORAGE = new GenericContainer<>(
                    DockerImageName.parse("rustfs/rustfs:1.0.6"))
            .withEnv("RUSTFS_VOLUMES", "/data")
            .withEnv("RUSTFS_ADDRESS", "0.0.0.0:9000")
            .withEnv("RUSTFS_CONSOLE_ADDRESS", "0.0.0.0:9001")
            .withEnv("RUSTFS_CONSOLE_ENABLE", "true")
            .withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
            .withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/health")
                    .forPort(9000)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    static {
        OBJECT_STORAGE.start();
        createBucket();
    }

    /** 生产配置中的 S3 客户端。 */
    @Autowired
    protected S3Client s3Client;

    /**
     * 注入对象存储连接参数。
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("riccon.storage.endpoint", ApiStorageIntegrationTestSupport::endpoint);
        registry.add("riccon.storage.region", () -> "us-east-1");
        registry.add("riccon.storage.access-key", () -> ACCESS_KEY);
        registry.add("riccon.storage.secret-key", () -> SECRET_KEY);
        registry.add("riccon.storage.bucket", () -> BUCKET);
        registry.add("riccon.storage.path-style", () -> "true");
        registry.add("riccon.storage.download-url-ttl", () -> "300s");
    }

    /**
     * 读取对象内容，供测试断言上传是否真实落盘。
     *
     * @param key 对象 key
     * @return 对象字节
     */
    protected byte[] objectBytes(String key) {
        return s3Client.getObjectAsBytes(
                        GetObjectRequest.builder().bucket(BUCKET).key(key).build())
                .asByteArray();
    }

    /**
     * 判断对象是否存在。
     *
     * @param key 对象 key
     * @return 存在时返回 {@code true}
     */
    protected boolean objectExists(String key) {
        try {
            s3Client.headObject(builder -> builder.bucket(BUCKET).key(key));
            return true;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return false;
            }
            throw exception;
        }
    }

    /**
     * 判断指定前缀下没有对象。
     *
     * @param prefix key 前缀
     * @return 无对象时返回 {@code true}
     */
    protected boolean objectPrefixEmpty(String prefix) {
        return s3Client.listObjectsV2(builder -> builder.bucket(BUCKET).prefix(prefix))
                .contents()
                .isEmpty();
    }

    /**
     * 构造容器端点。
     *
     * @return S3 端点
     */
    private static String endpoint() {
        return "http://" + OBJECT_STORAGE.getHost() + ":" + OBJECT_STORAGE.getMappedPort(9000);
    }

    /** 创建测试桶；容器重启或复用场景下已存在视为成功。 */
    private static void createBucket() {
        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .forcePathStyle(true)
                .build()) {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException exception) {
            // 同一容器复用时不重复创建。
        } catch (S3Exception exception) {
            if (exception.statusCode() != 409) {
                throw exception;
            }
        }
    }
}
