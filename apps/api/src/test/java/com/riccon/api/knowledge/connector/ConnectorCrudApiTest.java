package com.riccon.api.knowledge.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.crypto.CredentialCipher;
import com.riccon.api.crypto.MasterKeyProvider;
import com.riccon.api.knowledge.connector.ConnectorApiTestSupport.ConnectorFixture;
import com.riccon.api.knowledge.connector.ConnectorApiTestSupport.Credential;
import com.riccon.api.knowledge.connector.ConnectorApiTestSupport.EventRow;
import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.common.crypto.CredentialEnvelope;
import com.riccon.platform.domain.knowledge.ConnectorType;
import com.riccon.platform.domain.knowledge.KnowledgeIndexMode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 连接器 CRUD、权限、校验、软删除与条目分页。
 *
 * <p>覆盖 IT-CONN-101 的 CRUD/权限/审计部分与 IT-CONN-105 的删除处置；同步执行与凭据测试在
 * {@link ConnectorCredentialSyncApiTest}。
 */
class ConnectorCrudApiTest extends ConnectorApiTestSupport {

    /** 主密钥提供者，用于验证信封加密往返。 */
    @Autowired
    private MasterKeyProvider masterKeyProvider;

    /** 凭据加密器。 */
    @Autowired
    private CredentialCipher credentialCipher;

    @Test
    @DisplayName("创建连接器：显式 defaultVisibility、掩码、调度、审计与信封加密")
    void createConnector_persistsMaskedCredentialAndAudit() {
        ConnectorFixture fixture = connectorFixture();
        String name = "conn-" + UUID.randomUUID().toString().substring(0, 8);
        ApiTestClient.ApiResponse response =
                createConnector(fixture.admin(), name, "TENANT", null, true, Credential.valid());
        JsonNode body = json(response, 201);
        String connectorId = body.path("id").asText();
        assertThat(body.path("type").asText()).isEqualTo(ConnectorType.S3.name());
        assertThat(body.path("status").asText()).isEqualTo("PAUSED");
        assertThat(body.path("defaultVisibility").asText()).isEqualTo("TENANT");
        assertThat(body.path("scheduleEnabled").asBoolean()).isTrue();
        assertThat(body.path("nextSyncAt").isNull()).isFalse();
        assertThat(body.path("credentialMaskedHint").asText())
                .isNotBlank()
                .doesNotContain(Credential.valid().secretAccessKey());

        Map<String, Object> row = connectorRow(fixture.tenant(), connectorId);
        byte[] ciphertext = (byte[]) row.get("credential_ciphertext");
        assertThat(ciphertext).isNotNull();
        assertThat(new String(ciphertext, java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain(Credential.valid().accessKeyId())
                .doesNotContain(Credential.valid().secretAccessKey())
                .startsWith("enc:v1:");
        assertThat(row.get("credential_key_id")).isNotNull();
        assertThat(row.get("credential_masked_hint"))
                .isEqualTo(body.path("credentialMaskedHint").asText());

        // 验证 DEK/AAD 与解密往返，确保 Worker 侧同一格式可解。
        Map<String, Object> keyRow = jdbcClient
                .sql("""
                        SELECT wrapped_dek, wrap_nonce FROM tenant_data_key
                         WHERE tenant_id = :tenant AND key_id = :keyId AND purpose = 'connector-credential'
                        """)
                .param("tenant", fixture.tenant())
                .param("keyId", row.get("credential_key_id"))
                .query()
                .singleRow();
        byte[] aad =
                CredentialEnvelope.aad(fixture.tenant(), "connector-credential", (String) row.get("credential_key_id"));
        byte[] dek =
                masterKeyProvider.unwrap((byte[]) keyRow.get("wrap_nonce"), (byte[]) keyRow.get("wrapped_dek"), aad);
        try {
            String plaintext = credentialCipher.decrypt(
                    fixture.tenant(),
                    "connector-credential",
                    (String) row.get("credential_key_id"),
                    dek,
                    new String(ciphertext, java.nio.charset.StandardCharsets.UTF_8));
            assertThat(plaintext).contains(Credential.valid().accessKeyId());
            assertThat(plaintext).contains(Credential.valid().secretAccessKey());
        } finally {
            Arrays.fill(dek, (byte) 0);
        }

        List<AuditRow> created = auditRows("knowledge.connector.created").stream()
                .filter(entry -> entry.tenantId().equals(fixture.tenant()))
                .toList();
        assertThat(created).hasSize(1);
        JsonNode details = JSON.readTree(created.get(0).details());
        assertThat(details.path("connectorId").asText()).isEqualTo(connectorId);
        assertThat(details.path("bucket").asText()).isEqualTo(BUCKET);
        assertThat(details.toString()).doesNotContain(Credential.valid().secretAccessKey());
        assertThat(details.path("credential").isMissingNode()).isTrue();

        assertThat(listConnectors(fixture.admin()).path("items").toString()).contains(connectorId);
    }

    @Test
    @DisplayName("创建校验：defaultVisibility 缺省/非法、未知字段、未知端点与名称冲突")
    void createConnector_validationAndConflict() {
        ConnectorFixture fixture = connectorFixture();
        ObjectNode body = JSON.createObjectNode();
        body.put("name", "missing-visibility");
        body.put("type", "S3");
        body.put("endpointId", ENDPOINT_ID);
        body.putObject("config").put("bucket", BUCKET);
        ApiTestClient.ApiResponse missing = client.post(
                "/api/v1/knowledge/connectors",
                JSON.writeValueAsString(body),
                fixture.admin().sessionCookie(),
                fixture.admin().csrfToken(),
                Map.of());
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.body()).contains("RICCON-KB-400");

        ObjectNode unknown = body.deepCopy();
        unknown.put("defaultVisibility", "TENANT");
        unknown.put("cron", "* * * * *");
        ApiTestClient.ApiResponse unknownResponse = client.post(
                "/api/v1/knowledge/connectors",
                JSON.writeValueAsString(unknown),
                fixture.admin().sessionCookie(),
                fixture.admin().csrfToken(),
                Map.of());
        assertThat(unknownResponse.status()).isEqualTo(400);
        assertThat(unknownResponse.body()).contains("RICCON-KB-400");

        String name = "conn-" + UUID.randomUUID().toString().substring(0, 8);
        json(createConnector(fixture.admin(), name, "TENANT", null, false, null), 201);
        ApiTestClient.ApiResponse duplicate = createConnector(fixture.admin(), name, "TENANT", null, false, null);
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.body()).contains("RICCON-KB-409");

        ObjectNode invalidEndpoint = body.deepCopy();
        invalidEndpoint.put("defaultVisibility", "TENANT");
        invalidEndpoint.put("endpointId", "not-allowlisted");
        ApiTestClient.ApiResponse invalidEndpointResponse = client.post(
                "/api/v1/knowledge/connectors",
                JSON.writeValueAsString(invalidEndpoint),
                fixture.admin().sessionCookie(),
                fixture.admin().csrfToken(),
                Map.of());
        assertThat(invalidEndpointResponse.status()).isEqualTo(400);
        assertThat(invalidEndpointResponse.body()).contains("RICCON-KB-400");
    }

