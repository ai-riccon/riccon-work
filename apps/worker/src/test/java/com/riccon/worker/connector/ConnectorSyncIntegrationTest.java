package com.riccon.worker.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.riccon.platform.common.crypto.CredentialEnvelope;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.connector.config.ConnectorProperties;
import com.riccon.worker.connector.domain.ConnectorContentTypes;
import com.riccon.worker.connector.domain.ConnectorErrorCodes;
import com.riccon.worker.connector.service.ConnectorS3ClientFactory;
import com.riccon.worker.connector.service.ConnectorSyncHandler;
import com.riccon.worker.connector.service.ConnectorSyncScheduler;
import com.riccon.worker.connector.service.ConnectorTempFileFactory;
import com.riccon.worker.crypto.service.MasterKeyProvider;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.service.S3FileContentReader;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.support.ExternalTestServices;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 连接器同步行为：首次全量、增量、删除传播、tombstone、限制、重试、租户停用与调度。
 *
 * <p>使用真实 RustFS；每个用例唯一租户 + 唯一前缀隔离。处理器直接调用，不启动 outbox 轮询，避免后台重试
 * 造成不可控等待；outbox/审计结果直接查库断言。
 */
class ConnectorSyncIntegrationTest extends WorkerIntegrationTestBase {

    /** JSON。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 端点 ID。 */
    private static final String ENDPOINT_ID = "local-rustfs";

    /** 凭据用途。 */
    private static final String PURPOSE = "connector-credential";

    /** 源桶；必须与平台 StorageProperties.bucket() 不同。 */
    private static final String SOURCE_BUCKET = BUCKET + "-source";

    /** 同步处理器。 */
    @Autowired
    private ConnectorSyncHandler handler;

    /** 调度器。 */
    @Autowired
    private ConnectorSyncScheduler scheduler;

    /** 配置（用于限制测试临时覆盖）。 */
    @Autowired
    private ConnectorProperties properties;

    /** 主密钥。 */
    @Autowired
    private MasterKeyProvider masterKeyProvider;

    /** 指标。 */
    @Autowired
    private MeterRegistry meterRegistry;

    /** 平台对象读取器，验证 B7b 可按平台桶读取连接器对象。 */
    @Autowired
    private S3FileContentReader contentReader;

    /** 源客户端工厂 spy，用于模拟截断/同步中删除。 */
    @MockitoSpyBean
    private ConnectorS3ClientFactory clientFactory;

    /** 临时文件工厂 spy，用于断言失败清理。 */
    @MockitoSpyBean
    private ConnectorTempFileFactory tempFileFactory;

    /**
     * 注入连接器配置。
     *
     * @param registry 动态属性
     */
    @DynamicPropertySource
    static void connectorProperties(DynamicPropertyRegistry registry) {
        registry.add("riccon.connector.enabled", () -> "true");
        registry.add("riccon.connector.endpoints", ConnectorSyncIntegrationTest::endpointJson);
        registry.add("riccon.connector.scheduler-enabled", () -> "true");
        registry.add("riccon.connector.scheduler-initial-delay-ms", () -> "3600000");
        registry.add("riccon.connector.max-object-bytes", () -> "10485760");
        registry.add("riccon.connector.sync-interval-minutes", () -> "60");
        registry.add("riccon.connector.path-style", () -> "true");
        registry.add("riccon.connector.default-region", () -> "us-east-1");
    }

    /** 每个测试确保源桶存在；源桶与平台桶分离。 */
    @org.junit.jupiter.api.BeforeEach
    void ensureSourceBucket() {
        try {
            s3Client.createBucket(builder -> builder.bucket(SOURCE_BUCKET));
        } catch (S3Exception exception) {
            if (exception.statusCode() != 409) {
                throw exception;
            }
        }
    }

