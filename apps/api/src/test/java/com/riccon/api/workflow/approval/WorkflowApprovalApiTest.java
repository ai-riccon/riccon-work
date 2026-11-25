package com.riccon.api.workflow.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.audit.service.AuditDetailsReader;
import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 审批待办/详情/决定 API 集成测试（IT-WF-108、SEC-WF-001；AC-05/06/15/18）。
 *
 * <p>覆盖：待办仅 assignee PENDING、created_at ASC、分页与空态；详情 dataScopeSummary 与脱敏参数、
 * 非 assignee/管理员/发起人/跨租户 404；批准恢复运行、拒绝失败与下游 SKIPPED、拒绝理由必填与 201/500
 * 字符审计可读（T01 F-001/T07a F-002）；自批/范围外 403 + denied 审计、平台与跨租户 404、无审批人
 * 不自动放行、重复/并发 409。
 */
class WorkflowApprovalApiTest extends WorkflowApprovalTestSupport {

    @Test
    @DisplayName("IT-WF-108 待办只含 assignee PENDING，按创建时间升序并分页")
    void listInbox_onlyAssignedPending_sortedAscending() {
        Fixture fixture = fixture("wap");
        String workflowId = publishApprovalWorkflow(fixture, "审批待办");
        Instant now = Instant.now();
        UUID oldest = seedAwaitingApproval(
                fixture, workflowId, fixture.ownerId(), "ROLE", DEFAULT_APPROVER_ROLE, null, now.minusSeconds(90));
        UUID newer = seedAwaitingApproval(
                fixture, workflowId, fixture.ownerId(), "ROLE", DEFAULT_APPROVER_ROLE, null, now.minusSeconds(30));
        UUID memberApproval = seedAwaitingApproval(
                fixture,
                workflowId,
                fixture.ownerId(),
                "USER",
                null,
                List.of(fixture.memberId()),
                now.minusSeconds(10));

        ApiTestClient.ApiResponse editorInbox = listApprovals(fixture.otherEditor(), null);
        assertThat(editorInbox.status()).as(editorInbox.body()).isEqualTo(200);
        JsonNode editorPage = client.json(editorInbox);
        assertThat(editorPage.path("total").asLong()).isEqualTo(2);
        assertThat(editorPage.path("items").size()).isEqualTo(2);
        assertThat(editorPage.path("items").get(0).path("id").asText()).isEqualTo(oldest.toString());
        assertThat(editorPage.path("items").get(1).path("id").asText()).isEqualTo(newer.toString());
        JsonNode item = editorPage.path("items").get(0);
        assertThat(item.path("workflowName").asText()).isEqualTo("审批待办");
        assertThat(item.path("nodeName").asText()).isEqualTo("审批取数");
        assertThat(item.path("status").asText()).isEqualTo("PENDING");
        assertThat(item.path("assigneeType").asText()).isEqualTo("ROLE");
        assertThat(item.path("assigneeRole").asText()).isEqualTo(DEFAULT_APPROVER_ROLE);
        assertThat(item.path("requestedBy").asText())
                .isEqualTo(fixture.ownerId().toString());
        assertThat(item.path("dataScopeSummary").path("runScope").asText()).isEqualTo("TENANT");
        assertThat(item.path("dataScopeSummary").path("documentReferenceCount").asInt())
                .isZero();
        assertThat(item.has("paramsPreview")).isFalse();
        assertThat(item.has("selfApprovalBlocked")).isFalse();

        JsonNode memberPage = client.json(listApprovals(fixture.member(), null));
        assertThat(memberPage.path("total").asLong()).isEqualTo(1);
        assertThat(memberPage.path("items").get(0).path("id").asText()).isEqualTo(memberApproval.toString());
        assertThat(memberPage.path("items").get(0).path("assigneeType").asText())
                .isEqualTo("USER");
        assertThat(memberPage.path("items").get(0).path("assigneeUserIds").isArray())
                .isFalse();

        ApiTestClient.ApiResponse requesterInbox = listApprovals(fixture.owner(), null);
        assertThat(client.json(requesterInbox).path("total").asLong()).isZero();
        assertThat(client.json(requesterInbox).path("items").size()).isZero();

        JsonNode adminPage = client.json(listApprovals(fixture.tenantAdmin(), null));
        assertThat(adminPage.path("total").asLong()).isZero();

        JsonNode paged = client.json(listApprovals(fixture.otherEditor(), "page=2&size=1"));
        assertThat(paged.path("total").asLong()).isEqualTo(2);
        assertThat(paged.path("totalPages").asInt()).isEqualTo(2);
        assertThat(paged.path("items").size()).isEqualTo(1);
        assertThat(paged.path("items").get(0).path("id").asText()).isEqualTo(newer.toString());

        JsonNode nonPending = client.json(listApprovals(fixture.otherEditor(), "status=APPROVED"));
        assertThat(nonPending.path("total").asLong()).isZero();
        assertThat(nonPending.path("items").size()).isZero();

        assertProblem(listApprovals(fixture.otherEditor(), "size=0"), 400, "RICCON-REQUEST-400");
    }