    @Test
    @DisplayName("权限与跨租户：员工 403、他租户管理员 404")
    void connectorPermissionsAndTenantIsolation() {
        ConnectorFixture fixture = connectorFixture();
        String name = "conn-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = json(createConnector(fixture.admin(), name, "TENANT", null, false, null), 201);
        String connectorId = created.path("id").asText();

        assertThat(client.get("/api/v1/knowledge/connectors", fixture.member().sessionCookie(), Map.of())
                        .status())
                .isEqualTo(403);
        assertThat(createConnector(fixture.member(), "member-connector", "TENANT", null, false, null)
                        .status())
                .isEqualTo(403);
        assertThat(client.get(
                                "/api/v1/knowledge/connectors/" + connectorId + "/items",
                                fixture.member().sessionCookie(),
                                Map.of())
                        .status())
                .isEqualTo(403);

        ConnectorFixture other = connectorFixture();
        assertThat(patchConnector(other.admin(), connectorId, "{\"name\":\"stolen\"}")
                        .status())
                .isEqualTo(404);
        assertThat(deleteConnector(other.admin(), connectorId, "").status()).isEqualTo(404);
        assertThat(testConnector(other.admin(), connectorId).status()).isEqualTo(404);
        assertThat(syncConnector(other.admin(), connectorId).status()).isEqualTo(404);
        assertThat(listItems(other.admin(), connectorId, "").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("PATCH：配置/调度/状态更新与状态审计；未知字段 400")
    void patchConnector_updatesScheduleAndStatusAudit() {
        ConnectorFixture fixture = connectorFixture();
        String name = "conn-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = json(createConnector(fixture.admin(), name, "TENANT", null, false, null), 201);
        String connectorId = created.path("id").asText();

        ObjectNode patch = JSON.createObjectNode();
        patch.put("scheduleEnabled", true);
        patch.put("status", "ACTIVE");
        patch.putObject("config").put("bucket", BUCKET).put("prefix", "updated/");
        JsonNode updated = json(patchConnector(fixture.admin(), connectorId, JSON.writeValueAsString(patch)), 200);
        assertThat(updated.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(updated.path("scheduleEnabled").asBoolean()).isTrue();
        assertThat(updated.path("nextSyncAt").isNull()).isFalse();
        assertThat(updated.path("config").path("prefix").asText()).isEqualTo("updated/");

        assertThat(auditRows("knowledge.connector.updated").stream()
                        .filter(entry -> entry.tenantId().equals(fixture.tenant()))
                        .count())
                .isEqualTo(1);
        assertThat(auditRows("knowledge.connector.status.changed").stream()
                        .filter(entry -> entry.tenantId().equals(fixture.tenant()))
                        .count())
                .isEqualTo(1);

        assertThat(patchConnector(fixture.admin(), connectorId, "{\"cron\":\"* * * * *\"}")
                        .status())
                .isEqualTo(400);
        assertThat(patchConnector(fixture.admin(), connectorId, "{\"status\":\"ERROR\"}")
                        .status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("删除 KEEP：连接器软删除、文档保留、二次删除幂等")
    void deleteConnector_keepDocuments() {
        ConnectorFixture fixture = connectorFixture();
        String name = "conn-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = json(createConnector(fixture.admin(), name, "TENANT", null, false, null), 201);
        String connectorId = created.path("id").asText();
        UUID documentId = insertConnectorDocument(fixture.tenant(), connectorId);

        assertThat(deleteConnector(fixture.admin(), connectorId, "").status()).isEqualTo(204);
        Map<String, Object> connector = connectorRow(fixture.tenant(), connectorId);
        assertThat(connector.get("deleted_at")).isNotNull();
        Map<String, Object> document = documentRow(fixture.tenant(), documentId);
        assertThat(document.get("deleted_at")).isNull();
        assertThat(events(fixture.tenant(), "riccon.knowledge.index.requested.v1"))
                .isEmpty();

        // 幂等：重复删除 204，不重复审计。
        assertThat(deleteConnector(fixture.admin(), connectorId, "").status()).isEqualTo(204);
        assertThat(auditRows("knowledge.connector.deleted").stream()
                        .filter(entry -> entry.tenantId().equals(fixture.tenant()))
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("删除 SOFT_DELETE：文档软删除、审计 reason=CONNECTOR、投递 DELETE 索引事件")
    void deleteConnector_softDeleteDocuments() {
        ConnectorFixture fixture = connectorFixture();
        String name = "conn-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = json(createConnector(fixture.admin(), name, "TENANT", null, false, null), 201);
        String connectorId = created.path("id").asText();
        UUID documentId = insertConnectorDocument(fixture.tenant(), connectorId);

        assertThat(deleteConnector(fixture.admin(), connectorId, "?documents=SOFT_DELETE")
                        .status())
                .isEqualTo(204);
        Map<String, Object> document = documentRow(fixture.tenant(), documentId);
        assertThat(document.get("status")).isEqualTo("DELETED");
        assertThat(document.get("deleted_at")).isNotNull();

        List<EventRow> events = events(fixture.tenant(), "riccon.knowledge.index.requested.v1");
        assertThat(events).hasSize(1);
        JsonNode payload = JSON.readTree(events.get(0).payload());
        assertThat(payload.path("mode").asText()).isEqualTo(KnowledgeIndexMode.DELETE.name());
        assertThat(payload.path("reason").asText()).isEqualTo("CONNECTOR");

        JsonNode documentDeleted = auditRows("knowledge.document.deleted").stream()
                .filter(entry -> entry.tenantId().equals(fixture.tenant()))
                .map(entry -> JSON.readTree(entry.details()))
                .findFirst()
                .orElseThrow();
        assertThat(documentDeleted.path("reason").asText()).isEqualTo("CONNECTOR");
        assertThat(documentDeleted.path("sourceType").asText()).isEqualTo("CONNECTOR");
        assertThat(documentDeleted.path("visibility").asText()).isEqualTo("TENANT");
        assertThat(documentDeleted.toString()).doesNotContain("docs/a.pdf");

        assertThat(deleteConnector(fixture.admin(), connectorId, "?documents=INVALID")
                        .status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("items：分页、状态过滤与 externalId 末段展示")
    void listItems_paginationAndDisplay() {
        ConnectorFixture fixture = connectorFixture();
        String name = "conn-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = json(createConnector(fixture.admin(), name, "TENANT", null, false, null), 201);
        String connectorId = created.path("id").asText();
        String longSegment = "x".repeat(80);
        insertItem(fixture.tenant(), connectorId, "a/b/c.pdf", "SYNCED");
        insertItem(fixture.tenant(), connectorId, "root/" + longSegment + ".pdf", "FAILED");
        insertItem(fixture.tenant(), connectorId, "skipped.zip", "SKIPPED");

        JsonNode page = json(listItems(fixture.admin(), connectorId, "?page=1&size=2"), 200);
        assertThat(page.path("total").asLong()).isEqualTo(3);
        assertThat(page.path("totalPages").asInt()).isEqualTo(2);
        assertThat(page.path("items")).hasSize(2);
        assertThat(page.path("items").toString()).doesNotContain("docs/");
        for (JsonNode item : page.path("items")) {
            assertThat(item.path("externalId").asText().length()).isLessThanOrEqualTo(32);
        }

        JsonNode filtered = json(listItems(fixture.admin(), connectorId, "?status=SKIPPED"), 200);
        assertThat(filtered.path("total").asLong()).isEqualTo(1);
        assertThat(filtered.path("items").get(0).path("status").asText()).isEqualTo("SKIPPED");
    }

    /**
     * 读取连接器列表。
     *
     * @param session 管理员会话
     * @return JSON
     */
    private JsonNode listConnectors(LoginSession session) {
        return json(client.get("/api/v1/knowledge/connectors?page=1&size=20", session.sessionCookie(), Map.of()), 200);
    }
}
