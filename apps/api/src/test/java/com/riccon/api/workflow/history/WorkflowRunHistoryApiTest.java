package com.riccon.api.workflow.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 运行历史/详情/过滤/审批聚合/管理性读取集成测试（IT-WF-106/108，AC-12/13/15/16）。
 *
 * <p>覆盖：列表可见性（发起人、流程所有者元数据、租户管理员全部）、倒序/分页/过滤、详情仅发起人/
 * 管理员、节点时间线、引用 {@code sourceStatus} 与遮蔽、审批聚合/counts、管理性读取审计、失败节点
 * 重试入口，以及不泄露输入/提示词/完整参数/凭据/节点内部输出。
 */
class WorkflowRunHistoryApiTest extends WorkflowHistoryTestSupport {

    @Test
    @DisplayName("IT-WF-108 列表可见性：发起人、流程所有者元数据、管理员全部，按创建时间倒序")
    void listRuns_visibilityMatrix_andDescendingOrder() {
        Fixture fixture = fixture("whl");
        String ownerWorkflow = publishHistoryWorkflow(fixture, WORKFLOW_NAME);
        UUID ownerVersion = publishedVersionId(fixture.tenant(), ownerWorkflow);
        UUID ownerRun = insertActiveRun(
                fixture.tenant(), ownerWorkflow, ownerVersion, 1, fixture.ownerId(), "SUCCEEDED", "end");
        UUID editorRun = insertActiveRun(
                fixture.tenant(), ownerWorkflow, ownerVersion, 1, fixture.otherEditorId(), "FAILED", "draft");
        String editorWorkflow = publishHistoryWorkflow(fixture.otherEditor(), fixture, "其他编辑者流程");
        UUID editorVersion = publishedVersionId(fixture.tenant(), editorWorkflow);
        UUID memberRun = insertActiveRun(
                fixture.tenant(), editorWorkflow, editorVersion, 1, fixture.memberId(), "PENDING", "start");

        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);
        setCreatedAt(fixture.tenant(), ownerRun, base.minusSeconds(300));
        setCreatedAt(fixture.tenant(), editorRun, base.minusSeconds(200));
        setCreatedAt(fixture.tenant(), memberRun, base.minusSeconds(100));

        JsonNode ownerPage = listRunsOk(fixture.owner(), null);
        assertThat(ids(ownerPage)).containsExactly(editorRun.toString(), ownerRun.toString());
        assertThat(ids(ownerPage)).doesNotContain(memberRun.toString());
        assertThat(ownerPage.path("total").asLong()).isEqualTo(2);

        JsonNode editorPage = listRunsOk(fixture.otherEditor(), null);
        assertThat(ids(editorPage)).containsExactly(memberRun.toString(), editorRun.toString());

        JsonNode memberPage = listRunsOk(fixture.member(), null);
        assertThat(ids(memberPage)).containsExactly(memberRun.toString());

        JsonNode adminPage = listRunsOk(fixture.tenantAdmin(), null);
        assertThat(ids(adminPage)).containsExactly(memberRun.toString(), editorRun.toString(), ownerRun.toString());

