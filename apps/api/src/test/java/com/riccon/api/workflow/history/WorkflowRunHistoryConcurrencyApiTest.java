package com.riccon.api.workflow.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.domain.audit.AuditActions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 取消与审批决定的并发竞态测试（REV-TASK-0229-001 F-004）。
 *
 * <p>覆盖：取消后决定原子失败、审批仍 PENDING 但运行已取消时的 {@code RUN_STATE_CONFLICT}、以及取消与
 * 批准并发时“要么决定成功且运行最终取消、要么决定 409 且审批作废”的两种合法结果；断言不出现
 * “已取消运行被审批恢复”或重复成功审计。
 */
class WorkflowRunHistoryConcurrencyApiTest extends WorkflowHistoryTestSupport {

    @Test
    @DisplayName("IT-WF-108 取消后决定返回 409，审批作废且不写成功审计")
    void decideAfterCancel_returnsConflict() {
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

        assertThat(cancelRun(fixture.owner(), runId.toString()).status()).isEqualTo(202);
        assertProblem(
                decideApproval(fixture.otherEditor(), approvalId, "APPROVE", "同意", "cancel-then-decide-001"),
                409,
                WF_409);
        assertThat(approvalRow(fixture.tenant(), approvalId).status()).isEqualTo("CANCELLED");
        assertThat(runStatus(fixture.tenant(), runId)).isEqualTo("CANCELLED");
        assertThat(tenantAuditRows(fixture.tenant(), AuditActions.WORKFLOW_APPROVAL_DECIDED))
                .isEmpty();
    }

    @Test
    @DisplayName("IT-WF-108 审批仍 PENDING 但运行已取消时返回 RUN_STATE_CONFLICT 409")
    void decideWhenRunCancelled_returnsRunStateConflict() {
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
        // 模拟并发事务交错：运行已取消，但审批行尚未被取消。
        jdbcClient
                .sql("UPDATE workflow_run SET status = 'CANCELLED', error_code = 'WF_CANCELLED',"
                        + " finished_at = NOW(6) WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", fixture.tenant())
                .param("id", BinaryUuids.toBytes(runId))
                .update();

        assertProblem(
                decideApproval(fixture.otherEditor(), approvalId, "APPROVE", "同意", "cancel-race-001"), 409, WF_409);
        assertThat(approvalRow(fixture.tenant(), approvalId).status()).isEqualTo("PENDING");
        assertThat(runStatus(fixture.tenant(), runId)).isEqualTo("CANCELLED");
        assertThat(tenantAuditRows(fixture.tenant(), AuditActions.WORKFLOW_APPROVAL_DECIDED))
                .isEmpty();
    }

    @Test
    @DisplayName("IT-WF-108 取消与批准并发：决定 200 或 409，最终状态与审计一致且不复活已取消运行")
    void concurrentCancelAndDecide_consistentOutcome() throws Exception {
        for (int index = 0; index < 3; index++) {
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

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            int decideStatus;
            try {
                Future<ApiTestClient.ApiResponse> cancelFuture = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return cancelRun(fixture.owner(), runId.toString());
                });
                Future<ApiTestClient.ApiResponse> decideFuture = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return decideApproval(
                            fixture.otherEditor(), approvalId, "APPROVE", "并发同意", "cancel-race-" + UUID.randomUUID());
                });
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                ApiTestClient.ApiResponse cancelResponse = cancelFuture.get(15, TimeUnit.SECONDS);
                ApiTestClient.ApiResponse decideResponse = decideFuture.get(15, TimeUnit.SECONDS);
                assertThat(cancelResponse.status()).as(cancelResponse.body()).isEqualTo(202);
                decideStatus = decideResponse.status();
                assertThat(decideStatus).isIn(200, 409);
            } finally {
                pool.shutdownNow();
            }

            String finalRunStatus = runStatus(fixture.tenant(), runId);
            String finalApprovalStatus =
                    approvalRow(fixture.tenant(), approvalId).status();
            List<AuditRow> decidedAudits = tenantAuditRows(fixture.tenant(), AuditActions.WORKFLOW_APPROVAL_DECIDED);
            if (decideStatus == 200) {
                assertThat(finalApprovalStatus).isEqualTo("APPROVED");
                assertThat(finalRunStatus).isIn("PENDING", "CANCELLED");
                assertThat(decidedAudits).hasSize(1);
                assertThat(decidedAudits.getFirst().outcome()).isEqualTo("SUCCESS");
            } else {
                assertThat(finalApprovalStatus).isEqualTo("CANCELLED");
                assertThat(finalRunStatus).isEqualTo("CANCELLED");
                assertThat(decidedAudits).isEmpty();
            }
            assertThat(finalRunStatus).isNotEqualTo("WAITING_APPROVAL");
        }
    }
}