    @Test
    void firstSync_createsDocumentsSkipsUnsupportedAndExcluded() {
        String tenant = uniqueTenant("conn");
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        String prefix = uniquePrefix("first");
        UUID connector = createConnector(tenant, user, prefix, List.of("pdf", "txt"), List.of("**/skip/**"), true);
        put(prefix + "a.pdf", "pdf-a");
        put(prefix + "b.txt", "txt-b");
        put(prefix + "c.zip", "zip-c");
        put(prefix + "skip/d.pdf", "skip-d");

        sync(tenant, connector, 0);

        Map<String, Object> state = connectorState(tenant, connector);
        assertThat(state.get("last_sync_status")).isEqualTo("SUCCESS");
        assertThat(booleanValue(state.get("last_sync_complete"))).isEqualTo(1);
        assertThat(state.get("last_sync_started_at")).isNotNull();
        assertThat(state.get("last_sync_finished_at")).isNotNull();
        assertThat(state.get("last_error_code")).isNull();
        JsonNode summary = JSON.readTree((String) state.get("last_sync_summary"));
        assertThat(summary.path("scanned").asLong()).isEqualTo(4);
        assertThat(summary.path("created").asLong()).isEqualTo(2);
        assertThat(summary.path("skipped").asLong()).isEqualTo(2);

        List<Map<String, Object>> documents = documents(tenant, connector);
        assertThat(documents).hasSize(2);
        for (Map<String, Object> document : documents) {
            assertThat(document.get("source_type")).isEqualTo("CONNECTOR");
            assertThat(document.get("file_id")).isNull();
            assertThat(document.get("visibility")).isEqualTo("TENANT");
            assertThat(document.get("status")).isEqualTo("PENDING");
        }
        assertThat(documents.stream().map(row -> row.get("title")).toList())
                .containsExactlyInAnyOrder("a.pdf", "b.txt");
        for (Map<String, Object> document : documents) {
            UUID documentId = com.riccon.worker.persistence.UuidBinary.fromBytes((byte[]) document.get("id"));
            Instant createdAt = ((LocalDateTime) document.get("created_at")).toInstant(ZoneOffset.UTC);
            String objectKey = ConnectorContentTypes.objectKey(tenant, documentId, createdAt);
            byte[] stored = contentReader.read(
                    TenantId.of(tenant),
                    new FileRecord(
                            documentId,
                            (String) document.get("title"),
                            FileFormat.PDF,
                            (String) document.get("mime_type"),
                            ((Number) document.get("size_bytes")).longValue(),
                            objectKey));
            assertThat(new String(stored, StandardCharsets.UTF_8)).isIn("pdf-a", "txt-b");
        }
        assertThat(s3Client.listObjectsV2(
                                builder -> builder.bucket(SOURCE_BUCKET).prefix("tenants/"))
                        .contents())
                .isEmpty();

        List<Map<String, Object>> items = items(tenant, connector);
        assertThat(items).hasSize(4);
        assertThat(items.stream()
                        .filter(row -> "SYNCED".equals(row.get("status")))
                        .count())
                .isEqualTo(2);
        assertThat(items.stream()
                        .filter(row -> "SKIPPED".equals(row.get("status")))
                        .count())
                .isEqualTo(2);

        List<Map<String, Object>> indexEvents = events(tenant, EventTypes.KNOWLEDGE_INDEX_REQUESTED);
        assertThat(indexEvents).hasSize(2);
        for (Map<String, Object> event : indexEvents) {
            JsonNode payload = JSON.readTree((String) event.get("payload"));
            assertThat(payload.path("mode").asText()).isEqualTo("FULL");
            assertThat(payload.toString()).doesNotContain(prefix).doesNotContain("a.pdf");
        }
    }

    @Test
    void firstSyncHundredObjects_createsHundredDocuments() {
        String tenant = uniqueTenant("conn");
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        String prefix = uniquePrefix("hundred");
        UUID connector = createConnector(tenant, user, prefix, List.of(), List.of(), false);
        for (int index = 0; index < 100; index++) {
            put(prefix + String.format("doc-%03d.pdf", index), "content-" + index);
        }

        sync(tenant, connector, 0);

        assertThat(documents(tenant, connector)).hasSize(100);
        assertThat(items(tenant, connector)).hasSize(100);
        assertThat(events(tenant, EventTypes.KNOWLEDGE_INDEX_REQUESTED)).hasSize(100);
        JsonNode summary =
                JSON.readTree((String) connectorState(tenant, connector).get("last_sync_summary"));
        assertThat(summary.path("scanned").asLong()).isEqualTo(100);
        assertThat(summary.path("created").asLong()).isEqualTo(100);
        assertThat(summary.path("complete").asBoolean()).isTrue();
    }

    @Test
    void incrementalAdd_createsOnlyNewDocument() {
        String tenant = uniqueTenant("conn");
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        String prefix = uniquePrefix("add");
        UUID connector = createConnector(tenant, user, prefix, List.of(), List.of(), false);
        put(prefix + "a.pdf", "a");
        sync(tenant, connector, 0);
        put(prefix + "b.pdf", "b");
        sync(tenant, connector, 0);
        assertThat(documents(tenant, connector)).hasSize(2);
        JsonNode summary =
                JSON.readTree((String) connectorState(tenant, connector).get("last_sync_summary"));
        assertThat(summary.path("created").asLong()).isEqualTo(1);
        assertThat(summary.path("updated").asLong()).isZero();
    }