        JsonNode first = adminPage.path("items").get(0);
        assertThat(first.path("id").asText()).isEqualTo(memberRun.toString());
        assertThat(first.path("workflowName").asText()).isEqualTo("其他编辑者流程");
        assertThat(first.path("status").asText()).isEqualTo("PENDING");
        assertThat(first.path("resultStatus").asText()).isEqualTo("NONE");
        assertThat(first.path("requesterUserId").asText())
                .isEqualTo(fixture.memberId().toString());
        assertThat(first.path("durationMs").asLong()).isZero();
        assertThat(first.path("pendingApprovalCount").asInt()).isZero();
        assertThat(first.has("approvalStatus")).isFalse();
        assertThat(first.has("result")).isFalse();
        assertThat(first.path("retentionExpiresAt").asText()).isNotEmpty();
    }

    @Test
    @DisplayName("IT-WF-108 历史过滤：流程/版本/状态/发起人/时间与分页，非法参数 400")
    void listRuns_filtersAndPagination() {
        Fixture fixture = fixture("whl");
        String workflowId = publishHistoryWorkflow(fixture, WORKFLOW_NAME);
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID first = insertActiveRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "SUCCEEDED", "end");
        UUID second =
                insertActiveRun(fixture.tenant(), workflowId, versionId, 1, fixture.otherEditorId(), "FAILED", "draft");
        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);
        setCreatedAt(fixture.tenant(), first, base.minusSeconds(200));
        setCreatedAt(fixture.tenant(), second, base.minusSeconds(100));

        assertThat(ids(listRunsOk(fixture.tenantAdmin(), "workflowId=" + workflowId)))
                .containsExactly(second.toString(), first.toString());
        assertThat(ids(listRunsOk(fixture.tenantAdmin(), "status=FAILED"))).containsExactly(second.toString());
        assertThat(ids(listRunsOk(fixture.tenantAdmin(), "requesterUserId=" + fixture.otherEditorId())))
                .containsExactly(second.toString());
        assertThat(ids(listRunsOk(fixture.tenantAdmin(), "versionNo=1"))).hasSize(2);
        assertThat(ids(listRunsOk(fixture.tenantAdmin(), "versionNo=2"))).isEmpty();

        String from = base.minusSeconds(150).toString();
        String to = base.minusSeconds(50).toString();
        assertThat(ids(listRunsOk(fixture.tenantAdmin(), "from=" + from + "&to=" + to)))
                .containsExactly(second.toString());

        JsonNode pageTwo = listRunsOk(fixture.tenantAdmin(), "page=2&size=1");
        assertThat(pageTwo.path("page").asInt()).isEqualTo(2);
        assertThat(pageTwo.path("size").asInt()).isEqualTo(1);
        assertThat(pageTwo.path("total").asLong()).isEqualTo(2);
        assertThat(pageTwo.path("totalPages").asInt()).isEqualTo(2);
        assertThat(ids(pageTwo)).containsExactly(first.toString());

        assertProblem(listRuns(fixture.tenantAdmin(), "from=" + to + "&to=" + from), 400, WF_400);
        assertProblem(listRuns(fixture.tenantAdmin(), "from=2026-01-01T00:00:00"), 400, WF_400);
        assertProblem(listRuns(fixture.tenantAdmin(), "versionNo=0"), 400, WF_400);
        assertProblem(listRuns(fixture.tenantAdmin(), "workflowId=not-a-uuid"), 400, WF_400);
        assertThat(listRuns(fixture.tenantAdmin(), "page=0").status()).isEqualTo(400);
        assertThat(listRuns(fixture.tenantAdmin(), "size=101").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("IT-WF-107 到期运行在列表与详情统一不可见，管理员也不能读取")
    void expiredRun_isInvisibleEverywhere() {
        Fixture fixture = fixture("whl");
        String workflowId = publishHistoryWorkflow(fixture, WORKFLOW_NAME);
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID runId = insertActiveRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "SUCCEEDED", "end");
        setRetention(fixture.tenant(), runId, Instant.now().minusSeconds(60));

        assertThat(ids(listRunsOk(fixture.owner(), null))).isEmpty();
        assertThat(ids(listRunsOk(fixture.tenantAdmin(), null))).isEmpty();
        assertProblem(getRun(fixture.owner(), runId), 404, TENANT_404);
        assertProblem(getRun(fixture.tenantAdmin(), runId), 404, TENANT_404);
        List<AuditRow> adminAudits = tenantAuditRows(fixture.tenant(), "workflow.run.admin_read");
        assertThat(adminAudits).hasSize(1);
        assertThat(auditDetails(adminAudits.getFirst()).path("scope").asText()).isEqualTo("LIST");
    }

    @Test
    @DisplayName("IT-WF-108 详情仅发起人与租户管理员；流程所有者、同租户他人、跨租户与平台 404")
    void detail_visibilityMatrix() {
        Fixture fixture = fixture("whl");
        String workflowId = publishHistoryWorkflow(fixture, WORKFLOW_NAME);
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID runId = insertActiveRun(
                fixture.tenant(), workflowId, versionId, 1, fixture.otherEditorId(), "SUCCEEDED", "end");

        assertProblem(getRun(fixture.owner(), runId), 404, TENANT_404);
        assertProblem(getRun(fixture.member(), runId), 404, TENANT_404);
        assertProblem(getRun(fixture.platform(), runId), 404, TENANT_404);
        assertThat(getRun(fixture.otherEditor(), runId).status()).isEqualTo(200);
        assertThat(getRun(fixture.tenantAdmin(), runId).status()).isEqualTo(200);

        Fixture other = fixture("whl");
        assertProblem(getRun(other.tenantAdmin(), runId), 404, TENANT_404);
    }

    @Test
    @DisplayName("IT-WF-106 详情返回节点时间线、引用 sourceStatus、结果，且不泄露输入/提示词/内部输出")
    void detail_successRun_citationsAndResult_noLeak() {
        Fixture fixture = fixture("whl");
        String workflowId = publishHistoryWorkflow(fixture, WORKFLOW_NAME);
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID runId = insertActiveRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "PENDING", "end");
        jdbcClient
                .sql("UPDATE workflow_run SET inputs_json = '{\"topic\":\"SECRET-INPUT-MARKER\"}'"
                        + " WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", fixture.tenant())
                .param("id", BinaryUuids.toBytes(runId))
                .update();
        Instant finished = Instant.now().minusSeconds(30);
        UUID availableDocument = seedKnowledgeDocument(fixture.tenant(), "季度报告", "checksum-a", false);
        UUID updatedDocument = seedKnowledgeDocument(fixture.tenant(), "更新报告", "checksum-new", false);
        UUID deletedDocument = seedKnowledgeDocument(fixture.tenant(), "已删除报告", "checksum-b", true);
        String firstCitations = citations(
                citation(1, "KNOWLEDGE", availableDocument, "checksum-a", "AVAILABLE 标题", "片段一", "PAGE", 3),
                citation(2, "KNOWLEDGE", updatedDocument, "checksum-old", "更新前标题", "片段二", "PAGE", 5),
                citation(3, "KNOWLEDGE", deletedDocument, "checksum-b", "删除标题", "片段三", "PAGE", 7));
        String secondCitations =
                citations(citation(1, "KNOWLEDGE", availableDocument, "checksum-a", "AVAILABLE 标题", "片段一", "PAGE", 3));
        insertRunNode(
                fixture.tenant(),
                runId,
                "retrieval",
                "RETRIEVAL",
                "检索知识库",
                1,
                "SUCCEEDED",
                "检索查询：市场调研",
                "检索命中 3 条",
                firstCitations,
                null,
                finished.minusSeconds(2),
                finished,
                null,
                null);
        insertRunNode(
                fixture.tenant(),
                runId,
                "reretrieval",
                "RETRIEVAL",
                "二次检索",
                1,
                "SUCCEEDED",
                "检索查询：补充",
                "检索命中 1 条",
                secondCitations,
                null,
                finished.minusSeconds(1),
                finished.plusSeconds(1),
                null,
                null);
        markRunSucceeded(fixture.tenant(), runId, """
                {"format":"TEXT","text":"最终市场调研报告","sizeBytes":27,
                 "generatedAt":"2026-11-25T10:00:00+08:00"}
                """, 4321, Instant.now());

        JsonNode detail = getRunOk(fixture.owner(), runId);
        assertThat(detail.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(detail.path("durationMs").asLong()).isEqualTo(4321);
        assertThat(detail.path("nodes")).hasSize(2);
        assertThat(detail.path("nodes").get(0).path("inputSummary").asText()).isEqualTo("检索查询：市场调研");
        assertThat(detail.path("nodes").get(0).path("outputSummary").asText()).isEqualTo("检索命中 3 条");
        assertThat(detail.path("nodes").get(0).path("nodeId").asText()).isEqualTo("retrieval");
        assertThat(detail.path("result").path("format").asText()).isEqualTo("TEXT");
        assertThat(detail.path("result").path("text").asText()).isEqualTo("最终市场调研报告");
        assertThat(detail.path("result").path("sizeBytes").asLong()).isEqualTo(27);
        assertThat(detail.path("result").path("generatedAt").asText()).startsWith("2026-11-25T10:00:00+08:00");

        JsonNode citations = detail.path("citations");
        assertThat(citations).hasSize(3);
        assertThat(citations.get(0).path("ordinal").asInt()).isEqualTo(1);
        assertThat(citations.get(0).path("sourceStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(citations.get(0).path("title").asText()).isEqualTo("AVAILABLE 标题");
        assertThat(citations.get(1).path("sourceStatus").asText()).isEqualTo("UPDATED");
        assertThat(citations.get(1).path("title").asText()).isEqualTo("更新前标题");
        assertThat(citations.get(2).path("sourceStatus").asText()).isEqualTo("DELETED");
        assertThat(citations.get(2).path("title").asText()).isEmpty();
        assertThat(citations.get(2).path("snippet").asText()).isEmpty();
        assertThat(citations.get(2).path("locatorType").isMissingNode()
                        || citations.get(2).path("locatorType").isNull())
                .isTrue();
        assertThat(citations.get(2).path("pageNo").isMissingNode()
                        || citations.get(2).path("pageNo").isNull())
                .isTrue();

        String body = detail.toString();
        assertThat(body).doesNotContain("SECRET-INPUT-MARKER");
        assertThat(body).doesNotContain("SECRET-OUTPUT-MARKER");
        assertThat(body).doesNotContain("ciphertext");
        assertThat(body).doesNotContain("prompt");
    }

    @Test
    @DisplayName("IT-WF-106 审批记录只对 assignee 返回脱敏参数摘要；管理员非 assignee 不获得")
    void detail_approvalPreview_onlyForAssignee() {
        Fixture fixture = fixture("whl");
        String workflowId = publishHistoryWorkflow(fixture, WORKFLOW_NAME);
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID runId = insertActiveRun(
                fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "WAITING_APPROVAL", APPROVAL_NODE);
        Instant createdAt = Instant.now().minusSeconds(10);
        UUID availableDocument = seedKnowledgeDocument(fixture.tenant(), "审批引用", "checksum-c", false);
        insertRunNode(
                fixture.tenant(),
                runId,
                "retrieval",
                "RETRIEVAL",
                "检索知识库",
                1,
                "SUCCEEDED",
                "检索查询：审批",
                "检索命中 1 条",
                citations(citation(1, "KNOWLEDGE", availableDocument, "checksum-c", "审批引用", "片段", "PAGE", 1)),
                null,
                createdAt.minusSeconds(3),
                createdAt.minusSeconds(1),
                null,
                null);
        UUID approvalId = insertApproval(
                fixture.tenant(),
                runId,
                workflowId,
                APPROVAL_NODE,
                "ROLE",
                "TENANT_ADMIN",
                null,
                fixture.ownerId(),
                createdAt);
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
                createdAt,
                null,
                approvalId,
                null);

        JsonNode requesterDetail = getRunOk(fixture.owner(), runId);
        JsonNode requesterApproval = requesterDetail.path("approvals").get(0);
        assertThat(requesterApproval.path("id").asText()).isEqualTo(approvalId.toString());
        assertThat(requesterApproval.path("status").asText()).isEqualTo("PENDING");
        assertThat(requesterApproval
                        .path("dataScopeSummary")
                        .path("documentReferenceCount")
                        .asInt())
                .isEqualTo(1);
        assertThat(requesterApproval.has("paramsPreview")).isFalse();
        assertThat(requesterApproval.path("nodeId").asText()).isEqualTo(APPROVAL_NODE);

        JsonNode adminDetail = getRunOk(fixture.tenantAdmin(), runId);
        JsonNode adminApproval = adminDetail.path("approvals").get(0);
        assertThat(adminApproval.path("paramsPreview").path("query").asText()).isEqualTo("已脱敏参数");
        assertThat(adminApproval.path("paramsPreview").path("limit").asInt()).isEqualTo(5);
        assertThat(adminApproval.toString()).doesNotContain("ciphertext");
        assertThat(adminDetail.toString()).doesNotContain("SECRET-OUTPUT-MARKER");
    }

    @Test
    @DisplayName("IT-WF-108 审批聚合：PENDING 优先、REJECTED 次之、APPROVED 再次、空为 null，counts 正确")
    void listAndDetail_approvalAggregationAndCounts() {
        Fixture fixture = fixture("whl");
        String workflowId = publishHistoryWorkflow(fixture, WORKFLOW_NAME);
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID pendingRun = insertActiveRun(
                fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "WAITING_APPROVAL", APPROVAL_NODE);
        UUID rejectedRun =
                insertActiveRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "FAILED", APPROVAL_NODE);
        UUID approvedRun = insertActiveRun(
                fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "PENDING", APPROVAL_NODE);
        UUID cancelledRun = insertActiveRun(
                fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "CANCELLED", APPROVAL_NODE);
        Instant base = Instant.now().minusSeconds(100);

        UUID pendingApproval = insertApproval(
                fixture.tenant(),
                pendingRun,
                workflowId,
                "approval",
                "ROLE",
                "TENANT_ADMIN",
                null,
                fixture.ownerId(),
                base);
        UUID approvedApproval = insertApproval(
                fixture.tenant(),
                pendingRun,
                workflowId,
                "approval2",
                "ROLE",
                "TENANT_ADMIN",
                null,
                fixture.ownerId(),
                base.plusSeconds(1));
        UUID rejectedApproval = insertApproval(
                fixture.tenant(),
                pendingRun,
                workflowId,
                "approval3",
                "ROLE",
                "TENANT_ADMIN",
                null,
                fixture.ownerId(),
                base.plusSeconds(2));
        UUID cancelledApproval = insertApproval(
                fixture.tenant(),
                pendingRun,
                workflowId,
                "approval4",
                "ROLE",
                "TENANT_ADMIN",
                null,
                fixture.ownerId(),
                base.plusSeconds(3));
        decide(fixture, approvedApproval, "APPROVED", base.plusSeconds(10));
        decide(fixture, rejectedApproval, "REJECTED", base.plusSeconds(11));
        decide(fixture, cancelledApproval, "CANCELLED", base.plusSeconds(12));

        UUID approvedOnly = insertApproval(
                fixture.tenant(),
                approvedRun,
                workflowId,
                "approval",
                "ROLE",
                "TENANT_ADMIN",
                null,
                fixture.ownerId(),
                base);
        decide(fixture, approvedOnly, "APPROVED", base.plusSeconds(10));
        UUID rejectedOnly = insertApproval(
                fixture.tenant(),
                rejectedRun,
                workflowId,
                "approval",
                "ROLE",
                "TENANT_ADMIN",
                null,
                fixture.ownerId(),
                base);
        decide(fixture, rejectedOnly, "REJECTED", base.plusSeconds(10));
        UUID cancelledOnly = insertApproval(
                fixture.tenant(),
                cancelledRun,
                workflowId,
                "approval",
                "ROLE",
                "TENANT_ADMIN",
                null,
                fixture.ownerId(),
                base);
        decide(fixture, cancelledOnly, "CANCELLED", base.plusSeconds(10));

        JsonNode page = listRunsOk(fixture.tenantAdmin(), "workflowId=" + workflowId);
        assertThat(approvalStatus(page, pendingRun)).isEqualTo("PENDING");
        assertThat(approvalStatus(page, rejectedRun)).isEqualTo("REJECTED");
        assertThat(approvalStatus(page, approvedRun)).isEqualTo("APPROVED");
        assertThat(approvalStatus(page, cancelledRun)).isNull();
        JsonNode pendingItem = item(page, pendingRun);
        assertThat(pendingItem.path("pendingApprovalCount").asInt()).isEqualTo(1);
        assertThat(pendingItem.path("approvedApprovalCount").asInt()).isEqualTo(1);
        assertThat(pendingItem.path("rejectedApprovalCount").asInt()).isEqualTo(1);

        JsonNode detail = getRunOk(fixture.tenantAdmin(), pendingRun);
        assertThat(detail.path("approvalStatus").asText()).isEqualTo("PENDING");
        assertThat(detail.path("pendingApprovalCount").asInt()).isEqualTo(1);
        assertThat(detail.path("approvedApprovalCount").asInt()).isEqualTo(1);
        assertThat(detail.path("rejectedApprovalCount").asInt()).isEqualTo(1);
        assertThat(detail.path("approvals")).hasSize(4);
        assertThat(detail.path("approvals").get(0).path("status").asText()).isEqualTo("PENDING");
        assertThat(detail.path("approvals").get(3).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(pendingApproval).isNotNull();
    }

    @Test
    @DisplayName("IT-WF-108 管理性读取审计：列表每次一条 LIST，详情仅他人运行一条 DETAIL")
    void adminReadAudit_listAndDetail() {
        Fixture fixture = fixture("whl");
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.admin_read")).isEmpty();
        listRunsOk(fixture.tenantAdmin(), null);
        List<AuditRow> listAudits = tenantAuditRows(fixture.tenant(), "workflow.run.admin_read");
        assertThat(listAudits).hasSize(1);
        JsonNode listDetails = auditDetails(listAudits.getFirst());
        assertThat(listDetails.size()).isEqualTo(2);
        assertThat(listDetails.path("scope").asText()).isEqualTo("LIST");
        assertThat(listDetails.path("reason").asText()).isEqualTo("MANAGEMENT_READ");
        assertThat(listAudits.getFirst().outcome()).isEqualTo("SUCCESS");
        assertThat(listAudits.getFirst().targetType()).isEqualTo("WORKFLOW_RUN");
        assertThat(listAudits.getFirst().targetId()).isNull();

        String workflowId = publishHistoryWorkflow(fixture, WORKFLOW_NAME);
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID othersRun =
                insertActiveRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "SUCCEEDED", "end");
        UUID adminOwnRun = insertActiveRun(
                fixture.tenant(), workflowId, versionId, 1, fixture.tenantAdminId(), "SUCCEEDED", "end");

        listRunsOk(fixture.tenantAdmin(), null);
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.admin_read")).hasSize(2);

        getRunOk(fixture.tenantAdmin(), othersRun);
        List<AuditRow> detailAudits = tenantAuditRows(fixture.tenant(), "workflow.run.admin_read");
        assertThat(detailAudits).hasSize(3);
        JsonNode detailDetails = auditDetails(detailAudits.getLast());
        assertThat(detailDetails.size()).isEqualTo(3);
        assertThat(detailDetails.path("workflowRunId").asText()).isEqualTo(othersRun.toString());
        assertThat(detailDetails.path("scope").asText()).isEqualTo("DETAIL");
        assertThat(detailDetails.path("reason").asText()).isEqualTo("MANAGEMENT_READ");
        assertThat(detailAudits.getLast().targetId()).isEqualTo(othersRun.toString());

        getRunOk(fixture.tenantAdmin(), adminOwnRun);
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.admin_read")).hasSize(3);

        getRunOk(fixture.owner(), othersRun);
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.admin_read")).hasSize(3);
    }

    @Test
    @DisplayName("IT-WF-106 失败运行详情保留失败节点与全部尝试，供 UI 重试")
    void detail_failedRun_showsFailedNodeAndAttempts() {
        Fixture fixture = fixture("whl");
        String workflowId = publishHistoryWorkflow(fixture, WORKFLOW_NAME);
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID runId = insertActiveRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "FAILED", "draft");
        Instant finished = Instant.now().minusSeconds(20);
        markRunFailed(fixture.tenant(), runId, "draft", "WF_MODEL_UNAVAILABLE", "模型不可用", finished);
        insertRunNode(
                fixture.tenant(),
                runId,
                "draft",
                "MODEL",
                "生成草稿",
                1,
                "FAILED",
                "生成市场草稿",
                "模型不可用",
                null,
                WorkflowErrorCode.WF_MODEL_UNAVAILABLE.name(),
                finished.minusSeconds(5),
                finished,
                null,
                null);
        insertRunNode(
                fixture.tenant(),
                runId,
                "draft",
                "MODEL",
                "生成草稿",
                2,
                "FAILED",
                "生成市场草稿",
                "模型不可用",
                null,
                WorkflowErrorCode.WF_MODEL_UNAVAILABLE.name(),
                finished.plusSeconds(1),
                finished.plusSeconds(2),
                null,
                null);

        JsonNode detail = getRunOk(fixture.owner(), runId);
        assertThat(detail.path("status").asText()).isEqualTo("FAILED");
        assertThat(detail.path("errorCode").asText()).isEqualTo("WF_MODEL_UNAVAILABLE");
        assertThat(detail.path("errorMessage").asText()).isEqualTo("模型不可用");
        assertThat(detail.path("currentNodeId").asText()).isEqualTo("draft");
        assertThat(detail.path("nodes")).hasSize(2);
        assertThat(detail.path("nodes").get(0).path("attempt").asInt()).isEqualTo(1);
        assertThat(detail.path("nodes").get(1).path("attempt").asInt()).isEqualTo(2);
        assertThat(detail.path("nodes").get(1).path("status").asText()).isEqualTo("FAILED");
        assertThat(detail.path("nodes").get(1).path("errorCode").asText()).isEqualTo("WF_MODEL_UNAVAILABLE");
        assertThat(detail.path("result").isMissingNode()
                        || detail.path("result").isNull())
                .isTrue();
    }

    /**
     * 构造引用 JSON 数组。
     *
     * @param citations 引用对象
     * @return JSON
     */
    private static String citations(String... citations) {
        return "[" + String.join(",", citations) + "]";
    }

    /**
     * 构造单条引用 JSON。
     *
     * @param ordinal 序号
     * @param sourceType 来源类型
     * @param documentId 文档 ID
     * @param sourceVersion 版本快照
     * @param title 标题
     * @param snippet 片段
     * @param locatorType 定位类型
     * @param pageNo 页码
     * @return JSON
     */
    private static String citation(
            int ordinal,
            String sourceType,
            UUID documentId,
            String sourceVersion,
            String title,
            String snippet,
            String locatorType,
            int pageNo) {
        return """
               {"ordinal":%d,"sourceType":"%s","knowledgeDocumentId":"%s","locatorType":"%s",
                "pageNo":%d,"title":"%s","snippet":"%s","score":0.9,"sourceVersion":"%s"}
               """.formatted(ordinal, sourceType, documentId, locatorType, pageNo, title, snippet, sourceVersion);
    }

    /**
     * 直接更新审批状态；APPROVED/REJECTED 需要审批人与时间。
     *
     * @param fixture 测试夹具
     * @param approvalId 审批 ID
     * @param status 目标状态
     * @param decidedAt 决定时间
     */
    private void decide(Fixture fixture, UUID approvalId, String status, Instant decidedAt) {
        if ("CANCELLED".equals(status)) {
            jdbcClient
                    .sql("UPDATE workflow_approval SET status = 'CANCELLED'"
                            + " WHERE tenant_id = :tenant AND id = :id")
                    .param("tenant", fixture.tenant())
                    .param("id", BinaryUuids.toBytes(approvalId))
                    .update();
            return;
        }
        jdbcClient
                .sql("""
                        UPDATE workflow_approval
                           SET status = :status,
                               decided_by = :decidedBy,
                               decided_at = :decidedAt,
                               decision_comment = :comment
                         WHERE tenant_id = :tenant AND id = :id
                        """)
                .param("status", status)
                .param("decidedBy", BinaryUuids.toBytes(fixture.otherEditorId()))
                .param("decidedAt", com.riccon.api.persistence.SqlTimestamps.toDatabase(decidedAt))
                .param("comment", "REJECTED".equals(status) ? "不同意" : "同意")
                .param("tenant", fixture.tenant())
                .param("id", BinaryUuids.toBytes(approvalId))
                .update();
    }

    /**
     * 提取分页中的运行 ID。
     *
     * @param page 分页响应
     * @return ID 字符串列表
     */
    private static List<String> ids(JsonNode page) {
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode item : page.path("items")) {
            ids.add(item.path("id").asText());
        }
        return ids;
    }

    /**
     * 按运行 ID 查找列表条目。
     *
     * @param page 分页响应
     * @param runId 运行 ID
     * @return 条目
     */
    private static JsonNode item(JsonNode page, UUID runId) {
        for (JsonNode item : page.path("items")) {
            if (item.path("id").asText().equals(runId.toString())) {
                return item;
            }
        }
        throw new AssertionError("run not found in page: " + runId);
    }

    /**
     * 读取列表条目的审批聚合状态。
     *
     * @param page 分页响应
     * @param runId 运行 ID
     * @return 状态文本；空为 {@code null}
     */
    private static String approvalStatus(JsonNode page, UUID runId) {
        JsonNode item = item(page, runId);
        return item.has("approvalStatus") ? item.path("approvalStatus").asText() : null;
    }
}
