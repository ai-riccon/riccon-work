package com.riccon.worker.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.connector.domain.ConnectorItemRecord;
import com.riccon.worker.connector.repository.ConnectorRepository;
import com.riccon.worker.connector.service.ConnectorAuditWriter;
import com.riccon.worker.connector.service.ConnectorDeletePropagationService;
import com.riccon.worker.connector.service.ConnectorEventPublisher;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

/**
 * 删除传播短事务 fail-closed：审计/索引事件失败时 item 与文档都必须回滚。
 */
class ConnectorDeletePropagationIntegrationTest extends WorkerIntegrationTestBase {

    /** JSON。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 审计 mock。 */
    @MockitoBean
    private ConnectorAuditWriter auditWriter;

    /** 事件 mock。 */
    @MockitoBean
    private ConnectorEventPublisher eventPublisher;

    /** 传播服务。 */
    @Autowired
    private ConnectorDeletePropagationService propagationService;

    /** 仓储。 */
    @Autowired
    private ConnectorRepository connectorRepository;

    @Test
    void propagate_auditFailureRollsBackItemAndDocument() {
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditWriter)
                .documentDeleted(any(), any(), any(), any());
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(fixture.tenant()))) {
            assertThatThrownBy(() -> propagationService.propagate(
                            TenantId.of(fixture.tenant()), fixture.item(), fixture.user(), "trace", Instant.now()))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(itemStatus(fixture)).isEqualTo("SYNCED");
        assertThat(documentDeletedAt(fixture)).isNull();
        reset(auditWriter);
    }

    @Test
    void propagate_eventFailureRollsBackItemAndDocument() {
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("event unavailable"))
                .when(eventPublisher)
                .enqueueDelete(any(), any(), anyInt(), any(), any(), any());
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(fixture.tenant()))) {
            assertThatThrownBy(() -> propagationService.propagate(
                            TenantId.of(fixture.tenant()), fixture.item(), fixture.user(), "trace", Instant.now()))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(itemStatus(fixture)).isEqualTo("SYNCED");
        assertThat(documentDeletedAt(fixture)).isNull();
        reset(eventPublisher);
    }

    /**
     * 创建租户、连接器、文档、条目。
     */
    private Fixture fixture() {
        String tenant = uniqueTenant("conn-del");
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
                .param("name", "del-" + connectorId.toString().substring(0, 8))
                .param("config", JSON.writeValueAsString(Map.of("bucket", BUCKET, "prefix", "x/")))
                .param("createdBy", com.riccon.worker.persistence.UuidBinary.toBytes(user))
                .update();
        UUID documentId = com.riccon.worker.persistence.UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_document (
                            tenant_id, id, title, source_type, file_id, connector_id, external_id, checksum,
                            mime_type, size_bytes, status, created_by, created_at, updated_at, visibility,
                            department_id, parse_engine, acl_version
                        ) VALUES (
                            :tenant, :id, 'doc', 'CONNECTOR', NULL, :connectorId, 'a.pdf', :checksum,
                            'application/pdf', 1, 'INDEXED', NULL, NOW(6), NOW(6), 'TENANT', NULL, 'NONE', 1
                        )
                        """)
                .param("tenant", tenant)
                .param("id", com.riccon.worker.persistence.UuidBinary.toBytes(documentId))
                .param("connectorId", com.riccon.worker.persistence.UuidBinary.toBytes(connectorId))
                .param("checksum", "a".repeat(64))
                .update();
        UUID itemId = com.riccon.worker.persistence.UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO kb_connector_item (
                            tenant_id, id, connector_id, external_id, external_id_hash, external_version,
                            checksum, document_id, generation, status, last_seen_at, created_at, updated_at
                        ) VALUES (
                            :tenant, :id, :connectorId, 'a.pdf', :hash, 'etag',
                            :checksum, :documentId, 1, 'SYNCED', NOW(6), NOW(6), NOW(6)
                        )
                        """)
                .param("tenant", tenant)
                .param("id", com.riccon.worker.persistence.UuidBinary.toBytes(itemId))
                .param("connectorId", com.riccon.worker.persistence.UuidBinary.toBytes(connectorId))
                .param("hash", sha256("a.pdf"))
                .param("checksum", "a".repeat(64))
                .param("documentId", com.riccon.worker.persistence.UuidBinary.toBytes(documentId))
                .update();
        ConnectorItemRecord item;
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(tenant))) {
            item = connectorRepository
                    .findItem(TenantId.of(tenant), connectorId, sha256("a.pdf"))
                    .orElseThrow();
        }
        return new Fixture(tenant, user, itemId, documentId, item);
    }

    private String itemStatus(Fixture fixture) {
        return jdbcClient
                .sql("SELECT status FROM kb_connector_item WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", fixture.tenant())
                .param("id", com.riccon.worker.persistence.UuidBinary.toBytes(fixture.itemId()))
                .query(String.class)
                .single();
    }

    private Object documentDeletedAt(Fixture fixture) {
        return jdbcClient
                .sql("SELECT deleted_at FROM knowledge_document WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", fixture.tenant())
                .param("id", com.riccon.worker.persistence.UuidBinary.toBytes(fixture.documentId()))
                .query()
                .optionalValue()
                .orElse(null);
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

    private record Fixture(String tenant, UUID user, UUID itemId, UUID documentId, ConnectorItemRecord item) {}
}
