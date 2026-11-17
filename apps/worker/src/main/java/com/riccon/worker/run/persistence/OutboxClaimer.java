package com.riccon.worker.run.persistence;

import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 跨租户 outbox 领取器（ADR-0016 唯一例外）。
 *
 * <p>只读取 outbox 的允许列，不读取任何业务内容；SELECT 与状态更新在同一事务内完成，
 * 使用 {@code FOR UPDATE SKIP LOCKED} 避免多个 Worker 抢同一行。领取本身不写业务审计。
 *
 * <p>租约到期后由同一查询回收；调用方必须在租户作用域内校验 {@code agent_run.tenant_id}
 * 与运行类别，不能把 outbox 的 tenant_id 当作已可信输入。
 */
@Component
public class OutboxClaimer {

    /** 允许跨租户读取的列，顺序与 {@link #mapRow(ResultSet, int)} 一致。 */
    private static final String CLAIM_SQL = """
            SELECT id, tenant_id, run_id, event_type, event_version, idempotency_key,
                   payload, attempts, available_at, lease_until
              FROM agent_run_outbox
             WHERE (status = 'PENDING' AND available_at <= NOW(6))
                OR (status = 'DISPATCHED' AND lease_until <= NOW(6))
             ORDER BY id
             LIMIT :batchSize
             FOR UPDATE SKIP LOCKED
            """;

    /** 领取后写入租约；attempts 在同一语句内递增，避免额外往返。 */
    private static final String CLAIM_UPDATE_SQL = """
            UPDATE agent_run_outbox
               SET status = 'DISPATCHED',
                   attempts = attempts + 1,
                   lease_until = DATE_ADD(NOW(6), INTERVAL :leaseSeconds SECOND),
                   lease_owner = :leaseOwner
             WHERE tenant_id = :tenantId
               AND id = :id
            """;

    /** 成功完成，只允许当前租约持有者推进。 */
    private static final String MARK_DONE_SQL = """
            UPDATE agent_run_outbox
               SET status = 'DONE', lease_until = NULL, lease_owner = NULL, last_error = NULL
             WHERE tenant_id = :tenantId
               AND id = :id
               AND status = 'DISPATCHED'
               AND lease_owner = :leaseOwner
            """;

    /** 可重试失败：回到 PENDING 并写入下次可用时间。 */
    private static final String MARK_RETRY_SQL = """
            UPDATE agent_run_outbox
               SET status = 'PENDING',
                   available_at = :availableAt,
                   lease_until = NULL,
                   lease_owner = NULL,
                   last_error = :lastError
             WHERE tenant_id = :tenantId
               AND id = :id
               AND status = 'DISPATCHED'
               AND lease_owner = :leaseOwner
            """;

    /** 不可重试或超过上限：进入 DEAD，等待人工重放。 */
    private static final String MARK_DEAD_SQL = """
            UPDATE agent_run_outbox
               SET status = 'DEAD',
                   lease_until = NULL,
                   lease_owner = NULL,
                   last_error = :lastError
             WHERE tenant_id = :tenantId
               AND id = :id
               AND status = 'DISPATCHED'
               AND lease_owner = :leaseOwner
            """;

    /** last_error 列长度。 */
    private static final int MAX_ERROR_LENGTH = 500;

    /** 跨租户领取使用的 JDBC 客户端。 */
    private final JdbcClient jdbcClient;

    /** 保证 SELECT 与 UPDATE 原子提交。 */
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建 outbox 领取器。
     *
     * @param jdbcClient Spring JDBC 客户端
     * @param transactionManager 事务管理器
     */
    public OutboxClaimer(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
    }