    @Test
    @DisplayName("IT-WF-108 详情仅 assignee 可见，返回 dataScopeSummary 与脱敏参数")
    void getDetail_assigneeOnly_withScopeSummaryAndPreview() {
        Fixture fixture = fixture("wap");
        String workflowId = publishApprovalWorkflow(fixture, "审批详情");
        UUID approvalId = seedAwaitingApproval(
                fixture,
                workflowId,
                fixture.ownerId(),
                "ROLE",
                DEFAULT_APPROVER_ROLE,
                null,
                Instant.now().minusSeconds(30));
        UUID runId = approvalRunId(fixture.tenant(), approvalId);
        String documentOne = UUID.randomUUID().toString();
        String documentTwo = UUID.randomUUID().toString();
        insertSucceededCitations(
                fixture.tenant(),
                runId,
                "[{\"knowledgeDocumentId\":\"" + documentOne + "\"},"
                        + "{\"knowledgeDocumentId\":\"" + documentOne + "\"},"
                        + "{\"fileId\":\"" + documentTwo + "\"}]",
                Instant.now().minusSeconds(40));

        ApiTestClient.ApiResponse response = getApproval(fixture.otherEditor(), approvalId);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode detail = client.json(response);
        assertThat(detail.path("id").asText()).isEqualTo(approvalId.toString());
        assertThat(detail.path("workflowId").asText()).isEqualTo(workflowId);
        assertThat(detail.path("workflowRunId").asText()).isEqualTo(runId.toString());
        assertThat(detail.path("nodeId").asText()).isEqualTo(APPROVAL_NODE);
        assertThat(detail.path("nodeName").asText()).isEqualTo("审批取数");
        assertThat(detail.path("status").asText()).isEqualTo("PENDING");
        assertThat(detail.path("actionKind").asText()).isEqualTo("MCP_TOOL");
        assertThat(detail.path("targetToolName").asText()).isEqualTo("search-mock");
        assertThat(detail.path("planSha256").asText()).isEqualTo(approvalPlanSha256(fixture.tenant(), approvalId));
        assertThat(detail.path("requestedBy").asText())
                .isEqualTo(fixture.ownerId().toString());
        assertThat(detail.path("dataScopeSummary").path("runScope").asText()).isEqualTo("TENANT");
        assertThat(detail.path("dataScopeSummary")
                        .path("documentReferenceCount")
                        .asInt())
                .isEqualTo(2);
        assertThat(detail.path("documentReferenceCount").asInt()).isEqualTo(2);
        assertThat(detail.path("selfApprovalBlocked").asBoolean()).isFalse();
        assertThat(detail.path("assigneeUserIds").isArray()).isTrue();
        assertThat(detail.path("assigneeUserIds").size()).isZero();
        JsonNode preview = detail.path("paramsPreview");
        assertThat(preview.isObject()).isTrue();
        assertThat(preview.path("query").asText()).isEqualTo("已脱敏参数");
        assertThat(preview.path("limit").asInt()).isEqualTo(5);
        assertThat(detail.toString()).doesNotContain("ciphertext", "paramsCiphertext", "prompt");

        assertProblem(getApproval(fixture.member(), approvalId), 404, APPROVAL_404);
        assertProblem(getApproval(fixture.tenantAdmin(), approvalId), 404, APPROVAL_404);
        assertProblem(getApproval(fixture.owner(), approvalId), 404, APPROVAL_404);
        assertProblem(getApproval(fixture.owner(), UUID.randomUUID()), 404, APPROVAL_404);

        Fixture other = fixture("wap");
        assertProblem(getApproval(other.otherEditor(), approvalId), 404, APPROVAL_404);
        assertProblem(getApproval(other.platform(), approvalId), 404, APPROVAL_404);
    }

