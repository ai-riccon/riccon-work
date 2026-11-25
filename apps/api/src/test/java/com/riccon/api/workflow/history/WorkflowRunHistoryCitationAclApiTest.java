package com.riccon.api.workflow.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * 运行详情引用 ACL 遮蔽测试（AC-13/TM-76）。
 *
 * <p>独立开启文档级 ACL，验证运行详情对无权限文档返回 {@code sourceStatus=NO_ACCESS}，标题/片段为空、
 * 定位为空，且响应不包含文档标题或正文。默认测试配置关闭 ACL，无法覆盖该分支，因此单独成类；
 * 仅新增 Spring 测试属性，不改生产配置。
 */
@TestPropertySource(properties = "riccon.kb.acl.enforcement-enabled=true")
class WorkflowRunHistoryCitationAclApiTest extends WorkflowHistoryTestSupport {

    @Test
    @DisplayName("IT-WF-106 ACL 撤销/无权文档的引用按 NO_ACCESS 遮蔽标题、片段与定位")
    void detail_noAccessCitation_isMasked() {
        Fixture fixture = fixture("whl");
        String workflowId = publishHistoryWorkflow(fixture, WORKFLOW_NAME);
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID runId = insertActiveRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "PENDING", "end");
        UUID privateDocument = seedPrivateDocument(fixture.tenant(), "无权访问的报告", fixture.otherEditorId());
        Instant finished = Instant.now().minusSeconds(10);
        insertRunNode(
                fixture.tenant(),
                runId,
                "retrieval",
                "RETRIEVAL",
                "检索知识库",
                1,
                "SUCCEEDED",
                "检索查询：ACL",
                "检索命中 1 条",
                """
                [{"ordinal":1,"sourceType":"KNOWLEDGE","knowledgeDocumentId":"%s",
                  "locatorType":"PAGE","pageNo":2,"title":"无权访问的报告","snippet":"敏感片段",
                  "score":0.7,"sourceVersion":"checksum-private"}]
                """.formatted(privateDocument),
                null,
                finished.minusSeconds(2),
                finished,
                null,
                null);
        markRunSucceeded(fixture.tenant(), runId, """
                {"format":"TEXT","text":"汇总","sizeBytes":6,"generatedAt":"2026-11-25T10:00:00+08:00"}
                """, 100, Instant.now());

        JsonNode detail = getRunOk(fixture.owner(), runId);
        JsonNode citation = detail.path("citations").get(0);
        assertThat(citation.path("sourceStatus").asText()).isEqualTo("NO_ACCESS");
        assertThat(citation.path("title").asText()).isEmpty();
        assertThat(citation.path("snippet").asText()).isEmpty();
        assertThat(citation.path("locatorType").isMissingNode()
                        || citation.path("locatorType").isNull())
                .isTrue();
        assertThat(citation.path("pageNo").isMissingNode()
                        || citation.path("pageNo").isNull())
                .isTrue();
        assertThat(detail.toString()).doesNotContain("无权访问的报告");
        assertThat(detail.toString()).doesNotContain("敏感片段");
    }

    /**
     * 种子一条仅创建人可见的私有知识库文档。
     *
     * @param tenant 租户
     * @param title 标题
     * @param createdBy 创建人
     * @return 文档 ID
     */
    private UUID seedPrivateDocument(String tenant, String title, UUID createdBy) {
        UUID documentId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_document (
                            tenant_id, id, title, source_type, file_id, checksum, mime_type, size_bytes,
                            status, created_by, created_at, updated_at, visibility, department_id
                        ) VALUES (
                            :tenant, :id, :title, 'SEED', NULL, 'checksum-private', 'text/plain', 4,
                            'INDEXED', :createdBy, NOW(6), NOW(6), 'USERS', NULL
                        )
                        """)
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(documentId))
                .param("title", title)
                .param("createdBy", BinaryUuids.toBytes(createdBy))
                .update();
        return documentId;
    }
}
