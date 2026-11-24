package com.riccon.api.knowledge.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * 连接器关闭开关回滚（ADR-0023 §7.5/§7.6、RUNBOOK M05b-4）。
 *
 * <p>{@code RICCON_CONNECTOR_ENABLED=false} 时只停止连接器管理与新同步；V12 已写入的连接器文档必须仍可读，
 * 不因开关关闭被删除或不可访问。测试使用真实 HTTP 与数据库，不启动 Worker 调度。
 */
@TestPropertySource(properties = "riccon.connector.enabled=false")
class ConnectorDisabledRollbackVerifyTest extends ApiIntegrationTestSupport {

    @Test
    @DisplayName("连接器关闭：写/测试/同步/凭据 503，已同步文档仍可读")
    void disabledConnector_keepsExistingDocumentsReadable() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("v2brollback");
        createTenant(platform, tenant);
        LoginSession admin = createAndLoginTenantAdmin(platform, tenant);
        UUID connectorId = UuidV7.generate();
        UUID documentId = insertConnectorDocument(tenant, connectorId, actorId(admin));

        // 管理面写操作按开关拒绝；错误码为契约中的 503 RICCON-KB-503。
        assertThat(client.get("/api/v1/knowledge/connectors", admin.sessionCookie(), Map.of())
                        .status())
                .as("只读列表在回滚期保留，便于观察已保存配置；它不产生新同步")
                .isEqualTo(200);
        assertUnavailable(createConnector(admin));
        assertUnavailable(client.post(
                "/api/v1/knowledge/connectors/" + connectorId + "/test",
                "",
                admin.sessionCookie(),
                admin.csrfToken(),
                Map.of()));
        assertUnavailable(client.post(
                "/api/v1/knowledge/connectors/" + connectorId + "/sync",
                "",
                admin.sessionCookie(),
                admin.csrfToken(),
                Map.of("Idempotency-Key", "rollback-sync")));
        assertUnavailable(client.put(
                "/api/v1/knowledge/connectors/" + connectorId + "/credential",
                client.jsonBody("accessKeyId", "rollback-access", "secretAccessKey", "rollback-secret"),
                admin.sessionCookie(),
                admin.csrfToken()));

        // 已写入的 V12 文档不被开关删除，仍按既有 ACL 可读。
        assertThat(detail(admin, documentId).status()).isEqualTo(200);
        JsonNode detail = client.json(detail(admin, documentId));
        assertThat(detail.path("id").asText()).isEqualTo(documentId.toString());
        assertThat(detail.path("sourceType").asText()).isEqualTo("CONNECTOR");
        JsonNode list =
                client.json(client.get("/api/v1/knowledge/documents?page=1&size=100", admin.sessionCookie(), Map.of()));
        assertThat(list.path("items").toString()).contains(documentId.toString());

        assertThat(jdbcClient.sql("""
                                SELECT COUNT(*) FROM audit_event
                                 WHERE tenant_id = :tenant AND action LIKE 'knowledge.connector.%'
                                """).param("tenant", tenant).query(Long.class).single())
                .isZero();
        assertThat(jdbcClient.sql("""
                                SELECT COUNT(*) FROM agent_run_outbox
                                 WHERE tenant_id = :tenant
                                   AND event_type = 'riccon.knowledge.connector.sync.requested.v1'
                                """).param("tenant", tenant).query(Long.class).single())
                .isZero();
    }

    /**
     * 插入一个连接器文档，不依赖连接器行；验证开关关闭不回滚 V12 数据。
     *
     * @param tenant 租户
     * @param connectorId 逻辑连接器 ID
     * @param creator 创建人
     * @return 文档 ID
     */
    private UUID insertConnectorDocument(String tenant, UUID connectorId, UUID creator) {
        UUID documentId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_document (
                            tenant_id, id, title, source_type, file_id, connector_id, external_id, checksum,
                            mime_type, size_bytes, status, created_by, created_at, updated_at, visibility,
                            department_id, parse_engine, acl_version
                        ) VALUES (
                            :tenant, :id, '回滚连接器文档', 'CONNECTOR', NULL, :connectorId, 'rollback/doc.pdf',
                            :checksum, 'application/pdf', 4, 'INDEXED', :createdBy, NOW(6), NOW(6), 'TENANT',
                            NULL, 'NONE', 1
                        )
                        """)
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(documentId))
                .param("connectorId", BinaryUuids.toBytes(connectorId))
                .param("checksum", "e".repeat(64))
                .param("createdBy", BinaryUuids.toBytes(creator))
                .update();
        return documentId;
    }

    /**
     * 创建连接器请求；返回 503 即可，不需要有效端点。
     *
     * @param session 会话
     * @return 响应
     */
    private ApiTestClient.ApiResponse createConnector(LoginSession session) {
        return client.post(
                "/api/v1/knowledge/connectors",
                client.jsonBody(
                        "name", "rollback-" + UUID.randomUUID().toString().substring(0, 8),
                        "type", "S3",
                        "endpointId", "rollback-endpoint",
                        "config", Map.of("bucket", "rollback-bucket"),
                        "defaultVisibility", "TENANT"),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of("Idempotency-Key", "rollback-create-" + UUID.randomUUID()));
    }

    /**
     * 读取文档详情。
     *
     * @param session 会话
     * @param documentId 文档 ID
     * @return 响应
     */
    private ApiTestClient.ApiResponse detail(LoginSession session, UUID documentId) {
        return client.get("/api/v1/knowledge/documents/" + documentId, session.sessionCookie(), Map.of());
    }

    /**
     * 断言连接器功能不可用。
     *
     * @param response 响应
     */
    private static void assertUnavailable(ApiTestClient.ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(503);
        assertThat(response.body()).contains("RICCON-KB-503");
    }
}
