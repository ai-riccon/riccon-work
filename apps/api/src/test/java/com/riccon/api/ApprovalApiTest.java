package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.ResearchApiTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 审批 API 集成测试。
 *
 * <p>覆盖待审批列表、批准/拒绝状态机、重复决定幂等、相反决定冲突、resume/cancel 事件字段、审计与跨租户 404。
 */
class ApprovalApiTest extends ResearchApiTestSupport {

    /** 待审批列表只返回脱敏预览，不返回密文与密钥 ID。 */
    @Test
    @DisplayName("列出待审批项")
    void list_returnsPendingApprovalsWithoutCiphertext() {
        Fixture fixture = fixture();
        UUID toolId = createMcpTool(fixture.tenant());
        JsonNode task = createTask(fixture.member(), fixture.memberWorkspace(), "审批列表主题", toolId, "appr-list-01");
        String runId = runId(task);
        markRunWaitingApproval(fixture.tenant(), runId);
        UUID pendingId = seedApproval(
                fixture.tenant(),
                task.path("id").asText(),
                runId,
                toolId,
                userId(fixture.member()),
                "search_market",
                "PENDING",
                "{\"region\":\"CN\"}");

        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/approvals?status=PENDING&page=1&size=20",
                fixture.admin().sessionCookie(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode page = client.json(response);
        assertThat(page.path("total").asLong()).isEqualTo(1);
        JsonNode approval = page.path("items").get(0);
        assertThat(approval.path("id").asText()).isEqualTo(pendingId.toString());
        assertThat(approval.path("status").asText()).isEqualTo("PENDING");
        assertThat(approval.path("actionType").asText()).isEqualTo("MCP_TOOL_CALL");
        assertThat(approval.path("toolName").asText()).isEqualTo("search_market");
        assertThat(approval.path("paramsPreview").path("region").asText()).isEqualTo("CN");
        assertThat(approval.path("paramsSha256").asText()).isEqualTo(TEST_SHA256);
        assertThat(response.body()).doesNotContain("ciphertext").doesNotContain("paramsKeyId");
    }

    /** 批准推进运行与任务、写 resume 事件与审计；重复批准幂等且不重复投递。 */
    @Test
    @DisplayName("批准幂等并投递 resume")
    void decide_approveIsIdempotent() {
        Fixture fixture = fixture();
        UUID toolId = createMcpTool(fixture.tenant());
        JsonNode task = createTask(fixture.member(), fixture.memberWorkspace(), "批准流程主题", toolId, "appr-ok-01");
        String taskId = task.path("id").asText();
        String runId = runId(task);
        markRunWaitingApproval(fixture.tenant(), runId);
        UUID approvalId = seedApproval(
                fixture.tenant(),
                taskId,
                runId,
                toolId,
                userId(fixture.member()),
                "search_market",
                "PENDING",
                "{\"region\":\"CN\"}");

        ApiTestClient.ApiResponse first = decide(fixture.admin(), approvalId, "APPROVE", "同意执行只读取数", "appr-ok-key-1");
        assertThat(first.status()).as(first.body()).isEqualTo(200);
        JsonNode body = client.json(first);
        assertThat(body.path("status").asText()).isEqualTo("APPROVED");
        assertThat(body.path("decidedBy").asText()).isEqualTo(userId(fixture.admin()));
        assertThat(body.path("decidedAt").isNull()).isFalse();
        assertThat(body.path("decisionComment").asText()).isEqualTo("同意执行只读取数");
        assertThat(approvalStatus(fixture.tenant(), approvalId)).isEqualTo("APPROVED");
        assertThat(runStatus(fixture.tenant(), runId)).isEqualTo("RUNNING");
        assertThat(taskStatus(fixture.tenant(), taskId)).isEqualTo("RUNNING");

        List<String> types = outboxEventTypes(fixture.tenant(), runId);
        assertThat(types).containsExactly("riccon.agent.run.requested.v1", "riccon.agent.run.resume.v1");
        List<String> payloads = outboxPayloads(fixture.tenant(), runId);
        Map<String, Object> resume = payload(payloads.get(1));
        assertThat(resume.keySet())
                .containsExactlyInAnyOrder("runId", "researchTaskId", "approvalId", "stage", "attempt");
        assertThat(resume)
                .containsEntry("runId", runId)
                .containsEntry("researchTaskId", taskId)
                .containsEntry("approvalId", approvalId.toString())
                .containsEntry("stage", "GENERATION")
                .containsEntry("attempt", 1);

        ApiTestClient.ApiResponse repeat = decide(fixture.admin(), approvalId, "APPROVE", "同意执行只读取数", "appr-ok-key-1");
        assertThat(repeat.status()).as(repeat.body()).isEqualTo(200);
        assertThat(client.json(repeat).path("status").asText()).isEqualTo("APPROVED");
        assertThat(outboxCount(fixture.tenant(), runId, "riccon.agent.run.resume.v1"))
                .isEqualTo(1L);

        ApiTestClient.ApiResponse opposite = decide(fixture.admin(), approvalId, "REJECT", null, "appr-ok-key-2");
        assertThat(opposite.status()).isEqualTo(409);
        assertThat(client.json(opposite).path("code").asText()).isEqualTo("RICCON-CONFLICT-409");

        assertThat(auditRows("approval.decided"))
                .filteredOn(row -> fixture.tenant().equals(row.tenantId()))
                .singleElement()
                .satisfies(row -> assertThat(row.details())
                        .contains(approvalId.toString())
                        .contains("APPROVE")
                        .contains("search_market"));
    }

    /** 拒绝终止运行与任务、写 cancel 事件且不产生 resume 或产物。 */
    @Test
    @DisplayName("拒绝终止任务并投递 cancel")
    void decide_rejectTerminatesTask() {
        Fixture fixture = fixture();
        UUID toolId = createMcpTool(fixture.tenant());
        JsonNode task = createTask(fixture.member(), fixture.memberWorkspace(), "拒绝流程主题", toolId, "appr-no-01");
        String taskId = task.path("id").asText();
        String runId = runId(task);
        markRunWaitingApproval(fixture.tenant(), runId);
        UUID approvalId = seedApproval(
                fixture.tenant(),
                taskId,
                runId,
                toolId,
                userId(fixture.member()),
                "search_market",
                "PENDING",
                "{\"region\":\"CN\"}");

        ApiTestClient.ApiResponse response = decide(fixture.admin(), approvalId, "REJECT", "数据范围过大", "appr-no-key-1");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(client.json(response).path("status").asText()).isEqualTo("REJECTED");
        assertThat(approvalStatus(fixture.tenant(), approvalId)).isEqualTo("REJECTED");
        assertThat(runStatus(fixture.tenant(), runId)).isEqualTo("CANCELLED");
        assertThat(taskStatus(fixture.tenant(), taskId)).isEqualTo("CANCELLED");

        String failureCode = jdbcClient
                .sql("SELECT failure_code FROM research_task WHERE tenant_id = :tenantId AND id = :taskId")
                .param("tenantId", fixture.tenant())
                .param("taskId", com.riccon.api.persistence.BinaryUuids.toBytes(UUID.fromString(taskId)))
                .query(String.class)
                .single();
        assertThat(failureCode).isEqualTo("APPROVAL_REJECTED");

        assertThat(outboxEventTypes(fixture.tenant(), runId))
                .containsExactly("riccon.agent.run.requested.v1", "riccon.agent.run.cancel.v1");
        Map<String, Object> cancel =
                payload(outboxPayloads(fixture.tenant(), runId).get(1));
        assertThat(cancel.keySet()).containsExactlyInAnyOrder("runId", "reason");
        assertThat(cancel).containsEntry("runId", runId).containsEntry("reason", "USER_CANCELLED");
        assertThat(outboxCount(fixture.tenant(), runId, "riccon.agent.run.resume.v1"))
                .isZero();

        Long artifacts = jdbcClient
                .sql("SELECT COUNT(*) FROM artifact WHERE tenant_id = :tenantId AND research_task_id = :taskId")
                .param("tenantId", fixture.tenant())
                .param("taskId", com.riccon.api.persistence.BinaryUuids.toBytes(UUID.fromString(taskId)))
                .query(Long.class)
                .single();
        assertThat(artifacts).isZero();

        assertThat(auditRows("approval.decided"))
                .filteredOn(row -> fixture.tenant().equals(row.tenantId()))
                .singleElement()
                .satisfies(row -> assertThat(row.details()).contains("REJECT"));
    }

    /** 已失效审批、运行不在等待审批、缺失幂等键与跨租户都失败。 */
    @Test
    @DisplayName("审批状态机与跨租户边界")
    void decide_rejectsInvalidTransitionsAndCrossTenant() {
        Fixture first = fixture();
        Fixture second = fixture();
        UUID toolId = createMcpTool(first.tenant());
        JsonNode expiredTask = createTask(first.member(), first.memberWorkspace(), "过期审批主题", toolId, "appr-edge-01");
        String expiredRunId = runId(expiredTask);
        markRunWaitingApproval(first.tenant(), expiredRunId);
        UUID expiredId = seedApproval(
                first.tenant(),
                expiredTask.path("id").asText(),
                expiredRunId,
                toolId,
                userId(first.member()),
                "search_market",
                "EXPIRED",
                "{\"region\":\"CN\"}");

        ApiTestClient.ApiResponse expired = decide(first.admin(), expiredId, "APPROVE", null, "appr-edge-key-1");
        assertThat(expired.status()).isEqualTo(409);

        // 每次运行的 APPROVAL/attempt 只允许一个审批，第二个审批使用第二个任务与运行。
        JsonNode pendingTask = createTask(first.member(), first.memberWorkspace(), "待审批边界主题", toolId, "appr-edge-02");
        String pendingTaskId = pendingTask.path("id").asText();
        String pendingRunId = runId(pendingTask);
        markRunWaitingApproval(first.tenant(), pendingRunId);
        UUID pendingId = seedApproval(
                first.tenant(),
                pendingTaskId,
                pendingRunId,
                toolId,
                userId(first.member()),
                "search_other",
                "PENDING",
                "{\"region\":\"CN\"}");
        ApiTestClient.ApiResponse crossTenant = decide(second.member(), pendingId, "APPROVE", null, "appr-edge-key-2");
        assertThat(crossTenant.status()).isEqualTo(404);
        assertThat(client.json(crossTenant).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        ApiTestClient.ApiResponse missingKey = client.post(
                "/api/v1/approvals/" + pendingId + "/decision",
                client.jsonBody("decision", "APPROVE"),
                first.admin().sessionCookie(),
                first.admin().csrfToken(),
                Map.of());
        assertThat(missingKey.status()).isEqualTo(400);

        // 运行不在 WAITING_APPROVAL 时不得决定，避免对已取消/已完成的运行写入 resume。
        jdbcClient
                .sql("""
                        UPDATE agent_run SET status = 'CANCELLED', finished_at = NOW(6), updated_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("tenantId", first.tenant())
                .param("runId", com.riccon.api.persistence.BinaryUuids.toBytes(UUID.fromString(pendingRunId)))
                .update();
        ApiTestClient.ApiResponse staleRun = decide(first.admin(), pendingId, "APPROVE", null, "appr-edge-key-3");
        assertThat(staleRun.status()).isEqualTo(409);
    }

    /**
     * 提交审批决定。
     *
     * @param session 登录会话
     * @param approvalId 审批 ID
     * @param decision 决定
     * @param comment 备注
     * @param idempotencyKey 幂等键
     * @return 响应
     */
    private ApiTestClient.ApiResponse decide(
            LoginSession session, UUID approvalId, String decision, String comment, String idempotencyKey) {
        String body = comment == null
                ? client.jsonBody("decision", decision)
                : client.jsonBody("decision", decision, "comment", comment);
        return client.post(
                "/api/v1/approvals/" + approvalId + "/decision",
                body,
                session.sessionCookie(),
                session.csrfToken(),
                Map.of("Idempotency-Key", idempotencyKey));
    }
}
