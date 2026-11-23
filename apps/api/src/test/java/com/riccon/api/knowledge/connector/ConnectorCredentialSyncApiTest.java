package com.riccon.api.knowledge.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.knowledge.connector.ConnectorApiTestSupport.ConnectorFixture;
import com.riccon.api.knowledge.connector.ConnectorApiTestSupport.Credential;
import com.riccon.api.knowledge.connector.ConnectorApiTestSupport.EventRow;
import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.domain.contract.EventTypes;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 凭据只写不读/掩码/轮换、连接测试分类与同步受理/并发 409。
 */
class ConnectorCredentialSyncApiTest extends ConnectorApiTestSupport {

    @Test
    @DisplayName("凭据：只写不读、轮换覆盖、掩码与审计无明文")
    void credential_onlyWriteRotateAndMaskedAudit() {
        ConnectorFixture fixture = connectorFixture();
        String name = "conn-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = json(createConnector(fixture.admin(), name, "TENANT", null, false, null), 201);
        String connectorId = created.path("id").asText();

        JsonNode first = json(putCredential(fixture.admin(), connectorId, Credential.valid()), 200);
        assertThat(first.path("credentialMaskedHint").asText()).isNotBlank();
        assertThat(first.toString()).doesNotContain(Credential.valid().accessKeyId());
        assertThat(first.toString()).doesNotContain(Credential.valid().secretAccessKey());
        byte[] firstCiphertext =
                (byte[]) connectorRow(fixture.tenant(), connectorId).get("credential_ciphertext");

        JsonNode second = json(putCredential(fixture.admin(), connectorId, Credential.valid()), 200);
        byte[] secondCiphertext =
                (byte[]) connectorRow(fixture.tenant(), connectorId).get("credential_ciphertext");
        assertThat(secondCiphertext).isNotNull();
        assertThat(secondCiphertext).isNotEqualTo(firstCiphertext);
        assertThat(second.path("credentialMaskedHint").asText())
                .isEqualTo(first.path("credentialMaskedHint").asText());

        List<String> details = auditRows("knowledge.connector.credential.changed").stream()
                .filter(entry -> entry.tenantId().equals(fixture.tenant()))
                .map(entry -> entry.details())
                .toList();
        assertThat(details).hasSize(2);
        assertThat(details.toString())
                .doesNotContain(Credential.valid().accessKeyId())
                .doesNotContain(Credential.valid().secretAccessKey())
                .contains("maskedHint");
    }

    @Test
    @DisplayName("连接测试：空 bucket 成功；错误凭据 AUTH；不可达端点 NETWORK；无凭据 CONFIG")
    void testConnector_successAndFailureCategories() {
        ConnectorFixture fixture = connectorFixture();
        String prefix = "connector/test-" + UUID.randomUUID().toString().substring(0, 8) + "/";
        String name = prefix.substring("connector/".length(), prefix.length() - 1);
        JsonNode created = json(createConnector(fixture.admin(), name, "TENANT", null, false, Credential.valid()), 201);
        String connectorId = created.path("id").asText();

        JsonNode success = json(testConnector(fixture.admin(), connectorId), 200);
        assertThat(success.path("success").asBoolean()).isTrue();
        assertThat(success.path("category").isNull()).isTrue();
        assertThat(success.path("message").asText()).isNotBlank();
        assertThat(success.toString())
                .doesNotContain(Credential.valid().secretAccessKey())
                .doesNotContain(BUCKET)
                .doesNotContain("127.0.0.1");
        assertThat(listKeys(prefix)).isEmpty();

        JsonNode noCredential = json(
                createConnector(
                        fixture.admin(),
                        "conn-" + UUID.randomUUID().toString().substring(0, 8),
                        "TENANT",
                        null,
                        false,
                        null),
                201);
        JsonNode configFailure =
                json(testConnector(fixture.admin(), noCredential.path("id").asText()), 200);
        assertThat(configFailure.path("success").asBoolean()).isFalse();
        assertThat(configFailure.path("category").asText()).isEqualTo("CONFIG");

        JsonNode wrong = json(
                createConnector(
                        fixture.admin(),
                        "conn-" + UUID.randomUUID().toString().substring(0, 8),
                        "TENANT",
                        null,
                        false,
                        Credential.invalid()),
                201);
        JsonNode authFailure =
                json(testConnector(fixture.admin(), wrong.path("id").asText()), 200);
        assertThat(authFailure.path("success").asBoolean()).isFalse();
        assertThat(authFailure.path("category").asText()).isEqualTo("AUTH");
        assertThat(authFailure.path("message").asText())
                .doesNotContain(Credential.invalid().secretAccessKey())
                .doesNotContain("127.0.0.1");

        JsonNode unreachable = json(
                createConnector(
                        fixture.admin(),
                        "conn-" + UUID.randomUUID().toString().substring(0, 8),
                        "TENANT",
                        null,
                        false,
                        Credential.valid()),
                201);
        String unreachableId = unreachable.path("id").asText();
        assertThat(patchConnector(
                                fixture.admin(), unreachableId, "{\"endpointId\":\"" + UNREACHABLE_ENDPOINT_ID + "\"}")
                        .status())
                .isEqualTo(200);
        JsonNode networkFailure = json(testConnector(fixture.admin(), unreachableId), 200);
        assertThat(networkFailure.path("success").asBoolean()).isFalse();
        assertThat(networkFailure.path("category").asText()).isEqualTo("NETWORK");
        assertThat(networkFailure.path("message").asText()).doesNotContain("127.0.0.1");
    }

