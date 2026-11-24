package com.riccon.api.knowledge.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.ExternalTestServices;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 连接器 API 集成测试基座。
 *
 * <p>复用真实 MySQL 与 RustFS，并使用唯一租户隔离数据；连接器端点允许列表在上下文启动前注入，指向同一个
 * RustFS。测试只通过公开 HTTP 端点与数据库断言验证契约，不使用测试专用后门。
 */
abstract class ConnectorApiTestSupport extends ApiIntegrationTestSupport {

    /** JSON 序列化。 */
    protected static final ObjectMapper JSON = new ObjectMapper();

    /** 连接器源桶名；带随机后缀，避免共享 RustFS 时互相干扰。 */
    protected static final String BUCKET =
            "riccon-connector-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    /**
     * 平台桶名；与连接器源桶分离。
     *
     * <p>连接器同步把对象写入平台桶，因此下载用例必须证明读取的是平台桶而不是连接器源桶；
     * external 模式下带 namespace，避免并行 agent 共用对象。
     */
    protected static final String PLATFORM_BUCKET = ExternalTestServices.bucketName();

    /** 本地 S3 端点 ID。 */
    protected static final String ENDPOINT_ID = "local-rustfs";

    /** 不可达端点 ID，用于 NETWORK 失败分类。 */
    protected static final String UNREACHABLE_ENDPOINT_ID = "unreachable";

    /** 错误凭据 access key。 */
    protected static final String WRONG_ACCESS_KEY = "wrong-access-key";

    /** 错误凭据 secret key。 */
    protected static final String WRONG_SECRET_KEY = "wrong-secret-key";

    /** RustFS 容器；external 模式不启动。 */
    private static final GenericContainer<?> OBJECT_STORAGE = new GenericContainer<>(
                    DockerImageName.parse("rustfs/rustfs:1.0.6"))
            .withEnv("RUSTFS_VOLUMES", "/data")
            .withEnv("RUSTFS_ADDRESS", "0.0.0.0:9000")
            .withEnv("RUSTFS_CONSOLE_ADDRESS", "0.0.0.0:9001")
            .withEnv("RUSTFS_CONSOLE_ENABLE", "true")
            .withEnv("RUSTFS_ACCESS_KEY", ExternalTestServices.storageAccessKey())
            .withEnv("RUSTFS_SECRET_KEY", ExternalTestServices.storageSecretKey())
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/health")
                    .forPort(9000)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    static {
        if (!ExternalTestServices.enabled()) {
            OBJECT_STORAGE.start();
        }
        createBucket(BUCKET);
        createBucket(PLATFORM_BUCKET);
    }

    /**
     * 注入连接器与存储配置。
     *
     * @param registry 动态属性
     */
    @DynamicPropertySource
    static void connectorProperties(DynamicPropertyRegistry registry) {
        registry.add("riccon.storage.endpoint", ConnectorApiTestSupport::endpoint);
        registry.add("riccon.storage.access-key", ExternalTestServices::storageAccessKey);
        registry.add("riccon.storage.secret-key", ExternalTestServices::storageSecretKey);
        registry.add("riccon.storage.bucket", () -> PLATFORM_BUCKET);
        registry.add("riccon.storage.path-style", () -> "true");
        registry.add("riccon.connector.enabled", () -> "true");
        registry.add("riccon.connector.endpoints", ConnectorApiTestSupport::endpointJson);
        registry.add("riccon.connector.sync-interval-minutes", () -> "60");
        registry.add("riccon.connector.test-timeout", () -> "3s");
        registry.add("riccon.connector.path-style", () -> "true");
        registry.add("riccon.connector.default-region", () -> "us-east-1");
    }

