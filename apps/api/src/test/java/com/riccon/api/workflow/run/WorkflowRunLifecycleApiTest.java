package com.riccon.api.workflow.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 运行取消/重试状态机集成测试（AC-15/23、ADR-0024 §4.6/§5.4）。
 *
 * <p>覆盖：PENDING/WAITING_APPROVAL 取消、已取消幂等、终态 409、非发起人 403、跨租户 404；失败节点重试
 * 新增 attempt、下游 SKIPPED 恢复 PENDING、重放不重复、非失败节点/未知节点 409、并发上限 429。
 */
class WorkflowRunLifecycleApiTest extends WorkflowRunTestSupport {

    /** 创建并发布流程。 */
    private String publishWorkflow(Fixture fixture, String name) {
        String workflowId = createDraft(fixture.owner(), name).path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        publishOk(fixture.tenantAdmin(), workflowId, "TENANT", null, "publish-" + UUID.randomUUID());
        return workflowId;
    }

    @Test
    @DisplayName("IT-WF-110 PENDING 运行取消成功且重复取消幂等")
    void cancelRun_pendingRun_isIdempotent() {
        Fixture fixture = fixture("wfl");
        String workflowId = publishWorkflow(fixture, "取消运行");
        JsonNode created = createRunOk(fixture.owner(), workflowId, null, "run-cancel-001");
        UUID runId = UUID.fromString(created.path("id").asText());

        ApiTestClient.ApiResponse response = cancelRun(fixture.owner(), runId.toString());
        assertThat(response.status()).as(response.body()).isEqualTo(202);
        RunRow row = runRow(fixture.tenant(), runId);
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.errorCode()).isEqualTo("WF_CANCELLED");
        assertThat(row.finishedAt()).isNotNull();
        var audits = tenantAuditRows(fixture.tenant(), "workflow.run.cancelled");
        assertThat(audits).hasSize(1);
        JsonNode details = auditDetails(audits.getFirst());
        assertThat(details.path("workflowRunId").asText()).isEqualTo(runId.toString());
        assertThat(details.path("previousStatus").asText()).isEqualTo("PENDING");

