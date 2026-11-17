package com.riccon.worker;

import com.riccon.worker.persistence.SchemaVersionChecker;
import com.riccon.worker.support.TestCredentials;
import com.riccon.worker.support.TestDataFactory;
import com.riccon.worker.support.TestSchema;
import com.riccon.worker.support.TestServiceToken;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.ObjectMapper;

/**
 * Worker 集成测试基类。
 *
 * <p>启动真实 MySQL、OpenSearch 与 RustFS（S3 兼容，ADR-0002 替代不可用的 MinIO 镜像），
 * 执行 {@code apps/api} 的权威迁移并写入 Flyway 版本记录。所有子类共享同一组容器，
 * 每个测试前清空业务表；测试不依赖 {@code Thread.sleep}，等待使用容器 wait strategy 与数据库状态断言。
 */
@SpringBootTest(properties = {"riccon.worker.outbox.scheduling-enabled=false"})
@AutoConfigureMockMvc
public abstract class WorkerIntegrationTestBase {

    /** 测试桶名；与动态属性保持一致。 */
    protected static final String BUCKET = "riccon-test";

    /** S3 测试凭据，仅用于容器。 */
    private static final String S3_ACCESS_KEY = "riccon_test_only";

    /** S3 测试密钥，仅用于容器。 */
    private static final String S3_SECRET_KEY = "riccon_test_only_secret";

    /** MySQL 容器。 */
    protected static MySQLContainer mysql;

    /** OpenSearch 容器。 */
    protected static GenericContainer<?> openSearch;

    /** RustFS S3 兼容容器。 */
    protected static GenericContainer<?> objectStorage;