    /**
     * 领取一批可执行事件。
     *
     * @param leaseOwner 领取者实例标识
     * @param batchSize 批量上限
     * @param leaseDuration 租约时长
     * @return 已置为 DISPATCHED 的事件；没有可领取行时返回空列表
     */
    public List<ClaimedOutboxEvent> claim(String leaseOwner, int batchSize, Duration leaseDuration) {
        Objects.requireNonNull(leaseOwner, "leaseOwner must not be null");
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        long leaseSeconds = Math.max(1, leaseDuration.toSeconds());
        List<ClaimedOutboxEvent> claimed = transactionTemplate.execute(status -> {
            List<ClaimedOutboxEvent> rows = jdbcClient
                    .sql(CLAIM_SQL)
                    .param("batchSize", batchSize)
                    .query(this::mapRow)
                    .list();
            Instant newLeaseUntil = Instant.now().plus(leaseDuration);
            for (ClaimedOutboxEvent row : rows) {
                jdbcClient
                        .sql(CLAIM_UPDATE_SQL)
                        .param("leaseOwner", leaseOwner)
                        .param("leaseSeconds", leaseSeconds)
                        .param("tenantId", row.tenantId())
                        .param("id", UuidBinary.toBytes(row.id()))
                        .update();
            }
            // 返回本次租约的近似到期时间；数据库使用 NOW(6)，允许毫秒级偏差。
            return rows.stream()
                    .map(row -> new ClaimedOutboxEvent(
                            row.id(),
                            row.tenantId(),
                            row.runId(),
                            row.eventType(),
                            row.eventVersion(),
                            row.idempotencyKey(),
                            row.payload(),
                            row.attempts(),
                            row.availableAt(),
                            newLeaseUntil))
                    .toList();
        });
        return claimed == null ? List.of() : claimed;
    }

    /**
     * 标记事件成功完成。
     *
     * @param tenantId 事件租户
     * @param eventId 事件 ID
     * @param leaseOwner 当前租约持有者
     * @return 受影响行数；0 表示租约已丢失
     */
    public int markDone(String tenantId, UUID eventId, String leaseOwner) {
        return jdbcClient
                .sql(MARK_DONE_SQL)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(eventId))
                .param("leaseOwner", leaseOwner)
                .update();
    }

    /**
     * 标记可重试失败，计算下一次可用时间。
     *
     * @param tenantId 事件租户
     * @param eventId 事件 ID
     * @param leaseOwner 当前租约持有者
     * @param availableAt 下次可领取时间
     * @param lastError 脱敏后的失败原因
     * @return 受影响行数
     */
    public int markRetry(String tenantId, UUID eventId, String leaseOwner, Instant availableAt, String lastError) {
        return jdbcClient
                .sql(MARK_RETRY_SQL)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(eventId))
                .param("leaseOwner", leaseOwner)
                .param("availableAt", LocalDateTime.ofInstant(availableAt, ZoneOffset.UTC))
                .param("lastError", truncate(lastError))
                .update();
    }

    /**
     * 标记不可重试失败。
     *
     * @param tenantId 事件租户
     * @param eventId 事件 ID
     * @param leaseOwner 当前租约持有者
     * @param lastError 脱敏后的失败原因
     * @return 受影响行数
     */
    public int markDead(String tenantId, UUID eventId, String leaseOwner, String lastError) {
        return jdbcClient
                .sql(MARK_DEAD_SQL)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(eventId))
                .param("leaseOwner", leaseOwner)
                .param("lastError", truncate(lastError))
                .update();
    }

    /**
     * 映射 outbox 行；attempts 在领取更新后递增，因此对外返回 attempts + 1。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 领取事件
     * @throws SQLException 列读取失败
     */
    private ClaimedOutboxEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        Instant availableAt = toInstant(rs.getObject("available_at", LocalDateTime.class));
        LocalDateTime leaseUntil = rs.getObject("lease_until", LocalDateTime.class);
        return new ClaimedOutboxEvent(
                UuidBinary.fromBytes(rs.getBytes("id")),
                rs.getString("tenant_id"),
                UuidBinary.fromBytes(rs.getBytes("run_id")),
                rs.getString("event_type"),
                rs.getInt("event_version"),
                rs.getString("idempotency_key"),
                rs.getString("payload"),
                rs.getInt("attempts") + 1,
                availableAt,
                leaseUntil == null ? null : toInstant(leaseUntil));
    }

    /**
     * 将数据库 UTC 时间转成 Instant。
     *
     * @param value 数据库时间
     * @return UTC 时间；null 原样返回
     */
    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    /**
     * 截断错误信息，避免超过列长度导致状态更新失败。
     *
     * @param value 错误信息
     * @return 最长 500 字符
     */
    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