    @Test
    void repeatSync_updatesLastSeenWithoutDuplicateDocumentsOrEvents() {
        String tenant = uniqueTenant("conn");
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        String prefix = uniquePrefix("repeat");
        UUID connector = createConnector(tenant, user, prefix, List.of(), List.of(), false);
        put(prefix + "a.pdf", "pdf-a");
        sync(tenant, connector, 0);
        List<Map<String, Object>> documentsBefore = documents(tenant, connector);
        int eventsBefore = events(tenant, EventTypes.KNOWLEDGE_INDEX_REQUESTED).size();
        jdbcClient
                .sql("""
                        UPDATE kb_connector_item
                           SET last_seen_at = '2020-01-01 00:00:00'
                         WHERE tenant_id = :tenant AND connector_id = :connector
                        """)
                .param("tenant", tenant)
                .param("connector", com.riccon.worker.persistence.UuidBinary.toBytes(connector))
                .update();

        sync(tenant, connector, 0);

        assertThat(documents(tenant, connector)).hasSameSizeAs(documentsBefore);
        assertThat(events(tenant, EventTypes.KNOWLEDGE_INDEX_REQUESTED)).hasSize(eventsBefore);
        JsonNode repeatedSummary =
                JSON.readTree((String) connectorState(tenant, connector).get("last_sync_summary"));
        assertThat(repeatedSummary.path("created").asLong()).isZero();
        assertThat(repeatedSummary.path("updated").asLong()).isZero();
        assertThat(repeatedSummary.path("failed").asLong()).isZero();
        List<Map<String, Object>> items = items(tenant, connector);
        assertThat(items).hasSize(1);
        assertThat((java.time.LocalDateTime) items.get(0).get("last_seen_at"))
                .isAfter(java.time.LocalDateTime.of(2021, 1, 1, 0, 0));
    }

    @Test
    void incrementalChange_updatesSameDocumentAndEnqueuesReindex() {
        String tenant = uniqueTenant("conn");
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        String prefix = uniquePrefix("incr");
        UUID connector = createConnector(tenant, user, prefix, List.of(), List.of(), false);
        put(prefix + "a.pdf", "version-one");
        sync(tenant, connector, 0);
        Map<String, Object> before = documents(tenant, connector).get(0);
        UUID documentId = com.riccon.worker.persistence.UuidBinary.fromBytes((byte[]) before.get("id"));

        put(prefix + "a.pdf", "version-two-longer");
        sync(tenant, connector, 0);

        List<Map<String, Object>> documents = documents(tenant, connector);
        assertThat(documents).hasSize(1);
        assertThat(com.riccon.worker.persistence.UuidBinary.fromBytes(
                        (byte[]) documents.get(0).get("id")))
                .isEqualTo(documentId);
        assertThat(documents.get(0).get("checksum")).isNotEqualTo(before.get("checksum"));
        assertThat(documents.get(0).get("status")).isEqualTo("PENDING");
        assertThat(events(tenant, EventTypes.KNOWLEDGE_INDEX_REQUESTED)).hasSize(2);
        JsonNode summary =
                JSON.readTree((String) connectorState(tenant, connector).get("last_sync_summary"));
        assertThat(summary.path("updated").asLong()).isEqualTo(1);
        assertThat(summary.path("created").asLong()).isZero();
    }

    @Test
    void sourceDeletion_softDeletesDocumentAndEnqueuesDeleteOnlyWhenComplete() {
        String tenant = uniqueTenant("conn");
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        String prefix = uniquePrefix("delete");
        UUID connector = createConnector(tenant, user, prefix, List.of(), List.of(), false);
        put(prefix + "a.pdf", "a");
        put(prefix + "b.pdf", "b");
        sync(tenant, connector, 0);

        delete(prefix + "a.pdf");
        sync(tenant, connector, 0);

        List<Map<String, Object>> items = items(tenant, connector);
        assertThat(items.stream()
                        .filter(row -> "DELETED".equals(row.get("status")))
                        .count())
                .isEqualTo(1);
        List<Map<String, Object>> documents = documents(tenant, connector);
        assertThat(documents.stream()
                        .filter(row -> row.get("deleted_at") != null)
                        .count())
                .isEqualTo(1);
        List<Map<String, Object>> deleteEvents = events(tenant, EventTypes.KNOWLEDGE_INDEX_REQUESTED).stream()
                .filter(event -> JSON.readTree((String) event.get("payload"))
                        .path("mode")
                        .asText()
                        .equals("DELETE"))
                .toList();
        assertThat(deleteEvents).hasSize(1);
        assertThat(auditCount(tenant, "knowledge.document.deleted")).isEqualTo(1);
    }

