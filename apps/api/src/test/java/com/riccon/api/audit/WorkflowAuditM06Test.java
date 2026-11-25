package com.riccon.api.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import com.riccon.api.workflow.approval.WorkflowApprovalTestSupport;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * M06 审计查询与理由读回独立套件（IT-AUDIT-M06-01、M06-AC-16）。
 *
 * <p>验证发布/运行/取消/审批/停用/启用/移交动作进入既有 M10 审计查询，可按 action、targetId、
 * actorId 与时间范围筛选；details 只含白名单字段，不含 prompt、工具参数、凭据或文档正文；
 * 审批理由 201/500 字可写入并读回，501 字拒绝且不产生成功审计；租户管理员查看他人运行写
 * MANAGEMENT_READ 管理性读取审计。</p>
 */
@Tag("security")
class WorkflowAuditM06Test extends WorkflowApprovalTestSupport {

    /** 查询审计事件。 */
    private JsonNode queryAudit(LoginSession session, String query) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/audit-events?" + query, session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /** URL 编码。 */
    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 断言 details 不含敏感字段与提示词标记。 */
    private static void assertDetailsWhitelisted(JsonNode event) {
        String details = event.path("details").toString();
        assertThat(details).doesNotContain("promptTemplate");
        assertThat(details).doesNotContain("SECRET-PROMPT-MARKER");
        assertThat(details).doesNotContain("argsTemplate");
        assertThat(details).doesNotContain("authorization");
        assertThat(details).doesNotContain("credential");
        assertThat(details).doesNotContain("password");
        JsonNode detailsNode = event.path("details");
        for (String forbidden : new String[] {
            "prompt", "promptTemplate", "graph", "graphJson", "paramsCiphertext", "documentText", "content"
        }) {
            assertThat(detailsNode.has(forbidden))
                    .as("details must not contain " + forbidden)
                    .isFalse();
        }
    }

    /** IT-AUDIT-M06-01：动作可查询、按对象/操作者/时间过滤、details 白名单。 */
    @Test
    @DisplayName("IT-AUDIT-M06-01 发布/运行/取消/审批/停用/启用/移交给审计可查")
    void workflowActionsAreQueryable() {
        Fixture fixture = fixture("itauditm06");
        String workflowId = createDraft(fixture.owner(), "审计流程-" + UUID.randomUUID())
                .path("id")
                .asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        publishOk(fixture.tenantAdmin(), workflowId, "ONLY_SELF", null, "publish-" + UUID.randomUUID());

        JsonNode run = createRunOk(fixture.owner(), workflowId, null, "run-" + UUID.randomUUID());
        String runId = run.path("id").asText();
        assertThat(cancelRun(fixture.owner(), runId).status()).isEqualTo(202);

        // 审批必须发生在 WAITING_APPROVAL 运行上；单独构造审批运行，不复用已取消运行。
        UUID approvalId = seedAwaitingApproval(
                fixture,
                workflowId,
                fixture.ownerId(),
                "ROLE",
                "TENANT_ADMIN",
                java.util.List.of(),
                java.time.Instant.now());
        UUID approvalRunId = approvalRunId(fixture.tenant(), approvalId);
        assertThat(decideApproval(fixture.tenantAdmin(), approvalId, "APPROVE", "审计读回", "decide-" + UUID.randomUUID())
                        .status())
                .isEqualTo(200);

        assertThat(disable(fixture.owner(), workflowId).status()).isEqualTo(200);
        assertThat(enable(fixture.owner(), workflowId).status()).isEqualTo(200);
        assertThat(transfer(fixture.tenantAdmin(), workflowId, fixture.otherEditorId())
                        .status())
                .isEqualTo(200);

        Map<String, String> actions = Map.of(
                "workflow.created", workflowId,
                "workflow.draft.saved", workflowId,
                "workflow.published", workflowId,
                "workflow.run.requested", runId,
                "workflow.run.cancelled", runId,
                "workflow.approval.decided", approvalRunId.toString(),
                "workflow.disabled", workflowId,
                "workflow.enabled", workflowId,
                "workflow.owner.transferred", workflowId);
        for (Map.Entry<String, String> entry : actions.entrySet()) {
            JsonNode page = queryAudit(
                    fixture.tenantAdmin(),
                    "action=" + enc(entry.getKey()) + "&targetId=" + enc(entry.getValue()) + "&size=20");
            assertThat(page.path("total").asLong())
                    .as(entry.getKey() + " must be queryable by object")
                    .isGreaterThanOrEqualTo(1);
            JsonNode event = page.path("items").get(0);
            assertThat(event.path("action").asText()).isEqualTo(entry.getKey());
            assertThat(event.path("targetId").asText()).isEqualTo(entry.getValue());
            assertDetailsWhitelisted(event);
        }

        // 按操作者与时间筛选：发布人为租户管理员，且事件发生在查询窗口内。
        String actorQuery = "action=workflow.published&targetId=" + enc(workflowId)
                + "&actorId=" + enc(fixture.tenantAdminId().toString())
                + "&occurredFrom="
                + enc(java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(8))
                        .minusDays(1)
                        .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                + "&occurredTo="
                + enc(java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(8))
                        .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        JsonNode byActor = queryAudit(fixture.tenantAdmin(), actorQuery);
        assertThat(byActor.path("total").asLong()).isEqualTo(1);

        // 审批决定 details 含决定/理由/审批人，且不泄露工具参数。
        JsonNode approvalPage = queryAudit(
                fixture.tenantAdmin(),
                "action=" + enc("workflow.approval.decided") + "&targetId=" + enc(approvalRunId.toString()));
        JsonNode details = approvalPage.path("items").get(0).path("details");
        assertThat(details.path("decision").asText()).isEqualTo("APPROVE");
        assertThat(details.path("decisionComment").asText()).isEqualTo("审计读回");
        assertThat(details.path("approverUserId").asText())
                .isEqualTo(fixture.tenantAdminId().toString());
    }

