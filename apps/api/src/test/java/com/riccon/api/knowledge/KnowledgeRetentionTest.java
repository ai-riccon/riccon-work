package com.riccon.api.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.knowledge.service.KnowledgeRetentionJob;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.platform.domain.audit.AuditActions;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M05 保留到期：默认 180 天、租户策略、各状态软删除与索引 DELETE 边界。
 */
@TestPropertySource(properties = "riccon.kb.acl.enforcement-enabled=true")
class KnowledgeRetentionTest extends KnowledgeAclTestSupport {

    /** JSON 解析。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 保留任务。 */
    @Autowired
    private KnowledgeRetentionJob retentionJob;

    /** 默认 180 天与租户策略覆盖。 */
    @Test
    @DisplayName("保留：默认 180 天与租户策略")
    void retention_defaultsAndTenantPolicy() {
        TenantFixture fixture = tenantFixture();
        JsonNode first = createDocument(fixture, "默认保留", null, null, null);
        Map<String, Object> firstRow =
                documentRow(fixture.tenant(), first.path("id").asText());
        assertThat(Duration.between(
                                ((java.time.LocalDateTime) firstRow.get("created_at"))
                                        .toInstant(java.time.ZoneOffset.UTC),
                                ((java.time.LocalDateTime) firstRow.get("retention_expires_at"))
                                        .toInstant(java.time.ZoneOffset.UTC))
                        .toDays())
                .isEqualTo(180);
        assertThat(first.path("retentionDays").asInt()).isEqualTo(180);
        assertThat(first.path("retentionPolicy").asText()).isEqualTo("DEPLOYMENT_DEFAULT");

        jdbcClient
                .sql("UPDATE tenant SET retention_days = 90 WHERE id = :tenantId")
                .param("tenantId", fixture.tenant())
                .update();
        JsonNode second = createDocument(fixture, "租户策略", null, null, null);
        assertThat(second.path("retentionDays").asInt()).isEqualTo(90);
        assertThat(second.path("retentionPolicy").asText()).isEqualTo("TENANT_POLICY");
        Map<String, Object> secondRow =
                documentRow(fixture.tenant(), second.path("id").asText());
        assertThat(Duration.between(
                                ((java.time.LocalDateTime) secondRow.get("created_at"))
                                        .toInstant(java.time.ZoneOffset.UTC),
                                ((java.time.LocalDateTime) secondRow.get("retention_expires_at"))
                                        .toInstant(java.time.ZoneOffset.UTC))
                        .toDays())
                .isEqualTo(90);
    }

    /** 历史 NULL 行固定按 180 天，不受租户策略调低影响；响应同样返回 180。 */
    @Test
    @DisplayName("历史 NULL 保留：固定 180 天且不追溯")
    void retention_historicalNullUsesDeploymentDefault() {
        TenantFixture fixture = tenantFixture();
        UUID adminId = actorId(fixture.admin());
        jdbcClient
                .sql("UPDATE tenant SET retention_days = 90 WHERE id = :tenantId")
                .param("tenantId", fixture.tenant())
                .update();

        String historical150 = insertHistoricalNullDocument(
                fixture.tenant(), "历史150天", adminId, Instant.now().minus(Duration.ofDays(150)));
        String historical181 = insertHistoricalNullDocument(
                fixture.tenant(), "历史181天", adminId, Instant.now().minus(Duration.ofDays(181)));
        JsonNode newDocument = createDocument(fixture, "新策略90天", null, null, null);

        retentionJob.expireDaily();

        Map<String, Object> kept = documentRow(fixture.tenant(), historical150);
        assertThat(kept.get("status")).isEqualTo("PENDING");
        assertThat(kept.get("deleted_at")).isNull();
        JsonNode response = client.json(getDocument(fixture.admin(), historical150));
        assertThat(response.path("retentionDays").asInt()).isEqualTo(180);
        assertThat(response.path("retentionPolicy").asText()).isEqualTo("DEPLOYMENT_DEFAULT");

        Map<String, Object> expired = documentRow(fixture.tenant(), historical181);
        assertThat(expired.get("status")).isEqualTo("DELETED");
        assertThat(expired.get("deleted_at")).isNotNull();
        List<AuditRow> audits = documentAudit(AuditActions.KNOWLEDGE_DOCUMENT_DELETED, fixture.tenant(), historical181);
        assertThat(audits).hasSize(1);
        assertThat(JSON.readTree(audits.get(0).details()).path("reason").asText())
                .isEqualTo("RETENTION");

        assertThat(newDocument.path("retentionDays").asInt()).isEqualTo(90);
        assertThat(newDocument.path("retentionPolicy").asText()).isEqualTo("TENANT_POLICY");
    }