    /**
     * 构造租户与管理员/员工会话。
     *
     * @return 夹具
     */
    protected ConnectorFixture connectorFixture() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("conn");
        createTenant(platform, tenant);
        LoginSession admin = createAndLoginTenantAdmin(platform, tenant);
        LoginSession member = createAndLoginMember(platform, tenant);
        return new ConnectorFixture(tenant, platform, admin, member);
    }

    /**
     * 创建连接器。
     *
     * @param session 管理员会话
     * @param name 名称
     * @param visibility 可见范围
     * @param departmentId 部门
     * @param scheduleEnabled 调度开关
     * @param credential 可选凭据
     * @return HTTP 响应
     */
    protected ApiTestClient.ApiResponse createConnector(
            LoginSession session,
            String name,
            String visibility,
            UUID departmentId,
            boolean scheduleEnabled,
            Credential credential) {
        ObjectNode body = JSON.createObjectNode();
        body.put("name", name);
        body.put("type", "S3");
        body.put("endpointId", ENDPOINT_ID);
        ObjectNode config = body.putObject("config");
        config.put("bucket", BUCKET);
        config.put("prefix", prefix(name));
        body.put("defaultVisibility", visibility);
        if (departmentId != null) {
            body.put("defaultDepartmentId", departmentId.toString());
        }
        body.put("scheduleEnabled", scheduleEnabled);
        if (credential != null) {
            ObjectNode credentialNode = body.putObject("credential");
            credentialNode.put("accessKeyId", credential.accessKeyId());
            credentialNode.put("secretAccessKey", credential.secretAccessKey());
        }
        return client.post(
                "/api/v1/knowledge/connectors",
                JSON.writeValueAsString(body),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of("Idempotency-Key", "connector-create-" + UUID.randomUUID()));
    }

    /**
     * 写入凭据。
     *
     * @param session 管理员会话
     * @param connectorId 连接器 ID
     * @param credential 凭据
     * @return HTTP 响应
     */
    protected ApiTestClient.ApiResponse putCredential(LoginSession session, String connectorId, Credential credential) {
        ObjectNode body = JSON.createObjectNode();
        body.put("accessKeyId", credential.accessKeyId());
        body.put("secretAccessKey", credential.secretAccessKey());
        return client.put(
                "/api/v1/knowledge/connectors/" + connectorId + "/credential",
                JSON.writeValueAsString(body),
                session.sessionCookie(),
                session.csrfToken());
    }

    /**
     * 触发连接测试。
     *
     * @param session 管理员会话
     * @param connectorId 连接器 ID
     * @return HTTP 响应
     */
    protected ApiTestClient.ApiResponse testConnector(LoginSession session, String connectorId) {
        return client.post(
                "/api/v1/knowledge/connectors/" + connectorId + "/test",
                "",
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
    }

    /**
     * 触发同步。
     *
     * @param session 管理员会话
     * @param connectorId 连接器 ID
     * @return HTTP 响应
     */
    protected ApiTestClient.ApiResponse syncConnector(LoginSession session, String connectorId) {
        return client.post(
                "/api/v1/knowledge/connectors/" + connectorId + "/sync",
                "",
                session.sessionCookie(),
                session.csrfToken(),
                Map.of("Idempotency-Key", "connector-sync-" + UUID.randomUUID()));
    }

    /**
     * 读取条目分页。
     *
     * @param session 会话
     * @param connectorId 连接器 ID
     * @param query 查询串
     * @return HTTP 响应
     */
    protected ApiTestClient.ApiResponse listItems(LoginSession session, String connectorId, String query) {
        return client.get(
                "/api/v1/knowledge/connectors/" + connectorId + "/items" + query, session.sessionCookie(), Map.of());
    }

    /**
     * 上传对象到测试桶。
     *
     * @param key 对象 key
     * @param content 内容
     */
    protected void putObject(String key, byte[] content) {
        try (S3Client s3 = s3Client()) {
            s3.putObject(
                    builder -> builder.bucket(BUCKET).key(key).contentType("application/pdf"),
                    software.amazon.awssdk.core.sync.RequestBody.fromBytes(content));
        }
    }

    /**
     * 列出前缀下对象 key。
     *
     * @param prefix 前缀
     * @return key 列表
     */
    protected List<String> listKeys(String prefix) {
        try (S3Client s3 = s3Client()) {
            return s3.listObjectsV2(builder -> builder.bucket(BUCKET).prefix(prefix)).contents().stream()
                    .map(item -> item.key())
                    .toList();
        }
    }

    /**
     * 删除对象。
     *
     * @param key 对象 key
     */
    protected void deleteObject(String key) {
        try (S3Client s3 = s3Client()) {
            s3.deleteObject(builder -> builder.bucket(BUCKET).key(key));
        }
    }

    /**
     * 生成前缀，避免共享桶内不同用例互相影响。
     *
     * @param name 名称
     * @return 前缀
     */
    protected static String prefix(String name) {
        return "connector/" + name.replaceAll("[^a-zA-Z0-9-]", "-") + "/";
    }

    /**
     * 读取连接器行。
     *
     * @param tenant 租户
     * @param connectorId 连接器 ID
     * @return 行
     */
    protected Map<String, Object> connectorRow(String tenant, String connectorId) {
        return jdbcClient
                .sql("""
                        SELECT id, status, endpoint_id, config, default_visibility, credential_ciphertext,
                               credential_key_id, credential_masked_hint, schedule_enabled, next_sync_at,
                               last_sync_started_at, last_sync_finished_at, last_sync_status, last_sync_complete,
                               last_error_code, last_sync_summary, deleted_at
                          FROM kb_connector
                         WHERE tenant_id = :tenant AND id = :id
                        """)
                .param("tenant", tenant)
                .param("id", com.riccon.api.persistence.BinaryUuids.toBytes(UUID.fromString(connectorId)))
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow();
    }

    /**
     * 部分更新连接器。
     *
     * @param session 管理员会话
     * @param connectorId 连接器 ID
     * @param body JSON 体
     * @return HTTP 响应
     */
    protected ApiTestClient.ApiResponse patchConnector(LoginSession session, String connectorId, String body) {
        return client.patch(
                "/api/v1/knowledge/connectors/" + connectorId, body, session.sessionCookie(), session.csrfToken());
    }

    /**
     * 删除连接器。
     *
     * @param session 管理员会话
     * @param connectorId 连接器 ID
     * @param query 查询串（含 {@code ?} 或空）
     * @return HTTP 响应
     */
    protected ApiTestClient.ApiResponse deleteConnector(LoginSession session, String connectorId, String query) {
        return client.delete(
                "/api/v1/knowledge/connectors/" + connectorId + query, session.sessionCookie(), session.csrfToken());
    }

    /**
     * 插入一个连接器文档，用于删除处置断言。
     *
     * @param tenant 租户
     * @param connectorId 连接器 ID
     * @return 文档 ID
     */
    protected UUID insertConnectorDocument(String tenant, String connectorId) {
        return insertConnectorDocument(tenant, connectorId, "docs/a.pdf", "a".repeat(64));
    }

    /**
     * 插入连接器文档并指定 externalId/checksum。
     *
     * @param tenant 租户
     * @param connectorId 连接器 ID
     * @param externalId 源对象 key
     * @param checksum 内容哈希
     * @return 文档 ID
     */
    protected UUID insertConnectorDocument(String tenant, String connectorId, String externalId, String checksum) {
        UUID documentId = com.riccon.api.common.UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_document (
                            tenant_id, id, title, source_type, file_id, connector_id, external_id, checksum,
                            mime_type, size_bytes, status, created_by, created_at, updated_at, visibility,
                            department_id, parse_engine, acl_version
                        ) VALUES (
                            :tenant, :id, '连接器文档', 'CONNECTOR', NULL, :connectorId, :externalId,
                            :checksum, 'application/pdf', 4, 'PENDING', NULL, NOW(6), NOW(6), 'TENANT',
                            NULL, 'NONE', 1
                        )
                        """)
                .param("tenant", tenant)
                .param("id", com.riccon.api.persistence.BinaryUuids.toBytes(documentId))
                .param("connectorId", com.riccon.api.persistence.BinaryUuids.toBytes(UUID.fromString(connectorId)))
                .param("externalId", externalId)
                .param("checksum", checksum)
                .update();
        return documentId;
    }

    /**
     * 插入一个 UPLOAD 文档（无源文件），用于读路径字段断言。
     *
     * @param tenant 租户
     * @return 文档 ID
     */
    protected UUID insertUploadDocument(String tenant) {
        UUID documentId = com.riccon.api.common.UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_document (
                            tenant_id, id, title, source_type, file_id, connector_id, external_id, checksum,
                            mime_type, size_bytes, status, created_by, created_at, updated_at, visibility,
                            department_id, parse_engine, acl_version
                        ) VALUES (
                            :tenant, :id, '上传文档', 'UPLOAD', NULL, NULL, NULL,
                            :checksum, 'application/pdf', 4, 'PENDING', NULL, NOW(6), NOW(6), 'TENANT',
                            NULL, 'NONE', 1
                        )
                        """)
                .param("tenant", tenant)
                .param("id", com.riccon.api.persistence.BinaryUuids.toBytes(documentId))
                .param("checksum", "b".repeat(64))
                .update();
        return documentId;
    }

    /**
     * 读取文档行。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 行
     */
    protected Map<String, Object> documentRow(String tenant, UUID documentId) {
        return jdbcClient
                .sql("SELECT status, deleted_at FROM knowledge_document WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", tenant)
                .param("id", com.riccon.api.persistence.BinaryUuids.toBytes(documentId))
                .query()
                .singleRow();
    }

    /**
     * 读取指定类型的事件。
     *
     * @param tenant 租户
     * @param eventType 事件类型
     * @return 事件
     */
    protected List<EventRow> events(String tenant, String eventType) {
        return jdbcClient
                .sql("""
                        SELECT event_type, idempotency_key, payload, status
                          FROM agent_run_outbox
                         WHERE tenant_id = :tenant AND event_type = :eventType
                         ORDER BY created_at ASC, id ASC
                        """)
                .param("tenant", tenant)
                .param("eventType", eventType)
                .query((rs, rowNum) -> new EventRow(
                        rs.getString("event_type"),
                        rs.getString("idempotency_key"),
                        rs.getString("payload"),
                        rs.getString("status")))
                .list();
    }

    /**
     * 读取连接器条目行。
     *
     * @param tenant 租户
     * @param connectorId 连接器 ID
     * @return 行
     */
    protected List<Map<String, Object>> itemRows(String tenant, String connectorId) {
        return jdbcClient
                .sql("""
                        SELECT external_id, external_id_hash, status, document_id, generation
                          FROM kb_connector_item
                         WHERE tenant_id = :tenant AND connector_id = :connectorId
                         ORDER BY created_at ASC, id ASC
                        """)
                .param("tenant", tenant)
                .param("connectorId", com.riccon.api.persistence.BinaryUuids.toBytes(UUID.fromString(connectorId)))
                .query()
                .listOfRows();
    }

    /**
     * 写入一个条目（用于 items 分页/展示断言）。
     *
     * @param tenant 租户
     * @param connectorId 连接器 ID
     * @param externalId 源 key
     * @param status 状态
     */
    protected void insertItem(String tenant, String connectorId, String externalId, String status) {
        UUID itemId = com.riccon.api.common.UuidV7.generate();
        String hash = sha256(externalId);
        jdbcClient
                .sql("""
                        INSERT INTO kb_connector_item (
                            tenant_id, id, connector_id, external_id, external_id_hash, external_version,
                            generation, status, last_seen_at, created_at, updated_at
                        ) VALUES (
                            :tenant, :id, :connectorId, :externalId, :hash, 'etag',
                            1, :status, NOW(6), NOW(6), NOW(6)
                        )
                        """)
                .param("tenant", tenant)
                .param("id", com.riccon.api.persistence.BinaryUuids.toBytes(itemId))
                .param("connectorId", com.riccon.api.persistence.BinaryUuids.toBytes(UUID.fromString(connectorId)))
                .param("externalId", externalId)
                .param("hash", hash)
                .param("status", status)
                .update();
    }

    /**
     * 计算 SHA-256。
     *
     * @param value 值
     * @return 十六进制
     */
    protected static String sha256(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 连接器端点允许列表 JSON。
     *
     * @return JSON
     */
    private static String endpointJson() {
        return "[{\"id\":\"" + ENDPOINT_ID + "\",\"url\":\"" + endpoint()
                + "\",\"label\":\"本地 RustFS\",\"allowHttp\":true,\"production\":false},"
                + "{\"id\":\"" + UNREACHABLE_ENDPOINT_ID
                + "\",\"url\":\"http://127.0.0.1:1\",\"label\":\"不可达端点\",\"allowHttp\":true,"
                + "\"production\":false}]";
    }

    /**
     * 对象存储端点。
     *
     * @return 端点 URL
     */
    private static String endpoint() {
        if (ExternalTestServices.enabled()) {
            return ExternalTestServices.storageEndpoint();
        }
        return "http://" + OBJECT_STORAGE.getHost() + ":" + OBJECT_STORAGE.getMappedPort(9000);
    }

    /**
     * 创建测试 S3 客户端。
     *
     * @return 客户端
     */
    private static S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        ExternalTestServices.storageAccessKey(), ExternalTestServices.storageSecretKey())))
                .forcePathStyle(true)
                .build();
    }

    /**
     * 创建测试桶；已存在视为成功。
     *
     * @param bucket 桶名
     */
    private static void createBucket(String bucket) {
        try (S3Client s3 = s3Client()) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException exception) {
            // 共享 RustFS 复用。
        } catch (S3Exception exception) {
            if (exception.statusCode() != 409) {
                throw exception;
            }
        }
    }

    /**
     * 租户夹具。
     *
     * @param tenant 租户代码
     * @param platform 平台管理员
     * @param admin 租户管理员
     * @param member 普通员工
     */
    protected record ConnectorFixture(String tenant, LoginSession platform, LoginSession admin, LoginSession member) {}

    /**
     * 测试凭据。
     *
     * @param accessKeyId 访问键
     * @param secretAccessKey 访问密钥
     */
    protected record Credential(String accessKeyId, String secretAccessKey) {

        /** 返回正确测试凭据。 */
        public static Credential valid() {
            return new Credential(ExternalTestServices.storageAccessKey(), ExternalTestServices.storageSecretKey());
        }

        /** 返回错误测试凭据。 */
        public static Credential invalid() {
            return new Credential(WRONG_ACCESS_KEY, WRONG_SECRET_KEY);
        }
    }

    /**
     * outbox 事件行。
     *
     * @param eventType 事件类型
     * @param idempotencyKey 幂等键
     * @param payload payload JSON
     * @param status 状态
     */
    protected record EventRow(String eventType, String idempotencyKey, String payload, String status) {}

    /**
     * 读取 JSON 并断言状态。
     *
     * @param response 响应
     * @param status 期望状态
     * @return JSON
     */
    protected tools.jackson.databind.JsonNode json(ApiTestClient.ApiResponse response, int status) {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        return client.json(response);
    }
}
