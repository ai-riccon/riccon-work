package com.riccon.api.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.riccon.api.common.UuidV7;
import com.riccon.api.outbox.domain.OutboxEvent;
import com.riccon.api.outbox.repository.OutboxRepository;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.common.tenant.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 索引队列不可用时重索引返回 503，不留下“部分受理”的运行或事件。
 */
class KnowledgeIndexUnavailableTest extends KnowledgeAclTestSupport {

    /** 抛异常的 outbox 仓储；夹具不使用 outbox，只有重索引写事件时失败。 */
    @MockitoBean
    private OutboxRepository outboxRepository;

    /** 队列写入失败 -> 503 RICCON-KB-503，运行与事件回滚。 */
    @Test
    @DisplayName("索引队列不可用返回 503 并回滚")
    void reindex_queueUnavailableReturns503() {
        TenantFixture fixture = tenantFixture();
        String documentId = insertIndexedSeedDocument(fixture.tenant(), actorId(fixture.admin()));
        doThrow(new DataAccessResourceFailureException("outbox unavailable"))
                .when(outboxRepository)
                .enqueue(any(TenantId.class), any(OutboxEvent.class));

        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/knowledge/documents/" + documentId + "/reindex",
                client.jsonBody(),
                fixture.admin().sessionCookie(),
                fixture.admin().csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(503);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-KB-503");

        Long runs = jdbcClient
                .sql("SELECT COUNT(*) FROM agent_run WHERE tenant_id = :tenantId")
                .param("tenantId", fixture.tenant())
                .query(Long.class)
                .single();
        assertThat(runs).isZero();
        Long events = jdbcClient
                .sql("SELECT COUNT(*) FROM agent_run_outbox WHERE tenant_id = :tenantId")
                .param("tenantId", fixture.tenant())
                .query(Long.class)
                .single();
        assertThat(events).isZero();
    }

    /**
     * 直接写入已索引 SEED 文档。
     *
     * @param tenant 租户
     * @param creator 创建人
     * @return 文档 ID
     */
    private String insertIndexedSeedDocument(String tenant, UUID creator) {
        UUID documentId = UuidV7.generate(Instant.now());
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_document (
                            tenant_id, id, title, source_type, file_id, checksum, mime_type, size_bytes,
                            status, created_by, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, '队列不可用', 'SEED', NULL, :checksum, 'text/plain', 4,
                            'INDEXED', :createdBy, NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(documentId))
                .param("checksum", "0".repeat(64))
                .param("createdBy", BinaryUuids.toBytes(creator))
                .update();
        return documentId.toString();
    }
}
