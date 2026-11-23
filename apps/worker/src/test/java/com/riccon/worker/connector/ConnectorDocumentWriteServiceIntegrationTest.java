package com.riccon.worker.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.connector.service.ConnectorDocumentWriteService;
import com.riccon.worker.connector.service.ConnectorEventPublisher;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

/**
 * 文档+条目+索引事件短事务：事件投递失败回滚；下一次重试可补投事件。
 */
class ConnectorDocumentWriteServiceIntegrationTest extends WorkerIntegrationTestBase {

    /** JSON。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 事件投递 spy。 */
    @MockitoSpyBean
    private ConnectorEventPublisher eventPublisher;

    /** 文档写入服务。 */
    @Autowired
    private ConnectorDocumentWriteService writeService;

    @Test
    void createDocument_eventFailureRollsBackAndRetryPublishes() {
        String tenant = uniqueTenant("conn-write");
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        UUID connectorId = com.riccon.worker.persistence.UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO kb_connector (
                            tenant_id, id, name, type, status, endpoint_id, config, default_visibility,
                            schedule_enabled, created_by, created_at, updated_at
                        ) VALUES (
                            :tenant, :id, :name, 'S3', 'PAUSED', 'local-rustfs', :config,
                            'TENANT', FALSE, :createdBy, NOW(6), NOW(6)
                        )
                        """)
                .param("tenant", tenant)
                .param("id", com.riccon.worker.persistence.UuidBinary.toBytes(connectorId))
                .param("name", "write-" + connectorId.toString().substring(0, 8))
                .param("config", JSON.writeValueAsString(Map.of("bucket", BUCKET, "prefix", "x/")))
                .param("createdBy", com.riccon.worker.persistence.UuidBinary.toBytes(user))
                .update();
        UUID itemId = com.riccon.worker.persistence.UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO kb_connector_item (
                            tenant_id, id, connector_id, external_id, external_id_hash, external_version,
                            generation, status, last_seen_at, created_at, updated_at
                        ) VALUES (
                            :tenant, :id, :connectorId, 'a.pdf', :hash, 'etag',
                            1, 'PENDING', NOW(6), NOW(6), NOW(6)
                        )
                        """)
                .param("tenant", tenant)
                .param("id", com.riccon.worker.persistence.UuidBinary.toBytes(itemId))
                .param("connectorId", com.riccon.worker.persistence.UuidBinary.toBytes(connectorId))
                .param("hash", sha256("a.pdf"))
                .update();

        doThrow(new IllegalStateException("event unavailable"))
                .doCallRealMethod()
                .when(eventPublisher)
                .enqueueIndex(any(), any(), anyInt(), any(), any(), any());
        Instant now = Instant.now();
        UUID firstDocument = com.riccon.worker.persistence.UuidBinary.newV7();
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(tenant))) {
            assertThatThrownBy(() -> writeService.createDocument(
                            TenantId.of(tenant),
                            itemId,
                            firstDocument,
                            "a.pdf",
                            connectorId,
                            "a.pdf",
                            "a".repeat(64),
                            "application/pdf",
                            1,
                            KnowledgeVisibility.TENANT,
                            null,
                            user,
                            now.plusSeconds(180L * 24 * 3600),
                            "etag",
                            user,
                            "trace",
                            now))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(documentCount(tenant)).isZero();
        assertThat(itemStatus(tenant, itemId)).isEqualTo("PENDING");

        UUID secondDocument = com.riccon.worker.persistence.UuidBinary.newV7();
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(tenant))) {
            writeService.createDocument(
                    TenantId.of(tenant),
                    itemId,
                    secondDocument,
                    "a.pdf",
                    connectorId,
                    "a.pdf",
                    "a".repeat(64),
                    "application/pdf",
                    1,
                    KnowledgeVisibility.TENANT,
                    null,
                    user,
                    now.plusSeconds(180L * 24 * 3600),
                    "etag",
                    user,
                    "trace",
                    now);
        }
        assertThat(documentCount(tenant)).isEqualTo(1);
        assertThat(itemStatus(tenant, itemId)).isEqualTo("SYNCED");
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM agent_run_outbox
                         WHERE tenant_id=:tenant AND event_type='riccon.knowledge.index.requested.v1'
                        """).param("tenant", tenant).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void createDocument_deletedConnectorRollsBackBeforeInsert() {
        String tenant = uniqueTenant("conn-write-lock");
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        UUID connectorId = com.riccon.worker.persistence.UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO kb_connector (
                            tenant_id, id, name, type, status, endpoint_id, config, default_visibility,
                            schedule_enabled, created_by, created_at, updated_at, deleted_at
                        ) VALUES (
                            :tenant, :id, :name, 'S3', 'PAUSED', 'local-rustfs', :config,
                            'TENANT', FALSE, :createdBy, NOW(6), NOW(6), NOW(6)
                        )
                        """)
                .param("tenant", tenant)
                .param("id", com.riccon.worker.persistence.UuidBinary.toBytes(connectorId))
                .param("name", "write-lock-" + connectorId.toString().substring(0, 8))
                .param("config", JSON.writeValueAsString(Map.of("bucket", BUCKET, "prefix", "x/")))
                .param("createdBy", com.riccon.worker.persistence.UuidBinary.toBytes(user))
                .update();
        UUID itemId = com.riccon.worker.persistence.UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO kb_connector_item (
                            tenant_id, id, connector_id, external_id, external_id_hash, external_version,
                            generation, status, last_seen_at, created_at, updated_at
                        ) VALUES (
                            :tenant, :id, :connectorId, 'a.pdf', :hash, 'etag',
                            1, 'PENDING', NOW(6), NOW(6), NOW(6)
                        )
                        """)
                .param("tenant", tenant)
                .param("id", com.riccon.worker.persistence.UuidBinary.toBytes(itemId))
                .param("connectorId", com.riccon.worker.persistence.UuidBinary.toBytes(connectorId))
                .param("hash", sha256("a.pdf"))
                .update();
        Instant now = Instant.now();
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(tenant))) {
            assertThatThrownBy(() -> writeService.createDocument(
                            TenantId.of(tenant),
                            itemId,
                            com.riccon.worker.persistence.UuidBinary.newV7(),
                            "a.pdf",
                            connectorId,
                            "a.pdf",
                            "a".repeat(64),
                            "application/pdf",
                            1,
                            KnowledgeVisibility.TENANT,
                            null,
                            user,
                            now.plusSeconds(180L * 24 * 3600),
                            "etag",
                            user,
                            "trace",
                            now))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not syncable");
        }
        assertThat(documentCount(tenant)).isZero();
        assertThat(itemStatus(tenant, itemId)).isEqualTo("PENDING");
    }

    private long documentCount(String tenant) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM knowledge_document WHERE tenant_id=:tenant")
                .param("tenant", tenant)
                .query(Long.class)
                .single();
    }

    private String itemStatus(String tenant, UUID itemId) {
        return jdbcClient
                .sql("SELECT status FROM kb_connector_item WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", tenant)
                .param("id", com.riccon.worker.persistence.UuidBinary.toBytes(itemId))
                .query(String.class)
                .single();
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
