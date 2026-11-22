package com.riccon.worker;

import com.riccon.worker.persistence.SchemaVersionChecker;
import com.riccon.worker.support.ExternalTestServices;
import com.riccon.worker.support.TestCredentials;
import com.riccon.worker.support.TestDataFactory;
import com.riccon.worker.support.TestSchema;
import com.riccon.worker.support.TestServiceToken;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
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
 * <p>默认启动真实 MySQL、OpenSearch 与 RustFS（S3 兼容，ADR-0002 替代不可用的 MinIO 镜像），
 * 执行 {@code apps/api} 的权威迁移并写入 Flyway 版本记录。所有子类共享同一组容器；测试不依赖
 * {@code Thread.sleep}，等待使用容器 wait strategy 与数据库状态断言。
 *
 * <p>M19 P3 起不再在每个测试前 TRUNCATE 39 张业务表：业务数据通过每个测试唯一的租户
 * （{@link #uniqueTenant(String)}）隔离，测试只读写自己的租户。全局目录、网关开关与跨租户
 * outbox 无法按租户隔离，只有确实写这些表的测试类才在自己的 {@code @BeforeEach} 中调用
 * {@link #resetModelGatewayState()}、{@link #resetMcpCatalog()} 或 {@link #clearOutbox()}；
 * 每个 helper 都写明恢复的基线与理由。{@code audit_event} 是 append-only 且按月分区，任何
 * 测试都不得删除它：审计断言必须带本测试租户或唯一目标 ID。
 *
 * <p>仅当显式设置 {@code RICCON_TEST_SERVICES=external} 且提供
 * {@code RICCON_TEST_SERVICES_NAMESPACE} 时，改为复用 compose 热测试栈；见
 * {@link ExternalTestServices}。默认与 CI 行为不变；迁移/回滚测试不经过本类。
 *
 * <p>M04：测试上下文使用 {@code test} profile 的 DEMO 端点允许列表、已知测试主密钥与 fingerprint pepper；
 * schema 期望版本随 V11 升到 11。V11 的全局种子（平台租户、路由链、网关开关）在 JVM 启动时
 * 幂等补齐，测试内修改由各测试类按需恢复。
 *
 * <p>M19 P4a：类级 {@code @Tag("integration")} 由所有子类继承，新增 Worker 集成测试继承本类即可；
 * 迁移/schema 版本测试不继承本类，单独标注 {@code migration}。标签只用于选择，不改变默认执行集合；
 * 规则见 {@code docs/CODING-STANDARDS.md} §10.4。
 */
@Tag("integration")
@SpringBootTest(properties = {"riccon.worker.outbox.scheduling-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class WorkerIntegrationTestBase {

    /** 测试桶名；external 模式带 namespace，默认模式沿用原值。 */
    protected static final String BUCKET = ExternalTestServices.bucketName();

    /**
     * M04 测试端点允许列表：DEMO 模式的本地占位端点。
     *
     * <p>B2 的 {@code ModelEndpointProperties} 会按 code 与 DB 目录对齐；这里的 code 只用于启动校验，
     * 既有 Worker 测试仍使用各自动态端口，B2/B4 迁移时应改为测试专属 allowlist 或 legacy seam。
     */
    private static final String DEMO_ENDPOINTS =
            "[{\"code\":\"demo-local\",\"baseUrl\":\"http://127.0.0.1:19090/v1\",\"environmentLabel\":\"DEMO_ONLY\"}]";

    /** M04 fingerprint pepper 的已知测试占位（Base64 32 字节，不对应任何真实环境）。 */
    private static final String TEST_FINGERPRINT_PEPPER = "cmljY29uLXRlc3QtZmluZ2VycHJpbnQtcGVwcGVyMzI=";

    /** S3 测试凭据，仅用于测试栈。 */
    private static final String S3_ACCESS_KEY = ExternalTestServices.storageAccessKey();

    /** S3 测试密钥，仅用于测试栈。 */
    private static final String S3_SECRET_KEY = ExternalTestServices.storageSecretKey();

    /** MySQL 容器；external 模式不启动。 */
    protected static MySQLContainer mysql;

    /** OpenSearch 容器；external 模式不启动。 */
    protected static GenericContainer<?> openSearch;

    /** RustFS S3 兼容容器；external 模式不启动。 */
    protected static GenericContainer<?> objectStorage;

    static {
        if (ExternalTestServices.enabled()) {
            // 显式 opt-in：连接 compose 热栈；数据库已由 prepare 创建时校验版本并跳过迁移。
            ExternalTestServices.verifyAndLock();
            TestSchema.ensureSchema(
                    ExternalTestServices.jdbcUrl(),
                    ExternalTestServices.username(),
                    ExternalTestServices.password(),
                    SchemaVersionChecker.EXPECTED_SCHEMA_VERSION);
            ExternalTestServices.purgeSearchIndices();
            ensureGlobalBaseline(
                    ExternalTestServices.jdbcUrl(), ExternalTestServices.username(), ExternalTestServices.password());
        } else {
            startContainers();
            TestSchema.applyMigrations(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            TestSchema.createFlywayHistory(
                    mysql.getJdbcUrl(),
                    mysql.getUsername(),
                    mysql.getPassword(),
                    SchemaVersionChecker.EXPECTED_SCHEMA_VERSION);
            ensureGlobalBaseline(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        }
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
     * 每个测试重建数据构造器。
     *
     * <p>不再清空业务表：业务数据由唯一租户隔离。需要恢复全局状态的测试类在自己的
     * {@code @BeforeEach} 中调用 {@link #resetModelGatewayState()}、{@link #resetMcpCatalog()}
     * 或 {@link #clearOutbox()}，避免把所有测试都拖入全表清理。
     */
    @BeforeEach
    void prepareData() {
        data = new TestDataFactory(jdbcClient, s3Client, objectMapper, BUCKET);
    }

    /**
     * 生成带随机后缀的测试租户 ID。
     *
     * <p>租户 ID 同时用于 MySQL 行隔离、OpenSearch 索引别名与 S3 key 前缀；随机后缀保证同一
     * external namespace 连续多次全量运行时不会与历史租户或唯一键冲突。TenantId 允许字符为
     * 字母、数字、下划线与短横线，随机后缀只使用十六进制。
     *
     * @param prefix 可读前缀（例如 tenant-a）
     * @return 长度不超过 64 的唯一租户 ID
     */
    protected static String uniqueTenant(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /**
     * 恢复 M04 全局模型目录、路由链与网关开关的可重复基线。
     *
     * <p>受影响的全局状态：
     *
     * <ul>
     *   <li>{@code model}：BASELINE/单模型解析取最早的 ACTIVE 模型。旧测试的模型若保持 ACTIVE，
     *       会复用已经关闭的测试桩端口；这里统一停用，目录本身保留以满足外键。
     *   <li>{@code model_routing_entry}：全局表，测试自建链在测试间清空；{@code model_routing}
     *       的 V11 种子行保留，只复位“已保存”标记。
     *   <li>{@code model_gateway_switch}：恢复 V11 发版种子 ROUTED/true/false。
     * </ul>
     *
     * <p>平台 DEK 与 provider_credential 由 {@code TestDataFactory.createModelCatalog} 使用
     * 唯一 key id，历史行不会被新 catalog 引用，因此无需删除（provider_credential 对
     * tenant_data_key 有外键，删除顺序也会更复杂）。
     */
    protected void resetModelGatewayState() {
        jdbcClient
                .sql("UPDATE model SET status = 'DISABLED' WHERE status <> 'DISABLED'")
                .update();
        jdbcClient.sql("DELETE FROM model_routing_entry").update();
        jdbcClient.sql("UPDATE model_routing SET updated_by = NULL").update();
        jdbcClient.sql("""
                        UPDATE model_gateway_switch
                           SET switch_value = CASE switch_key
                                 WHEN 'routingMode' THEN 'ROUTED'
                                 WHEN 'failoverEnabled' THEN 'true'
                                 WHEN 'quotaEnforcementEnabled' THEN 'false'
                                 ELSE switch_value
                               END
                        """).update();
    }

    /**
     * 清空 MCP 全局目录与调用记录。
     *
     * <p>{@code mcp_server.code} 是全局唯一键，测试固定使用 {@code riccon-builtin} 等白名单 code；
     * 不清理会与上一次测试冲突。{@code mcp_invocation} 与 {@code mcp_tool_grant} 通过外键
     * 引用工具，必须先删；{@code approval.mcp_tool_id} 只是逻辑引用，不受影响。
     */
    protected void resetMcpCatalog() {
        jdbcClient.sql("DELETE FROM mcp_tool_grant").update();
        jdbcClient.sql("DELETE FROM mcp_invocation").update();
        jdbcClient.sql("DELETE FROM mcp_tool").update();
        jdbcClient.sql("DELETE FROM mcp_server").update();
    }

    /**
     * 清空跨租户 outbox。
     *
     * <p>{@code OutboxPoller.pollOnce} 与 {@code OutboxClaimer.claim} 是唯一按状态跨租户读取的
     * 生产路径，无法用租户过滤隔离。使用它们的测试类必须先清空残留事件，才能继续用
     * {@code pollOnce() == 1} 之类的精确计数断言；这是 P3 保留的按表清理例外之一。
     */
    protected void clearOutbox() {
        jdbcClient.sql("DELETE FROM agent_run_outbox").update();
    }

    /**
     * 注入连接地址；默认容器在静态块启动，external 模式使用 compose 热栈。
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        if (ExternalTestServices.enabled()) {
            registry.add("spring.datasource.url", ExternalTestServices::jdbcUrl);
            registry.add("spring.datasource.username", ExternalTestServices::username);
            registry.add("spring.datasource.password", ExternalTestServices::password);
            registry.add("riccon.worker.opensearch.index-prefix", ExternalTestServices::indexPrefix);
        } else {
            registry.add("spring.datasource.url", mysql::getJdbcUrl);
            registry.add("spring.datasource.username", mysql::getUsername);
            registry.add("spring.datasource.password", mysql::getPassword);
        }
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
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
        registry.add("riccon.worker.crypto.canary", () -> "test-canary");
        registry.add("riccon.worker.crypto.fingerprint-pepper", () -> TEST_FINGERPRINT_PEPPER);
        registry.add("riccon.worker.service-token.secret", () -> TestServiceToken.SECRET);
        registry.add("riccon.worker.model.allow-insecure-http", () -> "true");
        registry.add("riccon.worker.model.retry-delays", () -> "0ms,0ms");
        registry.add("riccon.worker.model.request-timeout", () -> "10s");
        // M04：Worker 集成上下文显式运行在 DEMO + 受管端点允许列表；主密钥与 pepper 使用已知测试占位。
        registry.add("riccon.model.deployment-mode", () -> "DEMO");
        registry.add("riccon.model.endpoints", () -> DEMO_ENDPOINTS);
        registry.add("riccon.model.allow-legacy-endpoints", () -> "true");
        registry.add("riccon.model.allow-http", () -> "true");
        registry.add("riccon.billing.currency", () -> "CNY");
        // B1c F-001：不在此注册 riccon.ops.allowed-cidrs。动态属性优先级高于子类 @TestPropertySource，
        // 会让 PrometheusExposureDenyTest 的“非允许来源”配置失效；默认空值由 application-test.yml 提供。
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

    /**
     * 幂等补齐 V2/V11 的全局种子。
     *
     * <p>每个测试 JVM 启动后执行一次；热栈数据库可能来自上一次运行，不能假设种子仍然存在。
     * 只插入缺失行，不覆盖运维或测试已修改的值：测试内的开关恢复由
     * {@link #resetModelGatewayState()} 负责。
     *
     * @param jdbcUrl 数据库 URL
     * @param username 用户名
     * @param password 密码
     */
    private static void ensureGlobalBaseline(String jdbcUrl, String username, String password) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO tenant (id, name, status, plan, retention_days)
                    SELECT 'platform', '平台管理', 'ACTIVE', 'internal', 180 FROM DUAL
                     WHERE NOT EXISTS (SELECT 1 FROM tenant WHERE id = 'platform')
                    """);
            statement.execute("""
                    INSERT INTO model_routing (purpose)
                    SELECT 'CHAT' FROM DUAL
                     WHERE NOT EXISTS (SELECT 1 FROM model_routing WHERE purpose = 'CHAT')
                    """);
            statement.execute("""
                    INSERT INTO model_routing (purpose)
                    SELECT 'REPORT' FROM DUAL
                     WHERE NOT EXISTS (SELECT 1 FROM model_routing WHERE purpose = 'REPORT')
                    """);
            statement.execute("""
                    INSERT INTO model_gateway_switch (switch_key, switch_value)
                    SELECT 'routingMode', 'ROUTED' FROM DUAL
                     WHERE NOT EXISTS (SELECT 1 FROM model_gateway_switch WHERE switch_key = 'routingMode')
                    """);
            statement.execute("""
                    INSERT INTO model_gateway_switch (switch_key, switch_value)
                    SELECT 'failoverEnabled', 'true' FROM DUAL
                     WHERE NOT EXISTS (SELECT 1 FROM model_gateway_switch WHERE switch_key = 'failoverEnabled')
                    """);
            statement.execute("""
                    INSERT INTO model_gateway_switch (switch_key, switch_value)
                    SELECT 'quotaEnforcementEnabled', 'false' FROM DUAL
                     WHERE NOT EXISTS (
                           SELECT 1 FROM model_gateway_switch WHERE switch_key = 'quotaEnforcementEnabled')
                    """);
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to ensure global test baseline rows", ex);
        }
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
     * 返回共享 MySQL 容器；仅供需要独立数据库的 schema 版本测试在默认模式复用。
     *
     * <p>external 模式返回 {@code null}；调用方必须改用一次性容器，不能把 schema 版本测试
     * 指向热栈数据库。
     *
     * @return 默认模式返回已启动的共享容器
     */
    public static MySQLContainer sharedMysql() {
        return mysql;
    }

    /**
     * 返回对象存储端点。
     *
     * @return HTTP 端点
     */
    protected static String storageEndpoint() {
        if (ExternalTestServices.enabled()) {
            return ExternalTestServices.storageEndpoint();
        }
        return "http://127.0.0.1:" + objectStorage.getMappedPort(9000);
    }

    /**
     * 返回 OpenSearch 端点。
     *
     * @return HTTP 端点
     */
    protected static String openSearchEndpoint() {
        if (ExternalTestServices.enabled()) {
            return ExternalTestServices.openSearchUrl();
        }
        return "http://127.0.0.1:" + openSearch.getMappedPort(9200);
    }

    /**
     * 返回 S3 访问键；供需要独立构造 StorageProperties 的测试使用。
     *
     * @return 访问键
     */
    protected static String storageAccessKey() {
        return S3_ACCESS_KEY;
    }

    /**
     * 返回 S3 密钥；供需要独立构造 StorageProperties 的测试使用。
     *
     * @return 访问密钥
     */
    protected static String storageSecretKey() {
        return S3_SECRET_KEY;
    }
}
