package com.riccon.worker.run.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.support.TestDataFactory;
import com.riccon.worker.support.TestDocuments;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** Outbox 跨租户领取、租约与状态推进集成测试。 */
class OutboxClaimerTest extends WorkerIntegrationTestBase {

    /** 被测领取器。 */
    @Autowired
    private OutboxClaimer outboxClaimer;

    /** claim 跨租户领取事件；每个测试先清空残留 outbox，保证批量断言可重复。 */
    @BeforeEach
    void clearStaleOutbox() {
        clearOutbox();
    }

    /** 领取 PENDING 事件后置为 DISPATCHED 并递增 attempts。 */
    @Test
    void claim_pendingEvent_marksDispatched() {
        Published published = publishFileParse("tenant-a");

        List<ClaimedOutboxEvent> claimed = outboxClaimer.claim("owner-1", 10, Duration.ofSeconds(30));

        assertThat(claimed).hasSize(1);
        ClaimedOutboxEvent event = claimed.get(0);
        assertThat(event.id()).isEqualTo(published.event().eventId());
        assertThat(event.tenantId()).isEqualTo(published.tenant());
        assertThat(event.runId()).isEqualTo(published.event().runId());
        assertThat(event.attempts()).isEqualTo(1);
        assertThat(outboxRow(event.id()))
                .containsEntry("status", "DISPATCHED")
                .containsEntry("attempts", 1)
                .containsEntry("lease_owner", "owner-1");
    }

    /** 同一批事件不会被第二个 Worker 立即重复领取。 */
    @Test
    void claim_sameEventTwice_secondCallEmpty() {
        publishFileParse("tenant-a");
        assertThat(outboxClaimer.claim("owner-1", 10, Duration.ofSeconds(30))).hasSize(1);

        assertThat(outboxClaimer.claim("owner-2", 10, Duration.ofSeconds(30))).isEmpty();
    }