    @Test
    void incompleteList_doesNotPropagateDeletion() {
        String tenant = uniqueTenant("conn");
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        String prefix = uniquePrefix("partial-list");
        UUID connector = createConnector(tenant, user, prefix, List.of(), List.of(), false);
        put(prefix + "a.pdf", "a");
        put(prefix + "b.pdf", "b");
        put(prefix + "c.pdf", "c");
        put(prefix + "d.pdf", "d");
        sync(tenant, connector, 0);
        assertThat(items(tenant, connector)).hasSize(4);

        delete(prefix + "a.pdf");
        ReflectionTestUtils.setField(properties, "maxObjects", 2);
        try {
            sync(tenant, connector, 0);
        } finally {
            ReflectionTestUtils.setField(properties, "maxObjects", 5_000);
        }

        Map<String, Object> state = connectorState(tenant, connector);
        assertThat(state.get("last_sync_status")).isEqualTo("PARTIAL");
        assertThat(booleanValue(state.get("last_sync_complete"))).isZero();
        assertThat(state.get("last_error_code")).isEqualTo(ConnectorErrorCodes.OBJECT_LIMIT);
        // a 与 d 都未见，但 list 不完整时不得删除任何项。
        List<Map<String, Object>> items = items(tenant, connector);
        assertThat(items.stream()
                        .filter(row -> "DELETED".equals(row.get("status")))
                        .count())
                .isZero();
        assertThat(documents(tenant, connector).stream()
                        .filter(row -> row.get("deleted_at") != null)
                        .count())
                .isZero();

        sync(tenant, connector, 0);
        assertThat(items(tenant, connector).stream()
                        .filter(row -> "DELETED".equals(row.get("status")))
                        .count())
                .isEqualTo(1);
    }

    @Test
    void sourceReappears_reusesTombstoneAndIncrementsGeneration() {
        String tenant = uniqueTenant("conn");
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        String prefix = uniquePrefix("tombstone");
        UUID connector = createConnector(tenant, user, prefix, List.of(), List.of(), false);
        put(prefix + "a.pdf", "first");
        sync(tenant, connector, 0);
        UUID firstDocument = com.riccon.worker.persistence.UuidBinary.fromBytes(
                (byte[]) documents(tenant, connector).get(0).get("id"));
        UUID itemId = com.riccon.worker.persistence.UuidBinary.fromBytes(
                (byte[]) items(tenant, connector).get(0).get("id"));

        delete(prefix + "a.pdf");
        sync(tenant, connector, 0);
        assertThat(items(tenant, connector).get(0).get("status")).isEqualTo("DELETED");

        put(prefix + "a.pdf", "second");
        sync(tenant, connector, 0);

        List<Map<String, Object>> items = items(tenant, connector);
        assertThat(items).hasSize(1);
        assertThat(com.riccon.worker.persistence.UuidBinary.fromBytes(
                        (byte[]) items.get(0).get("id")))
                .isEqualTo(itemId);
        assertThat(((Number) items.get(0).get("generation")).intValue()).isEqualTo(2);
        assertThat(items.get(0).get("status")).isEqualTo("SYNCED");
        UUID secondDocument = com.riccon.worker.persistence.UuidBinary.fromBytes(
                (byte[]) items.get(0).get("document_id"));
        assertThat(secondDocument).isNotEqualTo(firstDocument);
        assertThat(documents(tenant, connector).stream()
                        .filter(row -> com.riccon.worker.persistence.UuidBinary.fromBytes((byte[]) row.get("id"))
                                .equals(firstDocument))
                        .findFirst()
                        .orElseThrow()
                        .get("deleted_at"))
                .isNotNull();
    }

    @Test
    void objectTooLarge_marksFailedAndPartial() {
        String tenant = uniqueTenant("conn");
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        String prefix = uniquePrefix("size");
        UUID connector = createConnector(tenant, user, prefix, List.of(), List.of(), false);
        put(prefix + "small.pdf", "1234");
        put(prefix + "big.pdf", "x".repeat(64));
        ReflectionTestUtils.setField(properties, "maxObjectBytes", 8L);
        try {
            sync(tenant, connector, 0);
        } finally {
            ReflectionTestUtils.setField(properties, "maxObjectBytes", 10_485_760L);
        }
        Map<String, Object> state = connectorState(tenant, connector);
        assertThat(state.get("last_sync_status")).isEqualTo("PARTIAL");
        assertThat(booleanValue(state.get("last_sync_complete"))).isEqualTo(1);
        List<Map<String, Object>> items = items(tenant, connector);
        assertThat(items.stream()
                        .filter(row -> "FAILED".equals(row.get("status")))
                        .count())
                .isEqualTo(1);
        assertThat(items.stream()
                        .filter(row -> "FAILED".equals(row.get("status")))
                        .findFirst()
                        .orElseThrow()
                        .get("error_code"))
                .isEqualTo(ConnectorErrorCodes.OBJECT_TOO_LARGE);
        assertThat(items.stream()
                        .filter(row -> "SYNCED".equals(row.get("status")))
                        .count())
                .isEqualTo(1);

        // 恢复上限后只重试失败项：大对象新建，小对象未变化。
        sync(tenant, connector, 0);
        assertThat(documents(tenant, connector)).hasSize(2);
        JsonNode summary =
                JSON.readTree((String) connectorState(tenant, connector).get("last_sync_summary"));
        assertThat(summary.path("created").asLong()).isEqualTo(1);
        assertThat(summary.path("updated").asLong()).isZero();
    }

