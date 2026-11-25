package com.riccon.api.workflow.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 审批端点保留期与待办批量聚合测试（REV-TASK-0229-001 F-001/F-003）。
 *
 * <p>验证审批待办、详情与决定都应用运行 {@code retention_expires_at}：到期运行上的审批不再可见，
 * 也不能被恢复；同时验证一页待办跨多个运行时 {@code dataScopeSummary.documentReferenceCount} 仍按
 * 每个审批的创建时间正确计数，代码路径为一次批量查询而非逐项 N+1。
 */
class WorkflowRunHistoryRetentionApiTest extends WorkflowHistoryTestSupport {

    @Test
    @DisplayName("IT-WF-107 到期运行的审批从待办/详情/决定统一不可见，且不写决定审计")
    void approvalEndpoints_hideExpiredRun() {
        Fixture fixture = fixture("whl");
        String workflowId = publishHistoryWorkflow(fixture, WORKFLOW_NAME);
        UUID approvalId = seedAwaitingApproval(
                fixture,
                workflowId,
                fixture.ownerId(),
                "ROLE",
                DEFAULT_APPROVER_ROLE,
                null,
                Instant.now().minusSeconds(20));
        UUID runId = approvalRunId(fixture.tenant(), approvalId);

        JsonNode inbox = client.json(listApprovals(fixture.otherEditor(), null));
        assertThat(inbox.path("total").asLong()).isEqualTo(1);
        assertThat(getApproval(fixture.otherEditor(), approvalId).status()).isEqualTo(200);

        setRetention(fixture.tenant(), runId, Instant.now().minusSeconds(5));

        JsonNode expiredInbox = client.json(listApprovals(fixture.otherEditor(), null));
        assertThat(expiredInbox.path("total").asLong()).isZero();
        assertThat(expiredInbox.path("items")).isEmpty();
        assertProblem(getApproval(fixture.otherEditor(), approvalId), 404, TENANT_404);
        assertProblem(
                decideApproval(fixture.otherEditor(), approvalId, "APPROVE", "同意", "expired-decision-001"),
                404,
                TENANT_404);
        assertThat(runStatus(fixture.tenant(), runId)).isEqualTo("WAITING_APPROVAL");
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.approval.decided"))
                .isEmpty();
    }

    @Test
    @DisplayName("IT-WF-108 待办跨运行批量计算 documentReferenceCount，按审批创建时间过滤上游引用")
    void inbox_documentReferenceCount_isBatchCorrect() {
        Fixture fixture = fixture("whl");
        String workflowId = publishHistoryWorkflow(fixture, WORKFLOW_NAME);
        Instant base = Instant.now().minusSeconds(120);

        UUID firstApproval = seedApprovalWithCitations(fixture, workflowId, base, 1);
        UUID secondApproval = seedApprovalWithCitations(fixture, workflowId, base.plusSeconds(10), 2);
        UUID thirdApproval = seedApprovalWithCitations(fixture, workflowId, base.plusSeconds(20), 3);

        ApiTestClient.ApiResponse response = listApprovals(fixture.otherEditor(), "size=10");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode page = client.json(response);
        assertThat(page.path("total").asLong()).isEqualTo(3);
        assertThat(page.path("items")).hasSize(3);
        assertThat(referenceCount(page, firstApproval)).isEqualTo(1);
        assertThat(referenceCount(page, secondApproval)).isEqualTo(2);
        assertThat(referenceCount(page, thirdApproval)).isEqualTo(3);
        // 待办按 created_at 升序，最早创建的审批在最前。
        assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(firstApproval.toString());
    }

    /**
     * 构造一个停在审批节点的运行，并在审批创建前写入指定数量的去重引用。
     *
     * @param fixture 测试夹具
     * @param workflowId 流程 ID
     * @param approvalCreatedAt 审批创建时间
     * @param documentCount 上游引用数量
     * @return 审批 ID
     */
    private UUID seedApprovalWithCitations(
            Fixture fixture, String workflowId, Instant approvalCreatedAt, int documentCount) {
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID runId = insertActiveRun(
                fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "WAITING_APPROVAL", APPROVAL_NODE);
        StringBuilder citations = new StringBuilder("[");
        for (int index = 0; index < documentCount; index++) {
            if (index > 0) {
                citations.append(',');
            }
            UUID documentId =
                    seedKnowledgeDocument(fixture.tenant(), "引用文档-" + index, "checksum-" + runId + "-" + index, false);
            citations.append("""
                    {"ordinal":%d,"sourceType":"KNOWLEDGE","knowledgeDocumentId":"%s",
                     "locatorType":"PAGE","pageNo":1,"title":"引用文档-%d","snippet":"片段",
                     "score":0.8,"sourceVersion":"checksum-%s-%d"}
                    """.formatted(index + 1, documentId, index, runId, index));
        }
        citations.append(']');
        insertRunNode(
                fixture.tenant(),
                runId,
                "retrieval",
                "RETRIEVAL",
                "检索知识库",
                1,
                "SUCCEEDED",
                "检索查询：批量计数",
                "检索命中 " + documentCount + " 条",
                citations.toString(),
                null,
                approvalCreatedAt.minusSeconds(2),
                approvalCreatedAt.minusSeconds(1),
                null,
                null);
        insertRunNode(
                fixture.tenant(),
                runId,
                APPROVAL_NODE,
                "APPROVAL",
                "审批取数",
                1,
                "WAITING_APPROVAL",
                "审批取数",
                null,
                null,
                null,
                approvalCreatedAt,
                null,
                null,
                null);
        return insertApproval(
                fixture.tenant(),
                runId,
                workflowId,
                APPROVAL_NODE,
                "ROLE",
                DEFAULT_APPROVER_ROLE,
                null,
                fixture.ownerId(),
                approvalCreatedAt);
    }

    /**
     * 读取待办条目的文档引用计数。
     *
     * @param page 待办分页
     * @param approvalId 审批 ID
     * @return 计数
     */
    private static int referenceCount(JsonNode page, UUID approvalId) {
        for (JsonNode item : page.path("items")) {
            if (item.path("id").asText().equals(approvalId.toString())) {
                return item.path("dataScopeSummary")
                        .path("documentReferenceCount")
                        .asInt();
            }
        }
        throw new AssertionError("approval not found in inbox: " + approvalId);
    }
}
