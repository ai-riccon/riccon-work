package com.riccon.worker.workflow.queue.persistence;

import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.workflow.queue.domain.ClaimedWorkflowRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code workflow_run} 持久队列领取器（ADR-0024 §4.3，TM-85）。
 *
 * <p>这是 ADR-0009/ADR-0016 之外新增的一条受限跨租户领取例外：只允许读取
 * {@code id/tenant_id/status/lease_until} 四列，使用 {@code FOR UPDATE SKIP LOCKED}
 * 与 {@code LIMIT 1} 保证每个槽位一次只领一个运行、并发实例不会重复领取。领取后立即回到
 * 调用方校验租户并打开 {@code TenantContext}，本类绝不读取输入、结果或图内容。
 *
 * <p>租约过期重领（原状态 RUNNING）在同一更新语句内递增 {@code claim_attempts}；
 * PENDING 首次领取与审批恢复/重试不递增。更新条件仍包含状态与租约谓词，租约被并发抢走时
 * 受影响行数为 0，调用方按空领取处理，不产生第二次执行。
 */
@Component
public class WorkflowRunClaimer {

    /** 允许跨租户读取的列，顺序与 {@link #mapRow(ResultSet, int)} 一致；测试静态断言列集合。 */
    static final String CLAIM_COLUMNS = "id, tenant_id, status, lease_until";

    /** 领取语句：两段状态谓词与索引 {@code (status, lease_until, id)} 对齐。 */
    static final String CLAIM_SQL = """
            SELECT %s
              FROM workflow_run
             WHERE ((status = 'PENDING')
                OR (status = 'RUNNING' AND lease_until <= NOW(6)))
             ORDER BY id
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """.formatted(CLAIM_COLUMNS);

    /** 领取成功后的条件更新：状态仍可领取时才写入租约；过期 RUNNING 递增 claim_attempts。 */
    static final String CLAIM_UPDATE_SQL = """
            UPDATE workflow_run
               SET status = 'RUNNING',
                   lease_owner = :leaseOwner,
                   lease_until = DATE_ADD(NOW(6), INTERVAL :leaseSeconds SECOND),
                   claim_attempts = claim_attempts + :claimIncrement,
                   started_at = COALESCE(started_at, NOW(6)),
                   updated_at = NOW(6)
             WHERE tenant_id = :tenantId
               AND id = :id
               AND ((status = 'PENDING')
                 OR (status = 'RUNNING' AND lease_until <= NOW(6)))
            """;

    /** 跨租户领取使用的 JDBC 客户端；不经过 TenantJdbc 是 TM-85 的唯一例外。 */
    private final JdbcClient jdbcClient;

    /** 领取事务模板；SELECT ... FOR UPDATE 必须在事务内。 */
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建领取器。
     *
     * @param jdbcClient Spring JDBC 客户端
     * @param transactionManager 事务管理器
     */
    public WorkflowRunClaimer(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
    }

    /**
     * 领取一个可执行运行。
     *
     * @param leaseOwner 本次租约持有者（实例 + 槽位）
     * @param leaseDuration 租约时长；至少 1 秒
     * @return 已领取运行；队列为空或租约竞争失败时为空
     */
    public Optional<ClaimedWorkflowRun> claim(String leaseOwner, Duration leaseDuration) {
        Objects.requireNonNull(leaseOwner, "leaseOwner must not be null");
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        long leaseSeconds = Math.max(1L, leaseDuration.toSeconds());
        Optional<ClaimedWorkflowRun> claimed = transactionTemplate.execute(status -> {
            Optional<RawClaimRow> row =
                    jdbcClient.sql(CLAIM_SQL).query(this::mapRow).optional();
            if (row.isEmpty()) {
                return Optional.empty();
            }
            RawClaimRow claim = row.get();
            int claimIncrement = claim.status() == WorkflowRunStatus.RUNNING ? 1 : 0;
            int updated = jdbcClient
                    .sql(CLAIM_UPDATE_SQL)
                    .param("leaseOwner", leaseOwner)
                    .param("leaseSeconds", leaseSeconds)
                    .param("claimIncrement", claimIncrement)
                    .param("tenantId", claim.tenantId())
                    .param("id", UuidBinary.toBytes(claim.runId()))
                    .update();
            if (updated == 0) {
                // 行锁竞争或状态在读取后被并发推进；按空领取处理，绝不二次执行。
                return Optional.empty();
            }
            // 数据库使用 NOW(6)；这里返回近似到期时间，心跳会立即用真实续期覆盖。
            Instant leaseUntil = Instant.now().plusSeconds(leaseSeconds);
            return Optional.of(
                    new ClaimedWorkflowRun(claim.runId(), claim.tenantId(), claim.status(), leaseUntil, leaseOwner));
        });
        return claimed == null ? Optional.empty() : claimed;
    }

    /**
     * 映射领取行；只读取 4 个允许列。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 原始领取行
     * @throws SQLException 列读取失败
     */
    private RawClaimRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        LocalDateTime leaseUntil = rs.getObject("lease_until", LocalDateTime.class);
        return new RawClaimRow(
                UuidBinary.fromBytes(rs.getBytes("id")),
                rs.getString("tenant_id"),
                WorkflowRunStatus.valueOf(rs.getString("status")),
                leaseUntil == null ? null : leaseUntil.toInstant(ZoneOffset.UTC));
    }

    /**
     * 领取 SQL 原始行（租户尚未校验）。
     *
     * @param runId 运行 ID
     * @param tenantId 原始租户字符串
     * @param status 领取前状态
     * @param leaseUntil 领取前租约到期；PENDING 为空
     */
    private record RawClaimRow(UUID runId, String tenantId, WorkflowRunStatus status, Instant leaseUntil) {}
}
