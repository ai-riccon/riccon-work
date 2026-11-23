package com.riccon.api.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.domain.audit.AuditActions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M05 删除与重索引：软删除、审计、索引 DELETE、幂等与 409/403/404 矩阵。
 */
@TestPropertySource(properties = "riccon.kb.acl.enforcement-enabled=true")
class KnowledgeDeleteReindexApiTest extends KnowledgeAclTestSupport {

    /** JSON 解析。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 删除软删除并写审计/索引 DELETE；重复删除幂等。 */
    @Test
    @DisplayName("删除：软删除、reason=USER、索引 DELETE 与幂等")
    void delete_softDeletesAuditsAndIsIdempotent() {
        TenantFixture fixture = tenantFixture();
        JsonNode document = createDocument(fixture, "待删除", null, null, null);
        String documentId = document.path("id").asText();

        ApiTestClient.ApiResponse deleted = client.delete(
                "/api/v1/knowledge/documents/" + documentId,
                fixture.admin().sessionCookie(),
                fixture.admin().csrfToken());
        assertThat(deleted.status()).as(deleted.body()).isEqualTo(204);

        Map<String, Object> row = documentRow(fixture.tenant(), documentId);
        assertThat(row.get("status")).isEqualTo("DELETED");
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(getDocument(fixture.admin(), documentId).status()).isEqualTo(404);
        assertThat(documentIds(listDocuments(fixture.admin()))).doesNotContain(documentId);

        List<AuditRow> audits = documentAudit(AuditActions.KNOWLEDGE_DOCUMENT_DELETED, fixture.tenant(), documentId);
        assertThat(audits).hasSize(1);
        JsonNode details = JSON.readTree(audits.get(0).details());
        assertThat(details.path("reason").asText()).isEqualTo("USER");
        assertThat(details.path("visibility").asText()).isEqualTo("TENANT");
        assertThat(details.path("sourceType").asText()).isEqualTo("UPLOAD");

        List<OutboxRow> deleteEvents = documentEvents(fixture.tenant(), documentId).stream()
                .filter(event -> event.idempotencyKey().contains(":delete:"))
                .toList();
        assertThat(deleteEvents).hasSize(1);
        assertThat(deleteEvents.get(0).idempotencyKey()).isEqualTo("doc:" + documentId + ":delete:1");
        JsonNode deletePayload = JSON.readTree(deleteEvents.get(0).payload());
        assertThat(deletePayload.path("mode").asText()).isEqualTo("DELETE");
        assertThat(deletePayload.path("reason").asText()).isEqualTo("USER");

        ApiTestClient.ApiResponse repeated = client.delete(
                "/api/v1/knowledge/documents/" + documentId,
                fixture.admin().sessionCookie(),
                fixture.admin().csrfToken());
        assertThat(repeated.status()).isEqualTo(204);
        assertThat(documentAudit(AuditActions.KNOWLEDGE_DOCUMENT_DELETED, fixture.tenant(), documentId))
                .hasSize(1);
        assertThat(documentEvents(fixture.tenant(), documentId).stream()
                        .filter(event -> event.idempotencyKey().contains(":delete:"))
                        .count())
                .isEqualTo(1);
    }

    /** 删除的 403/404 矩阵：不可见 404、可见非创建人 403、跨租户 404。 */
    @Test
    @DisplayName("删除权限矩阵")
    void delete_permissionMatrix() {
        TenantFixture fixture = tenantFixture();
        TenantFixture foreign = tenantFixture();
        LoginSession outsider = seedLogin(fixture.platform(), fixture.tenant(), "out", List.of("MEMBER"));
        JsonNode tenantDoc = createDocument(fixture, "租户文档", "TENANT", null, null);
        JsonNode usersDoc = createDocument(fixture, "用户文档", "USERS", null, List.of(actorId(fixture.member())));

        assertThat(delete(outsider, usersDoc.path("id").asText()).status()).isEqualTo(404);
        assertThat(delete(fixture.member(), usersDoc.path("id").asText()).status())
                .isEqualTo(403);
        assertThat(delete(fixture.member(), tenantDoc.path("id").asText()).status())
                .isEqualTo(403);
        assertThat(delete(foreign.admin(), tenantDoc.path("id").asText()).status())
                .isEqualTo(404);
        assertThat(documentAudit(
                        AuditActions.KNOWLEDGE_DOCUMENT_DELETED,
                        fixture.tenant(),
                        tenantDoc.path("id").asText()))
                .isEmpty();
    }