    /** IT-AUDIT-M06-01 / U-05：201 与 500 字理由读回一致，501 字拒绝且无成功审计。 */
    @Test
    @DisplayName("IT-AUDIT-M06-01 审批理由 201/500 读回，501 拒绝")
    void approvalCommentReadBackBoundaries() {
        Fixture fixture = fixture("itauditcomment");
        String workflowId = createDraft(fixture.owner(), "理由边界-" + UUID.randomUUID())
                .path("id")
                .asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        publishOk(fixture.tenantAdmin(), workflowId, "ONLY_SELF", null, "publish-" + UUID.randomUUID());

        String comment201 = "审".repeat(201);
        String comment500 = "批".repeat(500);
        UUID firstApproval = seedAwaitingApproval(
                fixture,
                workflowId,
                fixture.ownerId(),
                "ROLE",
                "TENANT_ADMIN",
                java.util.List.of(),
                java.time.Instant.now());
        UUID firstRun = approvalRunId(fixture.tenant(), firstApproval);
        assertThat(decideApproval(
                                fixture.tenantAdmin(),
                                firstApproval,
                                "APPROVE",
                                comment201,
                                "c201-" + UUID.randomUUID())
                        .status())
                .isEqualTo(200);

        UUID secondApproval = seedAwaitingApproval(
                fixture,
                workflowId,
                fixture.ownerId(),
                "ROLE",
                "TENANT_ADMIN",
                java.util.List.of(),
                java.time.Instant.now());
        UUID secondRun = approvalRunId(fixture.tenant(), secondApproval);
        assertThat(decideApproval(
                                fixture.tenantAdmin(),
                                secondApproval,
                                "REJECT",
                                comment500,
                                "c500-" + UUID.randomUUID())
                        .status())
                .isEqualTo(200);

        for (String runId : new String[] {firstRun.toString(), secondRun.toString()}) {
            JsonNode page = queryAudit(
                    fixture.tenantAdmin(), "action=" + enc("workflow.approval.decided") + "&targetId=" + enc(runId));
            assertThat(page.path("total").asLong()).isEqualTo(1);
            String readBack = page.path("items")
                    .get(0)
                    .path("details")
                    .path("decisionComment")
                    .asText();
            assertThat(readBack.length()).isIn(201, 500);
        }
        JsonNode first = queryAudit(
                        fixture.tenantAdmin(),
                        "action=" + enc("workflow.approval.decided") + "&targetId=" + enc(firstRun.toString()))
                .path("items")
                .get(0);
        assertThat(first.path("details").path("decisionComment").asText()).isEqualTo(comment201);
        JsonNode second = queryAudit(
                        fixture.tenantAdmin(),
                        "action=" + enc("workflow.approval.decided") + "&targetId=" + enc(secondRun.toString()))
                .path("items")
                .get(0);
        assertThat(second.path("details").path("decisionComment").asText()).isEqualTo(comment500);

        // 501 字：400 RICCON-WF-400，审批仍 PENDING，不写成功审计。
        UUID thirdApproval = seedAwaitingApproval(
                fixture,
                workflowId,
                fixture.ownerId(),
                "ROLE",
                "TENANT_ADMIN",
                java.util.List.of(),
                java.time.Instant.now());
        UUID thirdRun = approvalRunId(fixture.tenant(), thirdApproval);
        ApiTestClient.ApiResponse tooLong = decideApproval(
                fixture.tenantAdmin(), thirdApproval, "APPROVE", "长".repeat(501), "c501-" + UUID.randomUUID());
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(client.json(tooLong).path("code").asText()).isEqualTo("RICCON-WF-400");
        JsonNode thirdEvents = queryAudit(
                fixture.tenantAdmin(),
                "action=" + enc("workflow.approval.decided") + "&targetId=" + enc(thirdRun.toString()));
        assertThat(thirdEvents.path("total").asLong()).isZero();
    }

    /** 租户管理员查看他人运行写 MANAGEMENT_READ 管理性读取审计。 */
    @Test
    @DisplayName("IT-AUDIT-M06-01 管理员查看他人运行写 workflow.run.admin_read")
    void adminReadIsAudited() {
        Fixture fixture = fixture("itauditread");
        String workflowId = createDraft(fixture.owner(), "管理读取-" + UUID.randomUUID())
                .path("id")
                .asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        publishOk(fixture.tenantAdmin(), workflowId, "ONLY_SELF", null, "publish-" + UUID.randomUUID());
        JsonNode run = createRunOk(fixture.owner(), workflowId, null, "run-" + UUID.randomUUID());
        String runId = run.path("id").asText();

        assertThat(client.get(
                                "/api/v1/workflow-runs/" + runId,
                                fixture.tenantAdmin().sessionCookie(),
                                Map.of())
                        .status())
                .isEqualTo(200);
        JsonNode page = queryAudit(
                fixture.tenantAdmin(), "action=" + enc("workflow.run.admin_read") + "&targetId=" + enc(runId));
        assertThat(page.path("total").asLong()).isGreaterThanOrEqualTo(1);
        JsonNode details = page.path("items").get(0).path("details");
        assertThat(details.path("reason").asText()).isEqualTo("MANAGEMENT_READ");
        assertThat(details.path("workflowRunId").asText()).isEqualTo(runId);
    }
}
