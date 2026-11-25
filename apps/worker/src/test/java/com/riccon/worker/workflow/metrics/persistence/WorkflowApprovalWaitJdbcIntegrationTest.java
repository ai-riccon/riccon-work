package com.riccon.worker.workflow.metrics.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.common.UuidV7;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.support.TestSchema;
import com.riccon.worker.workflow.metrics.domain.WorkflowApprovalWaitSnapshot;
import com.riccon.worker.workflow.queue.support.WorkflowQueueTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 待审批等待聚合查询集成测试（OPS-M06-003/OPS-M06-007，真实 MySQL 8.4）。
 *
 * <p>独立一次性容器执行 V1–V15 权威迁移；不启动 Spring/OpenSearch/RustFS。断言只统计 {@code PENDING}
 * 审批，最老等待按 UTC 计算，且已决定/已取消审批不计入。
 */
@Tag("integration")
class WorkflowApprovalWaitJdbcIntegrationTest {

    /** 一次性 MySQL 容器；只本类使用，避免污染共享测试库。 */
    private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4.13"))
            .withDatabaseName("riccon")
            .withUsername("riccon")
            .withPassword("riccon_test_only")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--log-bin-trust-function-creators=1");

    static {
        MYSQL.start();
        TestSchema.applyMigrations(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    /** 显式 JDBC 客户端；连接初始化为 UTC（与生产 worker 一致）。 */
    private final JdbcClient jdbcClient = JdbcClient.create(new DriverManagerDataSource(
            MYSQL.getJdbcUrl() + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?") + "connectionTimeZone=UTC",
            MYSQL.getUsername(),
            MYSQL.getPassword()));

    /** 待审批计数与最老等待秒数。 */
    @Test
    @DisplayName("只统计 PENDING 审批并计算最老等待")
    void waitStats_countsPendingAndOldestWait() {
        WorkflowQueueTestSupport support = new WorkflowQueueTestSupport(jdbcClient);
        String tenant = "wf-approval-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        support.createTenant(tenant);
        UUID requester = support.createUser(tenant);
        WorkflowQueueTestSupport.WorkflowSeed seed = support.createWorkflow(tenant, requester);
        UUID firstRun = support.insertRun(
                tenant, seed, requester, "WAITING_APPROVAL", 0, null, null, "approval-a");
        UUID secondRun = support.insertRun(
                tenant, seed, requester, "WAITING_APPROVAL", 0, null, null, "approval-b");
        insertApproval(tenant, seed, firstRun, requester, "approval-a", "PENDING", 600L);
        insertApproval(tenant, seed, secondRun, requester, "approval-b", "PENDING", 60L);
        insertApproval(tenant, seed, firstRun, requester, "approval-c", "APPROVED", 900L);

        WorkflowApprovalWaitSnapshot snapshot = new WorkflowApprovalWaitJdbc(jdbcClient).waitStats();

        assertThat(snapshot.pending()).isEqualTo(2L);
        // 允许几秒执行偏差；不因毫秒截断把 600 秒算成 599。
        assertThat(snapshot.oldestWaitSeconds()).isBetween(600L, 700L);
    }

    /**
     * 插入审批行。
     *
     * @param tenant 租户
     * @param seed 流程与版本
     * @param runId 运行 ID
     * @param requester 发起人
     * @param nodeKey 审批节点 key
     * @param status 审批状态
     * @param ageSeconds 创建至今秒数
     */
    private void insertApproval(
            String tenant,
            WorkflowQueueTestSupport.WorkflowSeed seed,
            UUID runId,
            UUID requester,
            String nodeKey,
            String status,
            long ageSeconds) {
        jdbcClient
                .sql("""
                        INSERT INTO workflow_approval (
                            tenant_id, id, workflow_run_id, workflow_id, node_key, attempt, status,
                            assignee_type, assignee_role_code, action_kind, params_preview, plan_sha256,
                            requested_by, decided_by, decided_at, created_at, updated_at
                        ) VALUES (
                            :tenant, :id, :runId, :workflowId, :nodeKey, 1, :status,
                            'ROLE', 'TENANT_ADMIN', 'CONTINUE', '{}', REPEAT('a', 64),
                            :requestedBy,
                            CASE WHEN :status = 'APPROVED' THEN :requestedBy ELSE NULL END,
                            CASE WHEN :status = 'APPROVED' THEN UTC_TIMESTAMP(6) ELSE NULL END,
                            DATE_SUB(UTC_TIMESTAMP(6), INTERVAL :age SECOND), UTC_TIMESTAMP(6)
                        )
                        """)
                .param("tenant", tenant)
                .param("id", UuidBinary.toBytes(UuidV7.generate()))
                .param("runId", UuidBinary.toBytes(runId))
                .param("workflowId", UuidBinary.toBytes(seed.workflowId()))
                .param("nodeKey", nodeKey)
                .param("status", status)
                .param("requestedBy", UuidBinary.toBytes(requester))
                .param("age", ageSeconds)
                .update();
    }
}
