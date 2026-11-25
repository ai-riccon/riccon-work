package com.riccon.worker.workflow.metrics.persistence;

import com.riccon.worker.workflow.metrics.domain.WorkflowApprovalWaitSnapshot;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 待审批等待聚合查询（ADR-0024 §10.2，OPS-M06-007）。
 *
 * <p>审批是租户表，但本查询只返回 {@code COUNT(*)} 与最老等待秒数，不返回任何租户/审批标识或业务内容，
 * 用于 worker 全局告警（最老等待 >24h 提示租户管理员）。这是指标聚合读取，不替代按租户的审批查询。
 *
 * <p>时间口径：连接初始化固定 {@code time_zone='+00:00'}（ADR-0012），{@code created_at} 为 UTC，
 * 因此用 {@code UTC_TIMESTAMP(6)} 计算差值，避免依赖容器时区。
 */
@Component
public class WorkflowApprovalWaitJdbc {

    /** 待审批数量与最老等待秒数；无待审批时 MAX 为 NULL，用 COALESCE 归零。 */
    private static final String WAIT_STATS_SQL = """
            SELECT COUNT(*) AS pending,
                   COALESCE(MAX(TIMESTAMPDIFF(SECOND, created_at, UTC_TIMESTAMP(6))), 0) AS oldest_wait_seconds
              FROM workflow_approval
             WHERE status = 'PENDING'
            """;

    /** JDBC 客户端；Worker 指标查询不打开租户上下文。 */
    private final JdbcClient jdbcClient;

    /**
     * 创建聚合查询。
     *
     * @param jdbcClient JDBC 客户端
     */
    public WorkflowApprovalWaitJdbc(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    /** 读取待审批数量与最老等待秒数。 */
    public WorkflowApprovalWaitSnapshot waitStats() {
        return jdbcClient
                .sql(WAIT_STATS_SQL)
                .query((rs, rowNum) ->
                        new WorkflowApprovalWaitSnapshot(rs.getLong("pending"), Math.max(0L, rs.getLong("oldest_wait_seconds"))))
                .single();
    }
}