    @Test
    @DisplayName("凭据请求校验：缺字段/未知字段 400")
    void credentialValidation() {
        ConnectorFixture fixture = connectorFixture();
        JsonNode created = json(
                createConnector(
                        fixture.admin(),
                        "conn-" + UUID.randomUUID().toString().substring(0, 8),
                        "TENANT",
                        null,
                        false,
                        null),
                201);
        String connectorId = created.path("id").asText();
        ApiTestClient.ApiResponse missing = client.put(
                "/api/v1/knowledge/connectors/" + connectorId + "/credential",
                "{\"accessKeyId\":\"only\"}",
                fixture.admin().sessionCookie(),
                fixture.admin().csrfToken());
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.body()).contains("RICCON-KB-400");
        ApiTestClient.ApiResponse unknown = client.put(
                "/api/v1/knowledge/connectors/" + connectorId + "/credential",
                "{\"accessKeyId\":\"a\",\"secretAccessKey\":\"b\",\"sessionToken\":\"c\"}",
                fixture.admin().sessionCookie(),
                fixture.admin().csrfToken());
        assertThat(unknown.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("同步：202、并发 409、outbox 事件与 started 审计")
    void sync_acceptedAndConcurrentConflict() {
        ConnectorFixture fixture = connectorFixture();
        JsonNode created = json(
                createConnector(
                        fixture.admin(),
                        "conn-" + UUID.randomUUID().toString().substring(0, 8),
                        "TENANT",
                        null,
                        false,
                        Credential.valid()),
                201);
        String connectorId = created.path("id").asText();

        ApiTestClient.ApiResponse accepted = syncConnector(fixture.admin(), connectorId);
        assertThat(accepted.status()).isEqualTo(202);
        JsonNode body = client.json(accepted);
        assertThat(body.path("syncId").asText()).isNotBlank();
        assertThat(body.path("connectorId").asText()).isEqualTo(connectorId);
        assertThat(body.path("status").asText()).isEqualTo("PENDING");

        ApiTestClient.ApiResponse conflict = syncConnector(fixture.admin(), connectorId);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.body()).contains("RICCON-KB-409");

        List<EventRow> events = events(fixture.tenant(), EventTypes.KNOWLEDGE_CONNECTOR_SYNC_REQUESTED);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).idempotencyKey())
                .isEqualTo("connector:" + connectorId + ":sync:"
                        + body.path("syncId").asText());
        JsonNode payload = JSON.readTree(events.get(0).payload());
        assertThat(payload.path("connectorId").asText()).isEqualTo(connectorId);
        assertThat(payload.path("syncId").asText())
                .isEqualTo(body.path("syncId").asText());
        assertThat(payload.path("startedAt").asText()).isNotBlank();
        assertThat(payload.toString()).doesNotContain(BUCKET);

        assertThat(connectorRow(fixture.tenant(), connectorId).get("last_sync_started_at"))
                .isNotNull();
        JsonNode startedDetails = auditRows("knowledge.connector.sync.started").stream()
                .filter(entry -> entry.tenantId().equals(fixture.tenant()))
                .map(entry -> JSON.readTree(entry.details()))
                .findFirst()
                .orElseThrow();
        assertThat(startedDetails.path("trigger").asText()).isEqualTo("MANUAL");
        assertThat(startedDetails.path("connectorId").asText()).isEqualTo(connectorId);
    }
}