    @Test
    void authFailure_marksErrorSchedulesRetryAndRecordsMetric() {
        String tenant = uniqueTenant("conn");
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        String prefix = uniquePrefix("auth");
        UUID connector =
                createConnector(tenant, user, prefix, List.of(), List.of(), false, "wrong-access", "wrong-secret");
        put(prefix + "a.pdf", "a");

        sync(tenant, connector, 0);

        Map<String, Object> state = connectorState(tenant, connector);
        assertThat(state.get("last_sync_status")).isEqualTo("ERROR");
        assertThat(state.get("last_error_code")).isEqualTo(ConnectorErrorCodes.AUTH);
        assertThat(state.get("last_sync_finished_at")).isNotNull();
        assertThat(auditCount(tenant, "knowledge.connector.sync.failed")).isEqualTo(1);
        assertThat(auditCount(tenant, "knowledge.connector.sync.retried")).isEqualTo(1);
        List<Map<String, Object>> syncEvents = events(tenant, EventTypes.KNOWLEDGE_CONNECTOR_SYNC_REQUESTED);
        assertThat(syncEvents).hasSize(1);
        assertThat((String) syncEvents.get(0).get("idempotency_key")).endsWith(":retry:1");
        assertThat(((java.time.LocalDateTime) syncEvents.get(0).get("available_at")))
                .isAfter(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(2));
        double authFailures =
                meterRegistry.find("riccon_connector_sync_failures_total").tag("reason", "auth").counters().stream()
                        .mapToDouble(counter -> counter.count())
                        .sum();
        assertThat(authFailures).isGreaterThanOrEqualTo(1.0);

        sync(tenant, connector, 1);
        sync(tenant, connector, 2);
        sync(tenant, connector, 3);
        assertThat(events(tenant, EventTypes.KNOWLEDGE_CONNECTOR_SYNC_REQUESTED))
                .hasSize(3);
        assertThat(connectorState(tenant, connector).get("last_error_code")).isEqualTo(ConnectorErrorCodes.AUTH);
    }

    @Test
    void tenantDisabled_skipsWithoutChangingSyncState() {
        String tenant = uniqueTenant("conn");
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        String prefix = uniquePrefix("disabled");
        UUID connector = createConnector(tenant, user, prefix, List.of(), List.of(), false);
        put(prefix + "a.pdf", "a");
        jdbcClient
                .sql("UPDATE tenant SET status='SUSPENDED' WHERE id=:tenant")
                .param("tenant", tenant)
                .update();

        sync(tenant, connector, 0);

        Map<String, Object> state = connectorState(tenant, connector);
        assertThat(state.get("last_sync_started_at")).isNull();
        assertThat(state.get("last_sync_status")).isNull();
        assertThat(auditCount(tenant, "knowledge.connector.sync.started")).isZero();
        assertThat(events(tenant, EventTypes.KNOWLEDGE_CONNECTOR_SYNC_REQUESTED))
                .isEmpty();
    }

    @Test
    void missingCredential_marksCredentialErrorWithoutRetry() {
        String tenant = uniqueTenant("conn");
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        String prefix = uniquePrefix("no-cred");
        UUID connector = createConnector(tenant, user, prefix, List.of(), List.of(), false, null, null);
        sync(tenant, connector, 0);
        Map<String, Object> state = connectorState(tenant, connector);
        assertThat(state.get("last_sync_status")).isEqualTo("ERROR");
        assertThat(state.get("last_error_code")).isEqualTo(ConnectorErrorCodes.CREDENTIAL);
        assertThat(events(tenant, EventTypes.KNOWLEDGE_CONNECTOR_SYNC_REQUESTED))
                .isEmpty();
    }

    @Test
    void scheduleDue_claimsConnectorAndEnqueuesScheduleSyncOnce() {
        String tenant = uniqueTenant("conn");
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        String prefix = uniquePrefix("schedule");
        UUID connector = createConnector(tenant, user, prefix, List.of(), List.of(), true);
        jdbcClient
                .sql("""
                        UPDATE kb_connector
                           SET status='ACTIVE', schedule_enabled=TRUE, next_sync_at=:due
                         WHERE tenant_id=:tenant AND id=:id
                        """)
                .param(
                        "due",
                        java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1))
                .param("tenant", tenant)
                .param("id", com.riccon.worker.persistence.UuidBinary.toBytes(connector))
                .update();

