package com.riccon.worker.workflow.queue.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.workflow.queue.domain.ClaimedWorkflowRun;
import com.riccon.worker.workflow.queue.support.WorkflowQueueTestSupport;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * IT-WF-103：{@code workflow_run} 持久队列领取、租约与重复领取防护（真实 MySQL）。
 *
 * <p>覆盖 PENDING 领取、租约未到期跳过、过期 RUNNING 重领递增 {@code claim_attempts}、
 * 并发领取同一批运行不重复、PENDING 不被未来租约阻塞，以及非法租户原始字符串只在领取器
 * 层透传（由队列服务 fail-closed）。
 */
class WorkflowRunClaimerIntegrationTest extends WorkerIntegrationTestBase {

    /** 被测领取器。 */
    @Autowired
    private WorkflowRunClaimer claimer;

    /** 测试夹具。 */
    private WorkflowQueueTestSupport support;

    /** 每个测试重建夹具。 */
    @BeforeEach
    void setUp() {
        support = new WorkflowQueueTestSupport(jdbcClient);
        support.drainPendingRuns();
    }

    /** 领取 PENDING 后置 RUNNING、写租约与 started_at，不递增 claim_attempts。 */
    @Test
    @DisplayName("领取 PENDING 运行并写入租约")
    void claim_pendingRun_marksRunning() {
        Fixture fixture = fixture();

        ClaimedWorkflowRun claim =
                claimer.claim("owner-1", Duration.ofSeconds(90)).orElseThrow();

        assertThat(claim.runId()).isEqualTo(fixture.runId);
        assertThat(claim.tenantId()).isEqualTo(fixture.tenant);
        assertThat(claim.previousStatus().name()).isEqualTo("PENDING");
        assertThat(claim.leaseOwner()).isEqualTo("owner-1");
        WorkflowQueueTestSupport.RunRow row = support.readRun(fixture.tenant, fixture.runId);
        assertThat(row.status()).isEqualTo("RUNNING");
        assertThat(row.leaseOwner()).isEqualTo("owner-1");
        assertThat(row.claimAttempts()).isZero();
        assertThat(row.leaseUntil()).isAfter(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(60));
    }

    /** 同一运行不会被第二个实例立即重复领取。 */
    @Test
    @DisplayName("未到期租约不可重复领取")
    void claim_sameRunTwice_secondEmpty() {
        fixture();
        assertThat(claimer.claim("owner-1", Duration.ofSeconds(90))).isPresent();

        assertThat(claimer.claim("owner-2", Duration.ofSeconds(90))).isEmpty();
    }

    /** 租约未到期时跳过；过期 RUNNING 重领并递增 claim_attempts。 */
    @Test
    @DisplayName("租约过期后重领并递增 claim_attempts")
    void claim_expiredLease_reclaimsAndCountsAttempt() {
        Fixture fixture = fixture();
        support.expireLease(fixture.tenant, fixture.runId, 2, "dead-worker", WorkflowQueueTestSupport.CURRENT_NODE_KEY);

        ClaimedWorkflowRun claim =
                claimer.claim("owner-2", Duration.ofSeconds(90)).orElseThrow();

        assertThat(claim.runId()).isEqualTo(fixture.runId);
        assertThat(claim.previousStatus().name()).isEqualTo("RUNNING");
        WorkflowQueueTestSupport.RunRow row = support.readRun(fixture.tenant, fixture.runId);
        assertThat(row.claimAttempts()).isEqualTo(3);
        assertThat(row.leaseOwner()).isEqualTo("owner-2");
    }

    /** RUNNING 且租约未到期不会阻塞后续 PENDING 行。 */
    @Test
    @DisplayName("未来租约的 RUNNING 行不阻塞 PENDING")
    void claim_runningFutureLease_skipsToPending() {
        Fixture blocked = fixture();
        support.expireLease(blocked.tenant, blocked.runId, 0, "busy-worker", WorkflowQueueTestSupport.CURRENT_NODE_KEY);
        jdbcClient
                .sql("""
                        UPDATE workflow_run
                           SET lease_until = DATE_ADD(NOW(6), INTERVAL 60 SECOND)
                         WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("tenantId", blocked.tenant)
                .param("runId", com.riccon.worker.persistence.UuidBinary.toBytes(blocked.runId))
                .update();
        Fixture pending = fixture();

        ClaimedWorkflowRun claim =
                claimer.claim("owner-1", Duration.ofSeconds(90)).orElseThrow();

        assertThat(claim.runId()).isEqualTo(pending.runId);
    }

    /** 并发 6 个槽位领取 6 个运行：总数与去重后数量均为 6。 */
    @Test
    @DisplayName("并发领取不重复")
    void claim_concurrentWorkers_doNotDuplicate() throws Exception {
        List<Fixture> fixtures = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            fixtures.add(fixture());
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<UUID>>> futures = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                int worker = index;
                futures.add(pool.submit(() -> {
                    start.await();
                    List<UUID> owned = new ArrayList<>();
                    // SKIP LOCKED 在并发启动时可能让个别线程先看到全被锁而空手；重试保证 6 行最终都被领走。
                    for (int attempt = 0; attempt < 3; attempt++) {
                        claimer.claim("owner-" + worker, Duration.ofSeconds(90))
                                .ifPresent(claim -> owned.add(claim.runId()));
                    }
                    return owned;
                }));
            }
            start.countDown();
            List<UUID> claimed = new ArrayList<>();
            for (Future<List<UUID>> future : futures) {
                claimed.addAll(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(claimed).hasSize(6);
            assertThat(claimed).doesNotHaveDuplicates();
            for (Fixture fixture : fixtures) {
                assertThat(support.readRun(fixture.tenant, fixture.runId).status())
                        .isEqualTo("RUNNING");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 非法租户字符串由领取器原样返回，租户校验属于队列服务。 */
    @Test
    @DisplayName("非法租户原始值透传给服务层")
    void claim_invalidTenantString_isReturnedRaw() {
        Fixture fixture = fixtureWithTenant(uniqueTenant("bad tenant"));

        ClaimedWorkflowRun claim =
                claimer.claim("owner-1", Duration.ofSeconds(90)).orElseThrow();

        assertThat(claim.tenantId()).isEqualTo(fixture.tenant);
        assertThat(claim.tenantId()).contains(" ");
    }

    /**
     * 创建普通运行夹具。
     *
     * @return 夹具
     */
    private Fixture fixture() {
        return fixtureWithTenant(uniqueTenant("wf-claim"));
    }

    /**
     * 指定租户创建 PENDING 运行夹具。
     *
     * @param tenant 租户
     * @return 夹具
     */
    private Fixture fixtureWithTenant(String tenant) {
        support.createTenant(tenant);
        UUID user = support.createUser(tenant);
        WorkflowQueueTestSupport.WorkflowSeed seed = support.createWorkflow(tenant, user);
        UUID runId = support.insertRun(
                tenant, seed, user, "PENDING", 0, null, null, WorkflowQueueTestSupport.CURRENT_NODE_KEY);
        return new Fixture(tenant, runId);
    }

    /**
     * 测试夹具。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     */
    private record Fixture(String tenant, UUID runId) {}
}
