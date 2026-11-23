package com.riccon.api.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M05 失败时间：FAILED 文档返回带 +08:00 的 failedAt，非 FAILED 为空。
 *
 * <p>来源优先使用文件解析失败的 {@code file_parse.finished_at}；没有解析失败记录时回退文档
 * {@code updated_at}。该字段不新增迁移，只使用现有列。
 */
@TestPropertySource(properties = "riccon.kb.acl.enforcement-enabled=true")
class KnowledgeFailedAtApiTest extends KnowledgeAclTestSupport {

    /** JSON 解析。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 失败时间只对 FAILED 返回，并优先使用解析失败结束时间。 */
    @Test
    @DisplayName("失败时间：FAILED 返回 +08:00，非 FAILED 为空，回退 updated_at")
    void failedAt_onlyForFailedAndFallsBackToUpdatedAt() throws Exception {
        TenantFixture fixture = tenantFixture();
        JsonNode document = createDocument(fixture, "失败时间", "TENANT", null, null);
        String documentId = document.path("id").asText();
        UUID fileId = UUID.fromString(document.path("fileId").asText());

        JsonNode active = JSON.readTree(getDocument(fixture.admin(), documentId).body());
        assertThat(active.path("status").asText()).isEqualTo("PENDING");
        assertThat(active.path("failedAt").isNull()).as("非 FAILED 不返回失败时间").isTrue();

        Instant parseFinished = Instant.parse("2026-11-23T16:02:03Z");
        UUID parseId = UuidV7.generate(parseFinished);
        jdbcClient
                .sql("""
                        INSERT INTO file_parse (tenant_id, id, file_id, attempt, status, finished_at)
                        VALUES (:tenantId, :id, :fileId, 99, 'FAILED', :finishedAt)
                        """)
                .param("tenantId", fixture.tenant())
                .param("id", BinaryUuids.toBytes(parseId))
                .param("fileId", BinaryUuids.toBytes(fileId))
                .param("finishedAt", Timestamp.from(parseFinished))
                .update();
        Instant updatedAt = Instant.parse("2026-11-23T17:00:00Z");
        markFailed(fixture.tenant(), documentId, updatedAt);

        ApiTestClient.ApiResponse failedResponse = getDocument(fixture.admin(), documentId);
        assertThat(failedResponse.status()).as(failedResponse.body()).isEqualTo(200);
        JsonNode failed = JSON.readTree(failedResponse.body());
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("failedAt").asText()).isEqualTo("2026-11-24T00:02:03+08:00");

        // 删除解析失败记录后，API 回退到文档 updated_at；仍带 +08:00 偏移。
        jdbcClient
                .sql("DELETE FROM file_parse WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", fixture.tenant())
                .param("id", BinaryUuids.toBytes(parseId))
                .update();
        JsonNode fallback =
                JSON.readTree(getDocument(fixture.admin(), documentId).body());
        assertThat(fallback.path("failedAt").asText()).isEqualTo("2026-11-24T01:00:00+08:00");
    }

    /**
     * 标记文档失败并固定 updated_at，避免依赖执行环境时钟。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @param updatedAt 失败/更新时间
     */
    private void markFailed(String tenant, String documentId, Instant updatedAt) {
        jdbcClient
                .sql("""
                        UPDATE knowledge_document
                           SET status = 'FAILED',
                               error_code = 'KB_PARSE_CORRUPT',
                               error_message = '文件损坏或格式不合法',
                               updated_at = :updatedAt
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("updatedAt", Timestamp.from(updatedAt))
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(UUID.fromString(documentId)))
                .update();
    }
}