        scheduler.scheduleDue();
        List<Map<String, Object>> syncEvents = events(tenant, EventTypes.KNOWLEDGE_CONNECTOR_SYNC_REQUESTED);
        assertThat(syncEvents).hasSize(1);
        JsonNode payload = JSON.readTree((String) syncEvents.get(0).get("payload"));
        assertThat(payload.path("connectorId").asText()).isEqualTo(connector.toString());
        assertThat(payload.path("syncId").asText()).isNotBlank();
        assertThat(payload.path("startedAt").asText()).isNotBlank();
        assertThat(((java.time.LocalDateTime) connectorState(tenant, connector).get("next_sync_at")))
                .isAfter(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        assertThat(auditCount(tenant, "knowledge.connector.sync.started")).isEqualTo(1);

        scheduler.scheduleDue();
        assertThat(events(tenant, EventTypes.KNOWLEDGE_CONNECTOR_SYNC_REQUESTED))
                .hasSize(1);
    }

    @Test
    void truncatedWithoutToken_marksIncompleteAndDoesNotDelete() {
        String tenant = uniqueTenant("conn");
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        String prefix = uniquePrefix("truncated");
        UUID connector = createConnector(tenant, user, prefix, List.of(), List.of(), false);
        put(prefix + "a.pdf", "a");
        put(prefix + "b.pdf", "b");
        put(prefix + "c.pdf", "c");
        sync(tenant, connector, 0);
        assertThat(documents(tenant, connector)).hasSize(3);

        S3Client sourceClient = mock(S3Client.class);
        when(sourceClient.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(S3Object.builder()
                                .key(prefix + "a.zip")
                                .eTag("e")
                                .size(1L)
                                .build())
                        .isTruncated(true)
                        .build());
        doReturn(sourceClient).when(clientFactory).build(any(), any(), any());
        try {
            sync(tenant, connector, 0);
        } finally {
            reset(clientFactory);
        }

        Map<String, Object> state = connectorState(tenant, connector);
        assertThat(state.get("last_sync_status")).isEqualTo("PARTIAL");
        assertThat(booleanValue(state.get("last_sync_complete"))).isZero();
        assertThat(state.get("last_error_code")).isEqualTo(ConnectorErrorCodes.TRUNCATED);
        assertThat(documents(tenant, connector).stream()
                        .filter(row -> row.get("deleted_at") != null)
                        .count())
                .isZero();
        assertThat(items(tenant, connector).stream()
                        .filter(row -> "DELETED".equals(row.get("status")))
                        .count())
                .isZero();
    }

