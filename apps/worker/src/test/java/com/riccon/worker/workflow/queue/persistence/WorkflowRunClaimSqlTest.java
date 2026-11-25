package com.riccon.worker.workflow.queue.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 跨租户领取 SQL 静态守卫（TM-85 / OPS-M06-015）。
 *
 * <p>claim SELECT 只允许 {@code id/tenant_id/status/lease_until} 四列；出现 {@code SELECT *}、
 * 业务列或缺少 {@code FOR UPDATE SKIP LOCKED} 都会扩大租户隔离例外，必须在评审前被本测试拦住。
 * 运行期列集合断言由 IT-WF-103 的真实数据库用例补充。
 */
class WorkflowRunClaimSqlTest {

    /** 列集合恰好 4 列且无业务内容。 */
    @Test
    @DisplayName("领取列集合恰好四列")
    void claimColumns_areExactlyAllowedMetadata() {
        List<String> columns = Arrays.stream(WorkflowRunClaimer.CLAIM_COLUMNS.split(","))
                .map(String::trim)
                .toList();

        assertThat(columns).containsExactly("id", "tenant_id", "status", "lease_until");
        assertThat(columns)
                .doesNotContain(
                        "inputs_json", "result_json", "current_node_key", "workflow_version_id", "requester_user_id");
    }

    /** SQL 不含 SELECT *，且具备单行领取与跳过已锁行。 */
    @Test
    @DisplayName("领取 SQL 使用 SKIP LOCKED 且只领 1 个")
    void claimSql_isSingleRowSkipLocked() {
        assertThat(WorkflowRunClaimer.CLAIM_SQL).doesNotContain("*");
        assertThat(WorkflowRunClaimer.CLAIM_SQL).contains("FOR UPDATE SKIP LOCKED");
        assertThat(WorkflowRunClaimer.CLAIM_SQL).contains("LIMIT 1");
        assertThat(WorkflowRunClaimer.CLAIM_SQL).contains("ORDER BY id");
        assertThat(WorkflowRunClaimer.CLAIM_SQL).contains("status = 'PENDING'");
        assertThat(WorkflowRunClaimer.CLAIM_SQL).contains("status = 'RUNNING' AND lease_until <= NOW(6)");
    }

    /** 条件更新仍包含状态/租约谓词，重复领取不会二次执行。 */
    @Test
    @DisplayName("领取更新包含状态与租约条件")
    void claimUpdate_conditionsPreventDoubleExecution() {
        assertThat(WorkflowRunClaimer.CLAIM_UPDATE_SQL).contains("lease_owner = :leaseOwner");
        assertThat(WorkflowRunClaimer.CLAIM_UPDATE_SQL).contains("claim_attempts = claim_attempts + :claimIncrement");
        assertThat(WorkflowRunClaimer.CLAIM_UPDATE_SQL).contains("status = 'PENDING'");
        assertThat(WorkflowRunClaimer.CLAIM_UPDATE_SQL).contains("lease_until <= NOW(6)");
    }
}
