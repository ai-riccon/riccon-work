package com.riccon.api.workflow.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 重试/取消状态机与权限测试（AC-08/15/23，IT-WF-108）。
 *
 * <p>取消与重试的端点由 T05 提供；本任务验证运行历史相关约束：仅发起人/租户管理员、跨租户/平台 404、
 * 已取消幂等、终态 409、取消保留已完成节点结果、重试只新增失败节点尝试且重放不重复。历史与详情
 * 读取方对状态变化的可见性由 {@link WorkflowRunHistoryApiTest} 覆盖。
 */
class WorkflowRunHistoryLifecycleApiTest extends WorkflowHistoryTestSupport {

    @Test
    @DisplayName("IT-WF-108 取消仅发起人/管理员；保留已完成节点；已取消幂等、终态 409、跨租户/平台 404")
    void cancelRun_permissionsAndIdempotency() {
        Fixture fixture = fixture("whl");
        String workflowId = publishHistoryWorkflow(fixture, WORKFLOW_NAME);
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID runId = insertActiveRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "PENDING", "start");
        Instant finished = Instant.now().minusSeconds(5);
        insertRunNode(
                fixture.tenant(),
                runId,
                "start",
                "START",
                "开始",
                1,
                "SUCCEEDED",
                "运行输入：市场调研",
                "已校验输入",
                null,
                null,
                finished.minusSeconds(1),
                finished,
                null,
                null);

        assertProblem(cancelRun(fixture.member(), runId.toString()), 403, AUTH_403);
        assertProblem(cancelRun(fixture.platform(), runId.toString()), 404, TENANT_404);
        Fixture other = fixture("whl");
        assertProblem(cancelRun(other.tenantAdmin(), runId.toString()), 404, TENANT_404);

        assertThat(cancelRun(fixture.owner(), runId.toString()).status()).isEqualTo(202);
        assertThat(runStatus(fixture.tenant(), runId)).isEqualTo("CANCELLED");
        assertThat(nodeKeysByStatus(fixture.tenant(), runId, "SUCCEEDED")).containsExactly("start");
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.cancelled")).hasSize(1);

        assertThat(cancelRun(fixture.tenantAdmin(), runId.toString()).status()).isEqualTo(202);
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.cancelled")).hasSize(1);

        UUID terminalRun =
                insertActiveRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "SUCCEEDED", "end");
        assertProblem(cancelRun(fixture.owner(), terminalRun.toString()), 409, WF_409);
    }

    @Test
    @DisplayName("IT-WF-108 重试仅失败节点/发起人/管理员；新增 attempt 且重放不重复；跨租户/平台 404")
    void retryRun_permissionsAndReplay() {
        Fixture fixture = fixture("whl");
        String workflowId = publishHistoryWorkflow(fixture, WORKFLOW_NAME);
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID failedRun = insertFailedRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "draft");

        assertProblem(retryRun(fixture.member(), failedRun.toString(), "draft", "retry-key-101"), 403, AUTH_403);
        assertProblem(retryRun(fixture.platform(), failedRun.toString(), "draft", "retry-key-102"), 404, TENANT_404);
        Fixture other = fixture("whl");
        assertProblem(retryRun(other.tenantAdmin(), failedRun.toString(), "draft", "retry-key-103"), 404, TENANT_404);

        ApiTestClient.ApiResponse response = retryRun(fixture.owner(), failedRun.toString(), "draft", "retry-key-104");
        assertThat(response.status()).as(response.body()).isEqualTo(202);
        assertThat(runStatus(fixture.tenant(), failedRun)).isEqualTo("PENDING");
        assertThat(latestAttempt(fixture.tenant(), failedRun, "draft")).isEqualTo(2);
        assertThat(nodeRows(fixture.tenant(), failedRun, "draft"))
                .extracting(NodeRow::status)
                .containsExactly(WorkflowNodeStatus.FAILED, WorkflowNodeStatus.PENDING);
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.retried")).hasSize(1);

        ApiTestClient.ApiResponse replay = retryRun(fixture.owner(), failedRun.toString(), "draft", "retry-key-104");
        assertThat(replay.status()).as(replay.body()).isEqualTo(202);
        assertThat(latestAttempt(fixture.tenant(), failedRun, "draft")).isEqualTo(2);
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.retried")).hasSize(1);

        UUID adminRetryRun = insertFailedRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "draft");
        assertThat(retryRun(fixture.tenantAdmin(), adminRetryRun.toString(), "draft", "retry-key-105")
                        .status())
                .isEqualTo(202);
        assertThat(latestAttempt(fixture.tenant(), adminRetryRun, "draft")).isEqualTo(2);
    }

    @Test
    @DisplayName("IT-WF-108 取消后详情仍可读，节点结果与错误码保留，满足审计取证")
    void cancelledRun_detailRemainsReadable() {
        Fixture fixture = fixture("whl");
        String workflowId = publishHistoryWorkflow(fixture, WORKFLOW_NAME);
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        UUID runId = insertActiveRun(fixture.tenant(), workflowId, versionId, 1, fixture.ownerId(), "PENDING", "draft");
        Instant finished = Instant.now().minusSeconds(5);
        insertRunNode(
                fixture.tenant(),
                runId,
                "retrieval",
                "RETRIEVAL",
                "检索知识库",
                1,
                "SUCCEEDED",
                "检索查询：取消保留",
                "检索命中 2 条",
                null,
                null,
                finished.minusSeconds(2),
                finished,
                null,
                null);
        assertThat(cancelRun(fixture.owner(), runId.toString()).status()).isEqualTo(202);

        JsonNode detail = getRunOk(fixture.owner(), runId);
        assertThat(detail.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(detail.path("errorCode").asText()).isEqualTo("WF_CANCELLED");
        assertThat(detail.path("nodes")).hasSize(1);
        assertThat(detail.path("nodes").get(0).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(detail.path("nodes").get(0).path("outputSummary").asText()).isEqualTo("检索命中 2 条");
        assertThat(detail.path("currentNodeId").asText()).isEqualTo("draft");

        // 管理员读取他人已取消运行需写 DETAIL 管理性读取审计。
        getRunOk(fixture.tenantAdmin(), runId);
        List<AuditRow> audits = tenantAuditRows(fixture.tenant(), "workflow.run.admin_read");
        assertThat(audits).hasSize(1);
        assertThat(auditDetails(audits.getFirst()).path("scope").asText()).isEqualTo("DETAIL");
    }

    /**
     * 直接读取节点行状态（测试断言辅助）。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @return 状态列表
     */
    private List<WorkflowNodeStatus> nodeStatuses(String tenant, UUID runId, String nodeKey) {
        return jdbcClient
                .sql("""
                        SELECT status FROM workflow_node_run
                         WHERE tenant_id = :tenant AND workflow_run_id = :runId AND node_key = :nodeKey
                         ORDER BY attempt ASC
                        """)
                .param("tenant", tenant)
                .param("runId", BinaryUuids.toBytes(runId))
                .param("nodeKey", nodeKey)
                .query(WorkflowNodeStatus.class)
                .list();
    }
}