    @Test
    void connectorDeletedDuringSync_stopsBeforeCreatingMoreDocuments() {
        String tenant = uniqueTenant("conn");
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        String prefix = uniquePrefix("deleted-mid");
        UUID connector = createConnector(tenant, user, prefix, List.of(), List.of(), false);
        put(prefix + "a.pdf", "a");

        S3Client sourceClient = mock(S3Client.class);
        when(sourceClient.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(invocation -> {
            jdbcClient
                    .sql("UPDATE kb_connector SET deleted_at=NOW(6) WHERE tenant_id=:tenant AND id=:id")
                    .param("tenant", tenant)
                    .param("id", com.riccon.worker.persistence.UuidBinary.toBytes(connector))
                    .update();
            return ListObjectsV2Response.builder()
                    .contents(S3Object.builder()
                            .key(prefix + "a.pdf")
                            .eTag("e")
                            .size(1L)
                            .build())
                    .isTruncated(false)
                    .build();
        });
        doReturn(sourceClient).when(clientFactory).build(any(), any(), any());
        try {
            sync(tenant, connector, 0);
        } finally {
            reset(clientFactory);
        }

        assertThat(documents(tenant, connector)).isEmpty();
        Map<String, Object> state = connectorState(tenant, connector);
        assertThat(state.get("last_sync_started_at")).isNotNull();
        assertThat(state.get("last_sync_status")).isNull();
    }

    @Test
    void totalBytesLimit_cleansTempFile() throws Exception {
        String tenant = uniqueTenant("conn");
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        String prefix = uniquePrefix("temp-clean");
        UUID connector = createConnector(tenant, user, prefix, List.of(), List.of(), false);
        put(prefix + "a.pdf", "1234");
        Path temp = Files.createTempFile("connector-temp-test-", ".tmp");
        doReturn(temp).when(tempFileFactory).create();
        ReflectionTestUtils.setField(properties, "maxTotalBytes", 1L);
        try {
            sync(tenant, connector, 0);
        } finally {
            reset(tempFileFactory);
            ReflectionTestUtils.setField(properties, "maxTotalBytes", 2L * 1024 * 1024 * 1024);
        }
        assertThat(Files.exists(temp)).isFalse();
        assertThat(documents(tenant, connector)).isEmpty();
        assertThat(connectorState(tenant, connector).get("last_error_code"))
                .isEqualTo(ConnectorErrorCodes.TOTAL_BYTES_LIMIT);
    }

    /**
     * 创建连接器并写入信封加密凭据。
     */
    private UUID createConnector(
            String tenant,
            UUID user,
            String prefix,
            List<String> includeExtensions,
            List<String> excludeGlobs,
            boolean scheduleEnabled) {
        return createConnector(
                tenant,
                user,
                prefix,
                includeExtensions,
                excludeGlobs,
                scheduleEnabled,
                ExternalTestServices.storageAccessKey(),
                ExternalTestServices.storageSecretKey());
    }

    /**
     * 创建连接器；凭据可为空。
     */
    private UUID createConnector(
            String tenant,
            UUID user,
            String prefix,
            List<String> includeExtensions,
            List<String> excludeGlobs,
            boolean scheduleEnabled,
            String accessKeyId,
            String secretAccessKey) {
        UUID connectorId = com.riccon.worker.persistence.UuidBinary.newV7();
        ObjectNode config = JSON.createObjectNode();
        config.put("bucket", SOURCE_BUCKET);
        config.put("prefix", prefix);
        config.put("region", "us-east-1");
        if (!includeExtensions.isEmpty()) {
            var extensions = config.putArray("includeExtensions");
            includeExtensions.forEach(extensions::add);
        }
        if (!excludeGlobs.isEmpty()) {
            var globs = config.putArray("excludeGlobs");
            excludeGlobs.forEach(globs::add);
        }
        byte[] ciphertext = null;
        String keyId = null;
        if (accessKeyId != null) {
            byte[] dek = new byte[32];
            new java.security.SecureRandom().nextBytes(dek);
            keyId = "cn-" + UUID.randomUUID().toString().replace("-", "");
            byte[] aad = CredentialEnvelope.aad(tenant, PURPOSE, keyId);
            var wrapped = masterKeyProvider.wrap(dek, aad);
            jdbcClient
                    .sql("""
                            INSERT INTO tenant_data_key
                                (tenant_id, key_id, purpose, wrapped_dek, wrap_nonce, algorithm, master_key_id)
                            VALUES (:tenant, :keyId, :purpose, :wrapped, :nonce, 'AES-256-GCM', :masterKeyId)
                            """)
                    .param("tenant", tenant)
                    .param("keyId", keyId)
                    .param("purpose", PURPOSE)
                    .param("wrapped", wrapped.wrappedDek())
                    .param("nonce", wrapped.nonce())
                    .param("masterKeyId", masterKeyProvider.masterKeyId())
                    .update();
            try {
                String plaintext =
                        "{\"accessKeyId\":\"" + accessKeyId + "\",\"secretAccessKey\":\"" + secretAccessKey + "\"}";
                ciphertext = CredentialEnvelope.encrypt(
                                tenant, PURPOSE, keyId, dek, plaintext.getBytes(StandardCharsets.UTF_8))
                        .getBytes(StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(dek, (byte) 0);
            }
        }
        jdbcClient
                .sql("""
                        INSERT INTO kb_connector (
                            tenant_id, id, name, type, status, endpoint_id, config, default_visibility,
                            default_department_id, credential_ciphertext, credential_key_id,
                            credential_masked_hint, schedule_enabled, next_sync_at, created_by,
                            created_at, updated_at
                        ) VALUES (
                            :tenant, :id, :name, 'S3', 'PAUSED', :endpointId, :config, 'TENANT',
                            NULL, :ciphertext, :keyId, '****test', :schedule, NULL, :createdBy,
                            NOW(6), NOW(6)
                        )
                        """)
                .param("tenant", tenant)
                .param("id", com.riccon.worker.persistence.UuidBinary.toBytes(connectorId))
                .param("name", "conn-" + connectorId.toString().substring(0, 8))
                .param("endpointId", ENDPOINT_ID)
                .param("config", JSON.writeValueAsString(config))
                .param("ciphertext", ciphertext)
                .param("keyId", keyId)
                .param("schedule", scheduleEnabled)
                .param("createdBy", com.riccon.worker.persistence.UuidBinary.toBytes(user))
                .update();
        return connectorId;
    }

    /**
     * 构造并处理同步事件。
     */
    private void sync(String tenant, UUID connectorId, int retryAttempt) {
        UUID syncId = com.riccon.worker.persistence.UuidBinary.newV7();
        String key =
                "connector:" + connectorId + ":sync:" + syncId + (retryAttempt > 0 ? ":retry:" + retryAttempt : "");
        ObjectNode payload = JSON.createObjectNode();
        payload.put("connectorId", connectorId.toString());
        payload.put("syncId", syncId.toString());
        payload.put("startedAt", Instant.now().toString());
        payload.put("trigger", retryAttempt > 0 ? "RETRY" : "MANUAL");
        Instant now = Instant.now();
        ClaimedOutboxEvent event = new ClaimedOutboxEvent(
                com.riccon.worker.persistence.UuidBinary.newV7(),
                tenant,
                com.riccon.worker.persistence.UuidBinary.newV7(),
                EventTypes.KNOWLEDGE_CONNECTOR_SYNC_REQUESTED,
                1,
                key,
                JSON.writeValueAsString(payload),
                1,
                now,
                now.plus(Duration.ofSeconds(90)));
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(tenant))) {
            handler.handle(event);
        }
    }