    /** 租约过期后可以被重新领取，attempts 递增。 */
    @Test
    void claim_expiredLease_reclaimsAndIncrementsAttempts() {
        Published published = publishFileParse("tenant-a");
        jdbcClient
                .sql("""
                        UPDATE agent_run_outbox
                           SET status = 'DISPATCHED', attempts = 1,
                               lease_until = DATE_SUB(NOW(6), INTERVAL 1 SECOND),
                               lease_owner = 'dead-worker'
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", published.tenant())
                .param("id", UuidBinary.toBytes(published.event().eventId()))
                .update();

        List<ClaimedOutboxEvent> claimed = outboxClaimer.claim("owner-2", 10, Duration.ofSeconds(30));

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).attempts()).isEqualTo(2);
        assertThat(outboxRow(published.event().eventId()))
                .containsEntry("status", "DISPATCHED")
                .containsEntry("attempts", 2)
                .containsEntry("lease_owner", "owner-2");
    }

    /** 未到期租约不能被抢占。 */
    @Test
    void claim_futureLease_skips() {
        Published published = publishFileParse("tenant-a");
        jdbcClient
                .sql("""
                        UPDATE agent_run_outbox
                           SET status = 'DISPATCHED', lease_until = DATE_ADD(NOW(6), INTERVAL 60 SECOND),
                               lease_owner = 'busy-worker'
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", published.tenant())
                .param("id", UuidBinary.toBytes(published.event().eventId()))
                .update();

        assertThat(outboxClaimer.claim("owner-2", 10, Duration.ofSeconds(30))).isEmpty();
    }

    /** 未到 available_at 的重试事件不能被提前领取。 */
    @Test
    void claim_futureAvailableAt_skips() {
        Published published = publishFileParse("tenant-a");
        jdbcClient
                .sql("""
                        UPDATE agent_run_outbox
                           SET available_at = DATE_ADD(NOW(6), INTERVAL 60 SECOND)
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", published.tenant())
                .param("id", UuidBinary.toBytes(published.event().eventId()))
                .update();

        assertThat(outboxClaimer.claim("owner-1", 10, Duration.ofSeconds(30))).isEmpty();
    }

    /** 批量上限生效。 */
    @Test
    void claim_batchSize_isRespected() {
        for (int i = 0; i < 3; i++) {
            publishFileParse("tenant-a-" + i);
        }
        assertThat(outboxClaimer.claim("owner-1", 2, Duration.ofSeconds(30))).hasSize(2);
    }

    /** 成功完成只允许当前租约持有者推进。 */
    @Test
    void markDone_updatesOnlyLeaseOwner() {
        Published published = publishFileParse("tenant-a");
        ClaimedOutboxEvent event =
                outboxClaimer.claim("owner-1", 10, Duration.ofSeconds(30)).get(0);

        assertThat(outboxClaimer.markDone(published.tenant(), event.id(), "owner-2"))
                .isZero();
        assertThat(outboxClaimer.markDone(published.tenant(), event.id(), "owner-1"))
                .isEqualTo(1);
        assertThat(outboxRow(event.id())).containsEntry("status", "DONE");
    }

    /** 可重试失败回到 PENDING 并保留原因。 */
    @Test
    void markRetry_returnsToPendingWithAvailableAt() {
        Published published = publishFileParse("tenant-a");
        ClaimedOutboxEvent event =
                outboxClaimer.claim("owner-1", 10, Duration.ofSeconds(30)).get(0);
        Instant availableAt = Instant.now().plusSeconds(30);

        assertThat(outboxClaimer.markRetry(
                        published.tenant(), event.id(), "owner-1", availableAt, "RICCON-FILE-503: storage down"))
                .isEqualTo(1);

        Map<String, Object> row = outboxRow(event.id());
        assertThat(row).containsEntry("status", "PENDING");
        assertThat((String) row.get("last_error")).contains("RICCON-FILE-503");
        assertThat((Instant) row.get("available_at")).isAfter(Instant.now());
    }

    /** 不可重试失败进入 DEAD。 */
    @Test
    void markDead_setsDead() {
        Published published = publishFileParse("tenant-a");
        ClaimedOutboxEvent event =
                outboxClaimer.claim("owner-1", 10, Duration.ofSeconds(30)).get(0);

        assertThat(outboxClaimer.markDead(published.tenant(), event.id(), "owner-1", "unsupported"))
                .isEqualTo(1);
        assertThat(outboxRow(event.id())).containsEntry("status", "DEAD");
    }

    /** 同一租户的幂等键不能重复插入。 */
    @Test
    void createOutbox_duplicateIdempotencyKey_fails() {
        Published published = publishFileParse("tenant-a");
        assertThatThrownBy(() -> data.createOutbox(
                        published.tenant(),
                        published.event().runId(),
                        com.riccon.platform.domain.contract.EventTypes.FILE_PARSE_REQUESTED,
                        published.event().payload(),
                        "file:" + published.fileId() + ":parse:1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 无界批量参数必须拒绝。 */
    @Test
    void claim_invalidBatchSize_throws() {
        assertThatThrownBy(() -> outboxClaimer.claim("owner", 0, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 创建文件解析事件；每次生成唯一租户，避免与历史事件/运行冲突。
     *
     * @param tenantPrefix 可读租户前缀
     * @return 事件与文件信息
     */
    private Published publishFileParse(String tenantPrefix) {
        String tenant = uniqueTenant(tenantPrefix);
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        UUID workspaceId = data.createWorkspace(tenant, userId);
        UUID fileId = data.createFile(tenant, workspaceId, userId, FileFormat.PDF, TestDocuments.pdf(1));
        UUID parseId = data.createParse(tenant, fileId, 1);
        TestDataFactory.PublishedEvent event = data.publishFileParse(tenant, fileId, parseId, userId, 1);
        return new Published(tenant, fileId, event);
    }

    /**
     * 查询 outbox 行关键字段。
     *
     * @param eventId 事件 ID
     * @return 字段映射
     */
    private Map<String, Object> outboxRow(UUID eventId) {
        return jdbcClient
                .sql("""
                        SELECT status, attempts, lease_owner, last_error, available_at
                          FROM agent_run_outbox
                         WHERE id = :id
                        """)
                .param("id", UuidBinary.toBytes(eventId))
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new java.util.HashMap<>();
                    row.put("status", rs.getString("status"));
                    row.put("attempts", rs.getInt("attempts"));
                    row.put("lease_owner", String.valueOf(rs.getString("lease_owner")));
                    row.put("last_error", String.valueOf(rs.getString("last_error")));
                    row.put(
                            "available_at",
                            rs.getObject("available_at", java.time.LocalDateTime.class)
                                    .toInstant(java.time.ZoneOffset.UTC));
                    return row;
                })
                .single();
    }

    /**
     * 测试内部事件包装。
     *
     * @param tenant 租户
     * @param fileId 文件 ID
     * @param event 已发布事件
     */
    private record Published(String tenant, UUID fileId, TestDataFactory.PublishedEvent event) {}
}