    static {
        startContainers();
        TestSchema.applyMigrations(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        TestSchema.createFlywayHistory(
                mysql.getJdbcUrl(),
                mysql.getUsername(),
                mysql.getPassword(),
                SchemaVersionChecker.EXPECTED_SCHEMA_VERSION);
        createBucket();
    }

    /** 测试数据库访问。 */
    @Autowired
    protected DataSource dataSource;

    /** 测试 JDBC 客户端。 */
    @Autowired
    protected JdbcClient jdbcClient;

    /** 测试 S3 客户端（生产配置 Bean）。 */
    @Autowired
    protected S3Client s3Client;

    /** JSON 序列化器。 */
    @Autowired
    protected ObjectMapper objectMapper;

    /** 每个测试重建的数据构造器。 */
    protected TestDataFactory data;

    /**
     * 清空业务表并重建数据构造器。
     *
     * <p>{@code audit_event} 有 append-only 触发器与分区，测试不写审计；
     * {@code flyway_schema_history} 与全局权限字典保持不动。
     */
    @BeforeEach
    void resetDatabase() {
        String[] tables = {
            "audit_event",
            "artifact_citation",
            "artifact_version",
            "artifact",
            "approval",
            "mcp_invocation",
            "model_invocation",
            "agent_run_stage",
            "agent_run_outbox",
            "agent_run",
            "research_task",
            "message_citation",
            "message",
            "conversation",
            "knowledge_chunk",
            "knowledge_document",
            "file_chunk",
            "file_parse",
            "file",
            "mcp_tool_grant",
            "provider_credential",
            "tenant_data_key",
            "mcp_tool",
            "mcp_server",
            "model",
            "model_provider",
            "user_role",
            "role_permission",
            "role",
            "user_credential",
            "workspace",
            "app_user",
            "tenant"
        };
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String table : tables) {
                statement.execute("TRUNCATE TABLE " + table);
            }
            // 平台保留租户由 V2 迁移插入；清空后必须恢复，否则平台级凭据外键与跨租户审计无法写入。
            statement.execute("""
                    INSERT INTO tenant (id, name, status, plan, retention_days)
                    VALUES ('platform', '平台管理', 'ACTIVE', 'internal', 180)
                    """);
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to reset integration test database", ex);
        }
        data = new TestDataFactory(jdbcClient, s3Client, objectMapper, BUCKET);
    }

    /**
     * 注入容器地址；容器在静态块启动，属性在 Spring 上下文创建前可用。
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        registry.add("riccon.worker.storage.endpoint", WorkerIntegrationTestBase::storageEndpoint);
        registry.add("riccon.worker.storage.access-key", () -> S3_ACCESS_KEY);
        registry.add("riccon.worker.storage.secret-key", () -> S3_SECRET_KEY);
        registry.add("riccon.worker.storage.bucket", () -> BUCKET);
        registry.add("riccon.worker.opensearch.url", WorkerIntegrationTestBase::openSearchEndpoint);
        registry.add("riccon.worker.outbox.scheduling-enabled", () -> "false");
        registry.add("riccon.worker.outbox.retry-delays", () -> "10ms,20ms,30ms");
        registry.add("riccon.worker.outbox.retry-jitter", () -> "0ms");
        registry.add("riccon.worker.outbox.max-attempts", () -> "3");
        registry.add("riccon.worker.outbox.lease-duration", () -> "30s");
        registry.add("riccon.worker.crypto.master-key", () -> TestCredentials.MASTER_KEY_BASE64);
        registry.add("riccon.worker.crypto.master-key-id", () -> TestCredentials.MASTER_KEY_ID);
        registry.add("riccon.worker.service-token.secret", () -> TestServiceToken.SECRET);
        registry.add("riccon.worker.model.allow-insecure-http", () -> "true");
        registry.add("riccon.worker.model.retry-delays", () -> "0ms,0ms");
        registry.add("riccon.worker.model.request-timeout", () -> "10s");
        registry.add("riccon.worker.mcp.allow-stdio", () -> "true");
        registry.add("riccon.worker.mcp.request-timeout", () -> "15s");
        registry.add("riccon.worker.mcp.initialization-timeout", () -> "10s");
        registry.add("riccon.worker.chat.idle-timeout", () -> "20s");
    }

    /** 启动数据库、检索与对象存储容器。 */
    private static void startContainers() {
        mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4.13"));
        mysql.withDatabaseName("riccon");
        mysql.withUsername("riccon");
        mysql.withPassword("riccon_test_only");
        // V6 审计触发器需要该参数（与 deploy/compose/docker-compose.test.yml 一致）。
        mysql.withCommand(
                "--character-set-server=utf8mb4",
                "--collation-server=utf8mb4_0900_ai_ci",
                "--log-bin-trust-function-creators=1");
        mysql.start();

        openSearch = new GenericContainer<>(DockerImageName.parse("opensearchproject/opensearch:3.9.0"));
        openSearch.withEnv("discovery.type", "single-node");
        openSearch.withEnv("DISABLE_SECURITY_PLUGIN", "true");
        openSearch.withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true");
        openSearch.withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");
        openSearch.withExposedPorts(9200);
        // 让 OpenSearch 自己等待进入 yellow；避免测试 JVM 与容器端口映射的瞬时差异。
        openSearch.waitingFor(Wait.forHttp("/_cluster/health?wait_for_status=yellow&timeout=120s")
                .forPort(9200)
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofSeconds(180)));
        openSearch.start();

        objectStorage = new GenericContainer<>(DockerImageName.parse("rustfs/rustfs:1.0.6"));
        objectStorage.withEnv("RUSTFS_VOLUMES", "/data");
        objectStorage.withEnv("RUSTFS_ADDRESS", "0.0.0.0:9000");
        objectStorage.withEnv("RUSTFS_CONSOLE_ADDRESS", "0.0.0.0:9001");
        objectStorage.withEnv("RUSTFS_CONSOLE_ENABLE", "true");
        objectStorage.withEnv("RUSTFS_ACCESS_KEY", S3_ACCESS_KEY);
        objectStorage.withEnv("RUSTFS_SECRET_KEY", S3_SECRET_KEY);
        objectStorage.withExposedPorts(9000);
        objectStorage.waitingFor(
                Wait.forHttp("/health").forPort(9000).forStatusCode(200).withStartupTimeout(Duration.ofSeconds(60)));
        objectStorage.start();
    }

    /** 创建测试桶；容器每次 JVM 新启动，已存在视为成功。 */
    private static void createBucket() {
        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create(storageEndpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(S3_ACCESS_KEY, S3_SECRET_KEY)))
                .forcePathStyle(true)
                .build()) {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException ex) {
            // 同一容器复用时不重复创建。
        } catch (S3Exception ex) {
            if (ex.statusCode() != 409) {
                throw ex;
            }
        }
    }

    /**
     * 返回对象存储端点。
     *
     * @return HTTP 端点
     */
    protected static String storageEndpoint() {
        return "http://127.0.0.1:" + objectStorage.getMappedPort(9000);
    }

    /**
     * 返回 OpenSearch 端点。
     *
     * @return HTTP 端点
     */
    protected static String openSearchEndpoint() {
        return "http://127.0.0.1:" + openSearch.getMappedPort(9200);
    }
}