    /** 写入对象。 */
    private void put(String key, String content) {
        s3Client.putObject(
                builder -> builder.bucket(SOURCE_BUCKET).key(key).contentType("application/pdf"),
                RequestBody.fromBytes(content.getBytes(StandardCharsets.UTF_8)));
    }

    /** 删除对象。 */
    private void delete(String key) {
        s3Client.deleteObject(builder -> builder.bucket(SOURCE_BUCKET).key(key));
    }

    /** 读取连接器状态。 */
    private Map<String, Object> connectorState(String tenant, UUID connectorId) {
        return jdbcClient
                .sql("""
                        SELECT last_sync_started_at, last_sync_finished_at, last_sync_status, last_sync_complete,
                               last_error_code, last_sync_summary, next_sync_at, status
                          FROM kb_connector
                         WHERE tenant_id=:tenant AND id=:id
                        """)
                .param("tenant", tenant)
                .param("id", com.riccon.worker.persistence.UuidBinary.toBytes(connectorId))
                .query()
                .singleRow();
    }

    /** 读取文档。 */
    private List<Map<String, Object>> documents(String tenant, UUID connectorId) {
        return jdbcClient
                .sql("""
                        SELECT id, title, source_type, file_id, connector_id, checksum, mime_type, size_bytes,
                               status, visibility, created_at, deleted_at
                          FROM knowledge_document
                         WHERE tenant_id=:tenant AND connector_id=:connector
                         ORDER BY created_at ASC, id ASC
                        """)
                .param("tenant", tenant)
                .param("connector", com.riccon.worker.persistence.UuidBinary.toBytes(connectorId))
                .query()
                .listOfRows();
    }

    /** 读取条目。 */
    private List<Map<String, Object>> items(String tenant, UUID connectorId) {
        return jdbcClient
                .sql("""
                        SELECT id, external_id, external_version, checksum, document_id, generation, status,
                               error_code, last_seen_at, deleted_at
                          FROM kb_connector_item
                         WHERE tenant_id=:tenant AND connector_id=:connector
                         ORDER BY created_at ASC, id ASC
                        """)
                .param("tenant", tenant)
                .param("connector", com.riccon.worker.persistence.UuidBinary.toBytes(connectorId))
                .query()
                .listOfRows();
    }

    /** 读取事件。 */
    private List<Map<String, Object>> events(String tenant, String eventType) {
        return jdbcClient
                .sql("""
                        SELECT event_type, idempotency_key, payload, available_at, status
                          FROM agent_run_outbox
                         WHERE tenant_id=:tenant AND event_type=:eventType
                         ORDER BY created_at ASC, id ASC
                        """)
                .param("tenant", tenant)
                .param("eventType", eventType)
                .query()
                .listOfRows();
    }

    /** 统计审计。 */
    private long auditCount(String tenant, String action) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM audit_event WHERE tenant_id=:tenant AND action=:action")
                .param("tenant", tenant)
                .param("action", action)
                .query(Long.class)
                .single();
    }

    /** 读取数据库布尔值（MySQL 可能返回 Boolean 或数字）。 */
    private static int booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** 唯一前缀。 */
    private static String uniquePrefix(String label) {
        return "connector-tests/" + label + "-" + UUID.randomUUID().toString().substring(0, 8) + "/";
    }

    /** 端点 JSON。 */
    private static String endpointJson() {
        return "[{\"id\":\"" + ENDPOINT_ID + "\",\"url\":\"" + storageEndpoint()
                + "\",\"label\":\"本地 RustFS\",\"allowHttp\":true,\"production\":false}]";
    }
}
