package com.riccon.worker.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

    /** 事件 spy；默认执行真实幂等分支，失败用例再 doThrow。 */
    @MockitoSpyBean
    private ConnectorEventPublisher eventPublisher;

    /** 传播服务。 */
    @Autowired
    private ConnectorDeletePropagationService propagationService;

    /** 仓储 spy；默认真实执行，用于验证幂等分支确实查询了事件。 */
    @MockitoSpyBean
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

    @Test
    void propagate_existingDeleteEventIsIdempotent() {
        Fixture fixture = fixture();
        UUID runId = com.riccon.worker.persistence.UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO agent_run (
                            tenant_id, id, kind, user_id, status, attempt, max_attempts, trace_id,
                            created_at, updated_at
                        ) VALUES (
                            :tenant, :id, 'KNOWLEDGE_INDEX', :userId, 'PENDING', 1, 3, 'trace',
                            NOW(6), NOW(6)
                        )
                        """)
                .param("tenant", fixture.tenant())
                .param("id", com.riccon.worker.persistence.UuidBinary.toBytes(runId))
                .param("userId", com.riccon.worker.persistence.UuidBinary.toBytes(fixture.user()))
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO agent_run_outbox (
                            tenant_id, id, run_id, event_type, event_version, idempotency_key, payload,
                            status, attempts, available_at, created_at, updated_at
                        ) VALUES (
                            :tenant, :id, :runId, 'riccon.knowledge.index.requested.v1', 1,
                            :key, '{}', 'PENDING', 0, NOW(6), NOW(6), NOW(6)
                        )
                        """)
                .param("tenant", fixture.tenant())
                .param(
                        "id",
                        com.riccon.worker.persistence.UuidBinary.toBytes(
                                com.riccon.worker.persistence.UuidBinary.newV7()))
                .param("runId", com.riccon.worker.persistence.UuidBinary.toBytes(runId))
                .param("key", "doc:" + fixture.documentId() + ":delete:1")
                .update();
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(fixture.tenant()))) {
            assertThat(propagationService.propagate(
                            TenantId.of(fixture.tenant()), fixture.item(), fixture.user(), "trace", Instant.now()))
                    .isTrue();
        }
        assertThat(itemStatus(fixture)).isEqualTo("DELETED");
        assertThat(documentDeletedAt(fixture)).isNotNull();
        // 证明真实 publisher 的 enqueueDelete 被调用，并在 hasDeleteIndexEvent 命中后提前返回。
        verify(eventPublisher).enqueueDelete(any(), any(), anyInt(), any(), any(), any());
        verify(connectorRepository).hasDeleteIndexEvent(any(), eq(fixture.documentId()), eq(1));
        assertThat(jdbcClient
                        .sql("""
                        SELECT COUNT(*) FROM agent_run_outbox
                         WHERE tenant_id=:tenant AND idempotency_key=:key
                        """)
                        .param("tenant", fixture.tenant())
                        .param("key", "doc:" + fixture.documentId() + ":delete:1")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
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