    /** 重索引：force、幂等、409 状态冲突。 */
    @Test
    @DisplayName("重索引：force、幂等与 409")
    void reindex_forceIdempotentAndConflicts() {
        TenantFixture fixture = tenantFixture();
        JsonNode document = createDocument(fixture, "重索引", null, null, null);
        String documentId = document.path("id").asText();
        markIndexEventDone(fixture.tenant(), documentId);

        ApiTestClient.ApiResponse accepted = reindex(fixture.admin(), documentId, null);
        assertThat(accepted.status()).as(accepted.body()).isEqualTo(202);
        OutboxRow defaultEvent = eventWithKey(fixture.tenant(), documentId, "doc:" + documentId + ":index:2");
        JsonNode defaultPayload = JSON.readTree(defaultEvent.payload());
        assertThat(defaultPayload.path("force").asBoolean()).isFalse();
        assertThat(defaultPayload.path("mode").asText()).isEqualTo("FULL");
        assertThat(defaultPayload.path("reason").asText()).isEqualTo("REINDEX");
        List<AuditRow> firstAudit =
                documentAudit(AuditActions.KNOWLEDGE_DOCUMENT_REINDEXED, fixture.tenant(), documentId);
        assertThat(firstAudit).hasSize(1);
        JsonNode firstDetails = JSON.readTree(firstAudit.get(0).details());
        assertThat(firstDetails.path("force").asBoolean()).isFalse();
        assertThat(firstDetails.path("status").asText()).isEqualTo("PENDING");

        // 已有 PENDING 事件时再次重索引 -> 409，不重复投递。
        ApiTestClient.ApiResponse duplicate = reindex(fixture.admin(), documentId, null);
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(client.json(duplicate).path("code").asText()).isEqualTo("RICCON-KB-409");

        // 让上一次事件完成后用 force=true 整本重做。
        markIndexEventDone(fixture.tenant(), documentId);
        updateStatus(fixture.tenant(), documentId, "FAILED");
        ApiTestClient.ApiResponse forced = reindex(fixture.admin(), documentId, "true");
        assertThat(forced.status()).as(forced.body()).isEqualTo(202);
        OutboxRow forceEvent = eventWithKey(fixture.tenant(), documentId, "doc:" + documentId + ":index:3");
        assertThat(JSON.readTree(forceEvent.payload()).path("force").asBoolean())
                .isTrue();

        // 正在索引 -> 409。
        markIndexEventDone(fixture.tenant(), documentId);
        updateStatus(fixture.tenant(), documentId, "INDEXING");
        ApiTestClient.ApiResponse indexing = reindex(fixture.admin(), documentId, null);
        assertThat(indexing.status()).isEqualTo(409);

        // 已软删除 -> 409（管理员仍可见已删除文档的能力判定路径）。
        updateStatus(fixture.tenant(), documentId, "INDEXED");
        assertThat(delete(fixture.admin(), documentId).status()).isEqualTo(204);
        assertThat(reindex(fixture.admin(), documentId, null).status()).isEqualTo(409);

        // 非法 force -> 400 RICCON-KB-400。
        JsonNode another = createDocument(fixture, "非法 force", null, null, null);
        ApiTestClient.ApiResponse invalid =
                reindex(fixture.admin(), another.path("id").asText(), "maybe");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(client.json(invalid).path("code").asText()).isEqualTo("RICCON-KB-400");
    }

    /** 重索引的 403/404 矩阵与跨租户。 */
    @Test
    @DisplayName("重索引权限矩阵")
    void reindex_permissionMatrix() {
        TenantFixture fixture = tenantFixture();
        TenantFixture foreign = tenantFixture();
        LoginSession outsider = seedLogin(fixture.platform(), fixture.tenant(), "out", List.of("MEMBER"));
        JsonNode tenantDoc = createDocument(fixture, "租户文档", "TENANT", null, null);
        JsonNode usersDoc = createDocument(fixture, "用户文档", "USERS", null, List.of(actorId(fixture.member())));
        markIndexEventDone(fixture.tenant(), tenantDoc.path("id").asText());
        markIndexEventDone(fixture.tenant(), usersDoc.path("id").asText());

        assertThat(reindex(fixture.member(), tenantDoc.path("id").asText(), null)
                        .status())
                .isEqualTo(403);
        assertThat(reindex(outsider, usersDoc.path("id").asText(), null).status())
                .isEqualTo(404);
        assertThat(reindex(foreign.admin(), tenantDoc.path("id").asText(), null).status())
                .isEqualTo(404);
        assertThat(reindex(fixture.platform(), tenantDoc.path("id").asText(), null)
                        .status())
                .isEqualTo(404);
    }

    /**
     * 删除请求。
     *
     * @param session 会话
     * @param documentId 文档 ID
     * @return 响应
     */
    private ApiTestClient.ApiResponse delete(LoginSession session, String documentId) {
        return client.delete("/api/v1/knowledge/documents/" + documentId, session.sessionCookie(), session.csrfToken());
    }

    /**
     * 重索引请求。
     *
     * @param session 会话
     * @param documentId 文档 ID
     * @param force force 参数
     * @return 响应
     */
    private ApiTestClient.ApiResponse reindex(LoginSession session, String documentId, String force) {
        String path =
                "/api/v1/knowledge/documents/" + documentId + "/reindex" + (force == null ? "" : "?force=" + force);
        return client.post(path, client.jsonBody(), session.sessionCookie(), session.csrfToken(), Map.of());
    }

    /**
     * 把文档全部索引事件标记为 DONE，模拟 Worker 已处理。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     */
    private void markIndexEventDone(String tenant, String documentId) {
        jdbcClient
                .sql("""
                        UPDATE agent_run_outbox SET status = 'DONE'
                         WHERE tenant_id = :tenantId
                           AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.documentId')) = :documentId
                           AND status IN ('PENDING', 'DISPATCHED')
                        """)
                .param("tenantId", tenant)
                .param("documentId", documentId)
                .update();
    }

    /**
     * 更新文档状态。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @param status 状态
     */
    private void updateStatus(String tenant, String documentId, String status) {
        jdbcClient
                .sql("""
                        UPDATE knowledge_document SET status = :status
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("status", status)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(UUID.fromString(documentId)))
                .update();
    }

    /**
     * 按幂等键查询事件。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @param idempotencyKey 幂等键
     * @return 事件
     */
    private OutboxRow eventWithKey(String tenant, String documentId, String idempotencyKey) {
        return documentEvents(tenant, documentId).stream()
                .filter(event -> idempotencyKey.equals(event.idempotencyKey()))
                .findFirst()
                .orElseThrow();
    }

    /**
     * 抽取列表文档 ID。
     *
     * @param response 列表响应
     * @return 文档 ID
     */
    private List<String> documentIds(ApiTestClient.ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode item : client.json(response).path("items")) {
            ids.add(item.path("id").asText());
        }
        return ids;
    }
}