    /** 到期清理覆盖 INDEXED/INDEXING/FAILED/PENDING；仅已索引或已有分块的发索引 DELETE。 */
    @Test
    @DisplayName("到期清理：状态覆盖、索引 DELETE 边界与幂等")
    void expireTenant_coversStatusesAndIsIdempotent() {
        TenantFixture fixture = tenantFixture();
        String indexedWithChunk =
                createDocument(fixture, "已索引", null, null, null).path("id").asText();
        String indexingWithChunk =
                createDocument(fixture, "索引中", null, null, null).path("id").asText();
        String failedNoChunk =
                createDocument(fixture, "失败", null, null, null).path("id").asText();
        String pendingNoChunk =
                createDocument(fixture, "等待", null, null, null).path("id").asText();
        String notExpired =
                createDocument(fixture, "未到期", null, null, null).path("id").asText();
        String alreadyDeleted =
                createDocument(fixture, "已删除", null, null, null).path("id").asText();

        markIndexEventsDone(fixture.tenant());
        Instant past = Instant.now().minus(Duration.ofDays(1));
        updateLifecycle(fixture.tenant(), indexedWithChunk, "INDEXED", past);
        insertChunk(fixture.tenant(), indexedWithChunk, 1);
        updateLifecycle(fixture.tenant(), indexingWithChunk, "INDEXING", past);
        insertChunk(fixture.tenant(), indexingWithChunk, 1);
        updateLifecycle(fixture.tenant(), failedNoChunk, "FAILED", past);
        updateLifecycle(fixture.tenant(), pendingNoChunk, "PENDING", past);
        updateLifecycle(fixture.tenant(), alreadyDeleted, "DELETED", past);
        jdbcClient
                .sql("""
                        UPDATE knowledge_document SET deleted_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", fixture.tenant())
                .param("id", BinaryUuids.toBytes(UUID.fromString(alreadyDeleted)))
                .update();

        retentionJob.expireDaily();

        assertExpired(fixture, indexedWithChunk, true);
        assertExpired(fixture, indexingWithChunk, true);
        assertExpired(fixture, failedNoChunk, false);
        assertExpired(fixture, pendingNoChunk, false);
        Map<String, Object> untouched = documentRow(fixture.tenant(), notExpired);
        assertThat(untouched.get("status")).isEqualTo("PENDING");
        assertThat(untouched.get("deleted_at")).isNull();
        assertThat(documentAudit(AuditActions.KNOWLEDGE_DOCUMENT_DELETED, fixture.tenant(), notExpired))
                .isEmpty();

        assertThat(documentAudit(AuditActions.KNOWLEDGE_DOCUMENT_DELETED, fixture.tenant(), alreadyDeleted))
                .isEmpty();

        // 再次调度不重复审计/事件。
        long auditsBefore = auditRows(AuditActions.KNOWLEDGE_DOCUMENT_DELETED).stream()
                .filter(row -> fixture.tenant().equals(row.tenantId()))
                .count();
        retentionJob.expireDaily();
        long auditsAfter = auditRows(AuditActions.KNOWLEDGE_DOCUMENT_DELETED).stream()
                .filter(row -> fixture.tenant().equals(row.tenantId()))
                .count();
        assertThat(auditsAfter).isEqualTo(auditsBefore);
    }

    /**
     * 连接器来源文档与普通文档共用保留到期任务：软删除、审计 CONNECTOR/RETENTION 与索引 DELETE（IT-CONN-107）。
     */
    @Test
    @DisplayName("连接器文档保留到期：软删除、审计与索引 DELETE")
    void retention_coversConnectorSourceDocuments() {
        TenantFixture fixture = tenantFixture();
        Instant now = Instant.now();
        UUID connectorId = UuidV7.generate();
        String documentId = insertConnectorDocument(
                fixture.tenant(),
                connectorId,
                actorId(fixture.admin()),
                now.minus(Duration.ofDays(1)),
                now.minus(Duration.ofHours(1)));
        insertChunk(fixture.tenant(), documentId, 1);

        retentionJob.expireDaily();

        assertExpired(fixture, documentId, true);
        assertThat(documentRow(fixture.tenant(), documentId).get("status")).isEqualTo("DELETED");
        assertThat(listDocuments(fixture.admin()).body()).doesNotContain(documentId);
        assertThat(getDocument(fixture.admin(), documentId).status()).isEqualTo(404);
    }

    /**
     * 插入一个连接器来源文档。
     *
     * @param tenant 租户
     * @param connectorId 逻辑连接器 ID
     * @param creator 创建人
     * @param createdAt 创建时间
     * @param retentionExpiresAt 保留到期
     * @return 文档 ID
     */
    private String insertConnectorDocument(
            String tenant, UUID connectorId, UUID creator, Instant createdAt, Instant retentionExpiresAt) {
        UUID documentId = UuidV7.generate(createdAt);
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_document (
                            tenant_id, id, title, source_type, file_id, connector_id, external_id, checksum,
                            mime_type, size_bytes, status, created_by, created_at, updated_at, visibility,
                            department_id, parse_engine, acl_version, retention_expires_at
                        ) VALUES (
                            :tenant, :id, '连接器保留文档', 'CONNECTOR', NULL, :connectorId, 'retention/doc.pdf',
                            :checksum, 'application/pdf', 4, 'INDEXED', :createdBy, :createdAt, :createdAt,
                            'TENANT', NULL, 'NONE', 1, :retention
                        )
                        """)
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(documentId))
                .param("connectorId", BinaryUuids.toBytes(connectorId))
                .param("checksum", "f".repeat(64))
                .param("createdBy", BinaryUuids.toBytes(creator))
                .param("createdAt", Timestamp.from(createdAt))
                .param("retention", Timestamp.from(retentionExpiresAt))
                .update();
        return documentId.toString();
    }

    /**
     * 断言文档已软删除、审计存在、索引 DELETE 是否符合预期。
     *
     * @param fixture 租户夹具
     * @param documentId 文档 ID
     * @param expectDeleteEvent 是否应产生索引 DELETE
     */
    private void assertExpired(TenantFixture fixture, String documentId, boolean expectDeleteEvent) {
        Map<String, Object> row = documentRow(fixture.tenant(), documentId);
        assertThat(row.get("status")).isEqualTo("DELETED");
        assertThat(row.get("deleted_at")).isNotNull();
        List<AuditRow> audits = documentAudit(AuditActions.KNOWLEDGE_DOCUMENT_DELETED, fixture.tenant(), documentId);
        assertThat(audits).hasSize(1);
        JsonNode details = JSON.readTree(audits.get(0).details());
        assertThat(details.path("reason").asText()).isEqualTo("RETENTION");
        long deleteEvents = documentEvents(fixture.tenant(), documentId).stream()
                .filter(event -> event.idempotencyKey().contains(":delete:"))
                .count();
        assertThat(deleteEvents).isEqualTo(expectDeleteEvent ? 1 : 0);
    }

    /**
     * 插入历史 NULL retention 文档。
     *
     * @param tenant 租户
     * @param title 标题
     * @param creator 创建人
     * @param createdAt 创建时间
     * @return 文档 ID
     */
    private String insertHistoricalNullDocument(String tenant, String title, UUID creator, Instant createdAt) {
        UUID documentId = UuidV7.generate(createdAt);
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_document (
                            tenant_id, id, title, source_type, file_id, checksum, mime_type, size_bytes,
                            status, created_by, created_at, updated_at, retention_expires_at
                        ) VALUES (
                            :tenantId, :id, :title, 'SEED', NULL, :checksum, 'text/plain', 4,
                            'PENDING', :createdBy, :createdAt, :createdAt, NULL
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(documentId))
                .param("title", title)
                .param("checksum", "0".repeat(64))
                .param("createdBy", BinaryUuids.toBytes(creator))
                .param("createdAt", Timestamp.from(createdAt))
                .update();
        return documentId.toString();
    }

    /**
     * 更新文档状态与到期时间。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @param status 状态
     * @param retentionExpiresAt 到期时间
     */
    private void updateLifecycle(String tenant, String documentId, String status, Instant retentionExpiresAt) {
        jdbcClient
                .sql("""
                        UPDATE knowledge_document
                           SET status = :status, retention_expires_at = :retention
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("status", status)
                .param("retention", Timestamp.from(retentionExpiresAt))
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(UUID.fromString(documentId)))
                .update();
    }

    /**
     * 插入一个知识库分块。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @param ordinal 序号
     */
    private void insertChunk(String tenant, String documentId, int ordinal) {
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_chunk
                            (tenant_id, id, document_id, ordinal, locator_type, page_no, content)
                        VALUES
                            (:tenantId, :id, :documentId, :ordinal, 'PAGE', 1, '保留测试分块')
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(UuidV7.generate(Instant.now())))
                .param("documentId", BinaryUuids.toBytes(UUID.fromString(documentId)))
                .param("ordinal", ordinal)
                .update();
    }

    /**
     * 把租户所有索引事件标记为 DONE。
     *
     * @param tenant 租户
     */
    private void markIndexEventsDone(String tenant) {
        jdbcClient.sql("""
                        UPDATE agent_run_outbox SET status = 'DONE'
                         WHERE tenant_id = :tenantId AND status IN ('PENDING', 'DISPATCHED')
                        """).param("tenantId", tenant).update();
    }
}