        ApiTestClient.ApiResponse replay = cancelRun(fixture.owner(), runId.toString());
        assertThat(replay.status()).as(replay.body()).isEqualTo(202);
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.cancelled")).hasSize(1);
    }

    @Test
    @DisplayName("IT-WF-110 取消 WAITING_APPROVAL 运行会取消待审批项")
    void cancelRun_waitingApproval_cancelsApprovals() {
        Fixture fixture = fixture("wfl");
        String workflowId = publishWorkflow(fixture, "取消审批");
        JsonNode created = createRunOk(fixture.owner(), workflowId, null, "run-cancel-002");
        UUID runId = UUID.fromString(created.path("id").asText());
        jdbcClient
                .sql("UPDATE workflow_run SET status = 'WAITING_APPROVAL' WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", fixture.tenant())
                .param("id", com.riccon.api.persistence.BinaryUuids.toBytes(runId))
                .update();
        UUID approvalId = insertPendingApproval(fixture.tenant(), runId, workflowId, "approval", fixture.ownerId());

        ApiTestClient.ApiResponse response = cancelRun(fixture.tenantAdmin(), runId.toString());
        assertThat(response.status()).as(response.body()).isEqualTo(202);
        assertThat(runRow(fixture.tenant(), runId).status()).isEqualTo("CANCELLED");
        assertThat(jdbcClient
                        .sql("SELECT status FROM workflow_approval WHERE tenant_id = :tenant AND id = :id")
                        .param("tenant", fixture.tenant())
                        .param("id", com.riccon.api.persistence.BinaryUuids.toBytes(approvalId))
                        .query(String.class)
                        .single())
                .isEqualTo("CANCELLED");
        JsonNode details = auditDetails(
                tenantAuditRows(fixture.tenant(), "workflow.run.cancelled").getFirst());
        assertThat(details.path("previousStatus").asText()).isEqualTo("WAITING_APPROVAL");
    }

    @Test
    @DisplayName("IT-WF-110 终态运行取消返回 409，非发起人 403，跨租户 404")
    void cancelRun_forbiddenAndConflictMatrix() {
        Fixture fixture = fixture("wfl");
        String workflowId = publishWorkflow(fixture, "取消权限");
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID runId = insertActiveRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "SUCCEEDED", "end");

        assertProblem(cancelRun(fixture.owner(), runId.toString()), 409, WF_409);
        assertProblem(cancelRun(fixture.member(), runId.toString()), 403, AUTH_403);

        Fixture other = fixture("wfl");
        assertProblem(cancelRun(other.tenantAdmin(), runId.toString()), 404, TENANT_404);
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.cancelled")).isEmpty();
    }

    @Test
    @DisplayName("IT-WF-109 失败节点重试新增 attempt、下游恢复 PENDING，重放不重复")
    void retryRun_failedNode_requeuesAndIsIdempotent() {
        Fixture fixture = fixture("wfl");
        String workflowId = publishWorkflow(fixture, "重试运行");
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID runId = insertFailedRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "draft");
        insertNodeRun(fixture.tenant(), runId, "report", "MODEL", "生成报告", 1, "SKIPPED");
        // 非失败节点可达的下游（模拟条件未走分支）必须在重试后保持 SKIPPED。
        insertNodeRun(fixture.tenant(), runId, "unrelated", "MODEL", "未走分支", 1, "SKIPPED");

        ApiTestClient.ApiResponse response = retryRun(fixture.owner(), runId.toString(), "draft", "retry-key-001");
        assertThat(response.status()).as(response.body()).isEqualTo(202);
        RunRow row = runRow(fixture.tenant(), runId);
        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.currentNodeKey()).isEqualTo("draft");
        assertThat(row.errorCode()).isNull();
        assertThat(row.finishedAt()).isNull();
        assertThat(row.claimAttempts()).isZero();
        assertThat(nodeRows(fixture.tenant(), runId, "draft"))
                .extracting(NodeRow::attempt, NodeRow::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                1, com.riccon.platform.domain.workflow.WorkflowNodeStatus.FAILED),
                        org.assertj.core.groups.Tuple.tuple(
                                2, com.riccon.platform.domain.workflow.WorkflowNodeStatus.PENDING));
        assertThat(nodeKeysByStatus(fixture.tenant(), runId, "PENDING")).contains("report");
        assertThat(nodeKeysByStatus(fixture.tenant(), runId, "SKIPPED")).contains("unrelated");
        var audits = tenantAuditRows(fixture.tenant(), "workflow.run.retried");
        assertThat(audits).hasSize(1);
        JsonNode details = auditDetails(audits.getFirst());
        assertThat(details.path("workflowRunId").asText()).isEqualTo(runId.toString());
        assertThat(details.path("nodeId").asText()).isEqualTo("draft");
        assertThat(details.path("attempt").asInt()).isEqualTo(2);

        ApiTestClient.ApiResponse replay = retryRun(fixture.owner(), runId.toString(), "draft", "retry-key-001");
        assertThat(replay.status()).as(replay.body()).isEqualTo(202);
        assertThat(nodeAttemptCount(fixture.tenant(), runId, "draft")).isEqualTo(2);
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.retried")).hasSize(1);
    }

    @Test
    @DisplayName("IT-WF-109 非失败运行/非失败节点/未知节点返回 409，非法节点与缺失键返回 400")
    void retryRun_invalidStateAndRequest() {
        Fixture fixture = fixture("wfl");
        String workflowId = publishWorkflow(fixture, "重试校验");
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID pendingRun =
                insertActiveRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "PENDING", "start");
        assertProblem(retryRun(fixture.owner(), pendingRun.toString(), "draft", "retry-key-002"), 409, WF_409);

        UUID failedRun = insertFailedRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "draft");
        jdbcClient
                .sql("""
                        UPDATE workflow_node_run SET status = 'SUCCEEDED'
                         WHERE tenant_id = :tenant AND workflow_run_id = :runId AND node_key = 'draft'
                        """)
                .param("tenant", fixture.tenant())
                .param("runId", com.riccon.api.persistence.BinaryUuids.toBytes(failedRun))
                .update();
        assertProblem(retryRun(fixture.owner(), failedRun.toString(), "draft", "retry-key-003"), 409, WF_409);
        assertProblem(retryRun(fixture.owner(), failedRun.toString(), "missing", "retry-key-004"), 409, WF_409);

        ApiTestClient.ApiResponse badNode = client.post(
                "/api/v1/workflow-runs/" + failedRun + "/retry",
                client.jsonBody("nodeId", "bad key"),
                fixture.owner().sessionCookie(),
                fixture.owner().csrfToken(),
                Map.of("Idempotency-Key", "retry-key-005"));
        assertProblem(badNode, 400, WF_400);
        ApiTestClient.ApiResponse missingKey = retryRun(fixture.owner(), failedRun.toString(), "draft", null);
        assertProblem(missingKey, 400, WF_400);
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.retried")).isEmpty();
    }

    @Test
    @DisplayName("IT-WF-109 非发起人重试 403；重试恢复后并发超限 429")
    void retryRun_forbiddenAndRateLimit() {
        Fixture fixture = fixture("wfl");
        String workflowId = publishWorkflow(fixture, "重试权限");
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID failedRun = insertFailedRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "draft");
        assertProblem(retryRun(fixture.member(), failedRun.toString(), "draft", "retry-key-006"), 403, AUTH_403);

        for (int index = 0; index < 5; index++) {
            insertActiveRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "PENDING", "start");
        }
        ApiTestClient.ApiResponse rejected =
                retryRun(fixture.tenantAdmin(), failedRun.toString(), "draft", "retry-key-007");
        assertThat(rejected.status()).as(rejected.body()).isEqualTo(429);
        assertThat(client.json(rejected).path("code").asText()).isEqualTo(WF_429);
        assertThat(runRow(fixture.tenant(), failedRun).status()).isEqualTo("FAILED");
    }
}