    @Test
    @DisplayName("SEC-WF-001 批准恢复运行到目标节点，重复决定 409 且不重复审计")
    void approve_resumesRun_andIsNotReplayable() {
        Fixture fixture = fixture("wap");
        String workflowId = publishApprovalWorkflow(fixture, "批准运行");
        UUID approvalId = seedAwaitingApproval(
                fixture,
                workflowId,
                fixture.ownerId(),
                "ROLE",
                DEFAULT_APPROVER_ROLE,
                null,
                Instant.now().minusSeconds(20));
        UUID runId = approvalRunId(fixture.tenant(), approvalId);

        assertProblem(decideApproval(fixture.otherEditor(), approvalId, "APPROVE", "同意", null), 400, WF_400);

        ApiTestClient.ApiResponse response =
                decideApproval(fixture.otherEditor(), approvalId, "APPROVE", "同意", "decision-approve-001");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode decided = client.json(response);
        assertThat(decided.path("status").asText()).isEqualTo("APPROVED");
        assertThat(decided.path("decidedBy").asText())
                .isEqualTo(fixture.otherEditorId().toString());
        assertThat(decided.path("decisionComment").asText()).isEqualTo("同意");
        assertThat(decided.has("paramsPreview")).isFalse();

        ApprovalRow approval = approvalRow(fixture.tenant(), approvalId);
        assertThat(approval.status()).isEqualTo("APPROVED");
        assertThat(approval.decidedBy()).isEqualTo(fixture.otherEditorId());
        assertThat(approval.decidedAt()).isNotNull();
        assertThat(approval.decisionComment()).isEqualTo("同意");

        NodeRow approvalNode = nodeRows(fixture.tenant(), runId, APPROVAL_NODE).getLast();
        assertThat(approvalNode.status()).isEqualTo(WorkflowNodeStatus.SUCCEEDED);
        assertThat(nodeRunOutput(fixture.tenant(), runId, APPROVAL_NODE)
                        .path("decision")
                        .asText())
                .isEqualTo("APPROVED");
        assertThat(nodeRunErrorCode(fixture.tenant(), runId, APPROVAL_NODE)).isNull();

        RunRow run = runRow(fixture.tenant(), runId);
        assertThat(run.status()).isEqualTo("PENDING");
        assertThat(run.currentNodeKey()).isEqualTo(APPROVAL_TARGET_NODE);
        assertThat(run.errorCode()).isNull();
        assertThat(run.finishedAt()).isNull();

        List<AuditRowAccessor> audits = decidedAudits(fixture.tenant());
        assertThat(audits).hasSize(1);
        AuditRowAccessor audit = audits.getFirst();
        assertThat(audit.outcome()).isEqualTo("SUCCESS");
        assertThat(audit.actorId()).isEqualTo(fixture.otherEditorId().toString());
        assertThat(audit.targetId()).isEqualTo(runId.toString());
        JsonNode details = audit.details();
        assertThat(details.size()).isEqualTo(7);
        assertThat(details.path("workflowRunId").asText()).isEqualTo(runId.toString());
        assertThat(details.path("workflowApprovalId").asText()).isEqualTo(approvalId.toString());
        assertThat(details.path("nodeId").asText()).isEqualTo(APPROVAL_NODE);
        assertThat(details.path("decision").asText()).isEqualTo("APPROVE");
        assertThat(details.path("approverUserId").asText())
                .isEqualTo(fixture.otherEditorId().toString());
        assertThat(details.path("targetToolName").asText()).isEqualTo("search-mock");
        assertThat(details.path("decisionComment").asText()).isEqualTo("同意");

        assertProblem(
                decideApproval(fixture.otherEditor(), approvalId, "APPROVE", "同意", "decision-approve-001"),
                409,
                WF_409);
        assertThat(decidedAudits(fixture.tenant())).hasSize(1);
        assertThat(runRow(fixture.tenant(), runId).status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("SEC-WF-001 拒绝理由必填且 201 字符可写入并可读回审计")
    void reject_requiresComment_andEndsRunWithSkippedDownstream() {
        Fixture fixture = fixture("wap");
        String workflowId = publishApprovalWorkflow(fixture, "拒绝运行");
        UUID approvalId = seedAwaitingApproval(
                fixture,
                workflowId,
                fixture.ownerId(),
                "ROLE",
                DEFAULT_APPROVER_ROLE,
                null,
                Instant.now().minusSeconds(20));
        UUID runId = approvalRunId(fixture.tenant(), approvalId);

        assertProblem(
                decideApproval(fixture.otherEditor(), approvalId, "REJECT", null, "decision-reject-001"), 400, WF_400);
        assertProblem(
                decideApproval(fixture.otherEditor(), approvalId, "REJECT", "   ", "decision-reject-002"), 400, WF_400);
        assertProblem(
                decideApproval(fixture.otherEditor(), approvalId, "REJECT", "拒".repeat(501), "decision-reject-003"),
                400,
                WF_400);
        assertThat(approvalRow(fixture.tenant(), approvalId).status()).isEqualTo("PENDING");
        assertThat(decidedAudits(fixture.tenant())).isEmpty();

        String reason = "拒".repeat(201);
        ApiTestClient.ApiResponse response =
                decideApproval(fixture.otherEditor(), approvalId, "REJECT", reason, "decision-reject-004");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(client.json(response).path("status").asText()).isEqualTo("REJECTED");
        assertThat(client.json(response).path("decisionComment").asText()).isEqualTo(reason);

        ApprovalRow approval = approvalRow(fixture.tenant(), approvalId);
        assertThat(approval.status()).isEqualTo("REJECTED");
        assertThat(approval.decidedBy()).isEqualTo(fixture.otherEditorId());
        assertThat(approval.decisionComment()).isEqualTo(reason);

        NodeRow approvalNode = nodeRows(fixture.tenant(), runId, APPROVAL_NODE).getLast();
        assertThat(approvalNode.status()).isEqualTo(WorkflowNodeStatus.FAILED);
        assertThat(nodeRunErrorCode(fixture.tenant(), runId, APPROVAL_NODE)).isEqualTo("WF_APPROVAL_REJECTED");

        RunRow run = runRow(fixture.tenant(), runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WF_APPROVAL_REJECTED");
        assertThat(run.currentNodeKey()).isEqualTo(APPROVAL_NODE);
        assertThat(run.finishedAt()).isNotNull();
        assertThat(runResultStatus(fixture.tenant(), runId)).isEqualTo("NONE");
        assertThat(nodeKeysByStatus(fixture.tenant(), runId, "SKIPPED")).containsExactly("end", "report", "tool");

        List<AuditRowAccessor> audits = decidedAudits(fixture.tenant());
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().outcome()).isEqualTo("SUCCESS");
        JsonNode details = audits.getFirst().details();
        assertThat(details.path("decision").asText()).isEqualTo("REJECT");
        assertThat(details.path("decisionComment").asText()).isEqualTo(reason);
        Map<String, Object> readBack = AuditDetailsReader.read(
                JSON, AuditActions.WORKFLOW_APPROVAL_DECIDED, audits.getFirst().rawDetails());
        assertThat((String) readBack.get("decisionComment")).hasSize(201);
    }

    @Test
    @DisplayName("SEC-WF-001 500 字符理由写入并可读回审计，501 字符拒绝")
    void decide_commentLengthBoundary_isReadableForAudit() {
        Fixture fixture = fixture("wap");
        String workflowId = publishApprovalWorkflow(fixture, "理由边界");
        UUID approvalId = seedAwaitingApproval(
                fixture,
                workflowId,
                fixture.ownerId(),
                "ROLE",
                DEFAULT_APPROVER_ROLE,
                null,
                Instant.now().minusSeconds(20));

        assertProblem(
                decideApproval(fixture.otherEditor(), approvalId, "APPROVE", "通".repeat(501), "decision-500-001"),
                400,
                WF_400);
        assertThat(approvalRow(fixture.tenant(), approvalId).status()).isEqualTo("PENDING");

        String comment = "通".repeat(500);
        ApiTestClient.ApiResponse response =
                decideApproval(fixture.otherEditor(), approvalId, "APPROVE", comment, "decision-500-002");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(approvalRow(fixture.tenant(), approvalId).decisionComment()).hasSize(500);

        List<AuditRowAccessor> audits = decidedAudits(fixture.tenant());
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().details().path("decisionComment").asText()).isEqualTo(comment);
        Map<String, Object> readBack = AuditDetailsReader.read(
                JSON, AuditActions.WORKFLOW_APPROVAL_DECIDED, audits.getFirst().rawDetails());
        assertThat((String) readBack.get("decisionComment")).hasSize(500);
    }

    @Test
    @DisplayName("SEC-WF-001 自批/范围外 403 并写 denied 审计，平台与跨租户 404")
    void decide_forbiddenMatrix_auditsDeniedAndKeepsWaiting() {
        Fixture fixture = fixture("wap");
        String workflowId = publishApprovalWorkflow(fixture, "审批越权");
        UUID approvalId = seedAwaitingApproval(
                fixture,
                workflowId,
                fixture.ownerId(),
                "ROLE",
                DEFAULT_APPROVER_ROLE,
                null,
                Instant.now().minusSeconds(20));
        UUID runId = approvalRunId(fixture.tenant(), approvalId);

        assertProblem(decideApproval(fixture.owner(), approvalId, "APPROVE", "自批", "decision-self-001"), 403, AUTH_403);
        assertProblem(
                decideApproval(fixture.member(), approvalId, "APPROVE", "越权", "decision-scope-001"), 403, AUTH_403);
        assertProblem(
                decideApproval(fixture.tenantAdmin(), approvalId, "APPROVE", "越权", "decision-scope-002"),
                403,
                AUTH_403);
        assertProblem(
                decideApproval(fixture.owner(), UUID.randomUUID(), "APPROVE", "未知", "decision-missing-001"),
                404,
                APPROVAL_404);

        Fixture other = fixture("wap");
        assertProblem(
                decideApproval(other.otherEditor(), approvalId, "APPROVE", "跨租户", "decision-tenant-001"),
                404,
                APPROVAL_404);
        assertProblem(
                decideApproval(other.platform(), approvalId, "APPROVE", "平台", "decision-platform-001"),
                404,
                APPROVAL_404);
        assertProblem(listApprovals(other.platform(), null), 404, APPROVAL_404);

        List<AuditRowAccessor> audits = decidedAudits(fixture.tenant());
        assertThat(audits).hasSize(3);
        assertThat(audits).allSatisfy(audit -> {
            assertThat(audit.outcome()).isEqualTo("DENIED");
            assertThat(audit.details().has("decisionComment")).isFalse();
        });
        assertThat(approvalRow(fixture.tenant(), approvalId).status()).isEqualTo("PENDING");
        RunRow run = runRow(fixture.tenant(), runId);
        assertThat(run.status()).isEqualTo("WAITING_APPROVAL");
        assertThat(run.currentNodeKey()).isEqualTo(APPROVAL_NODE);
    }

    @Test
    @DisplayName("SEC-WF-001 无可用审批人不自动放行，待办与详情不泄露给发起人/管理员")
    void noApprover_staysWaitingWithoutAutoApproval() {
        Fixture fixture = fixture("wap");
        String workflowId = publishApprovalWorkflow(fixture, "无审批人");
        UUID approvalId = seedNoApproverApproval(fixture, workflowId);
        UUID runId = approvalRunId(fixture.tenant(), approvalId);

        JsonNode adminInbox = client.json(listApprovals(fixture.tenantAdmin(), null));
        assertThat(adminInbox.path("total").asLong()).isZero();
        assertProblem(getApproval(fixture.owner(), approvalId), 404, APPROVAL_404);
        assertProblem(
                decideApproval(fixture.owner(), approvalId, "APPROVE", "代批", "decision-noapprover-001"), 403, AUTH_403);

        ApprovalRow approval = approvalRow(fixture.tenant(), approvalId);
        assertThat(approval.status()).isEqualTo("PENDING");
        RunRow run = runRow(fixture.tenant(), runId);
        assertThat(run.status()).isEqualTo("WAITING_APPROVAL");
        assertThat(run.errorCode()).isEqualTo("WF_NO_APPROVER");
        assertThat(decidedAudits(fixture.tenant())).hasSize(1);
        assertThat(decidedAudits(fixture.tenant()).getFirst().outcome()).isEqualTo("DENIED");
    }

    @Test
    @DisplayName("SEC-WF-001 并发决定一个 200 一个 409，只写一条成功审计")
    void concurrentDecision_oneSucceedsOneConflicts() throws Exception {
        Fixture fixture = fixture("wap");
        String workflowId = publishApprovalWorkflow(fixture, "并发决定");
        UUID approvalId = seedAwaitingApproval(
                fixture,
                workflowId,
                fixture.ownerId(),
                "ROLE",
                DEFAULT_APPROVER_ROLE,
                null,
                Instant.now().minusSeconds(20));
        LoginSession secondEditor = seedAndLoginLocalUser(
                fixture.platform(),
                fixture.tenant(),
                "editorb" + UUID.randomUUID().toString().substring(0, 8),
                "editorb-password-12",
                List.of("WORKFLOW_EDITOR"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ApiTestClient.ApiResponse>> futures = new ArrayList<>();
            for (LoginSession session : List.of(fixture.otherEditor(), secondEditor)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return decideApproval(session, approvalId, "APPROVE", "并发", "concurrent-" + UUID.randomUUID());
                }));
            }
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<ApiTestClient.ApiResponse> future : futures) {
                statuses.add(future.get().status());
            }
            statuses.sort(Integer::compareTo);
            assertThat(statuses).containsExactly(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(approvalRow(fixture.tenant(), approvalId).status()).isEqualTo("APPROVED");
        List<AuditRowAccessor> audits = decidedAudits(fixture.tenant());
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().outcome()).isEqualTo("SUCCESS");
    }

    /**
     * 读取本租户 {@code workflow.approval.decided} 审计并保留原始 details 文本。
     *
     * @param tenant 租户
     * @return 审计访问器列表
     */
    private List<AuditRowAccessor> decidedAudits(String tenant) {
        return tenantAuditRows(tenant, AuditActions.WORKFLOW_APPROVAL_DECIDED).stream()
                .map(row -> new AuditRowAccessor(
                        row.outcome(), row.actorId(), row.targetId(), row.details(), auditDetails(row)))
                .toList();
    }

    /**
     * 审批决定审计的测试访问器。
     *
     * @param outcome 结果
     * @param actorId 操作者
     * @param targetId 目标运行
     * @param rawDetails 原始 details JSON
     * @param details 解析后的 details
     */
    private record AuditRowAccessor(
            String outcome, String actorId, String targetId, String rawDetails, JsonNode details) {}
}
