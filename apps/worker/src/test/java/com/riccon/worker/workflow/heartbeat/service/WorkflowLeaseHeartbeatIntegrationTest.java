package com.riccon.worker.workflow.heartbeat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.workflow.heartbeat.domain.WorkflowLease;
import com.riccon.worker.workflow.queue.config.WorkflowQueueProperties;
import com.riccon.worker.workflow.queue.domain.ClaimedWorkflowRun;
import com.riccon.worker.workflow.queue.persistence.WorkflowRunClaimer;
import com.riccon.worker.workflow.queue.support.WorkflowQueueTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * IT-WF-103 / OPS-M06-002：租约心跳与独立线程池（真实 MySQL + Spring 装配）。
 *
 * <p>验证领取后同步首次续期把租约延长到部署值、租约被重领时置 lost 并计数、运行取消时置
 * cancelled 且不误报丢失，以及槽位池/心跳池确实独立且为守护线程。
 */
class WorkflowLeaseHeartbeatIntegrationTest extends WorkerIntegrationTestBase {

    /** 被测心跳。 */
    @Autowired
    private WorkflowLeaseHeartbeat heartbeat;

    /** 领取器，用于构造真实领取租约。 */
    @Autowired
    private WorkflowRunClaimer claimer;

    /** 部署配置。 */
    @Autowired
    private WorkflowQueueProperties properties;

    /** Micrometer 注册表。 */
    @Autowired
    private MeterRegistry meterRegistry;

    /** 运行槽位池。 */
    @Autowired
    @Qualifier("workflowRunSlotExecutor")
    private ScheduledExecutorService slotExecutor;

    /** 心跳池。 */
    @Autowired
    @Qualifier("workflowHeartbeatExecutor")
    private ScheduledExecutorService heartbeatExecutor;

    /** 测试夹具。 */
    private WorkflowQueueTestSupport support;

    /** 每个测试重建夹具。 */
    @BeforeEach
    void setUp() {
        support = new WorkflowQueueTestSupport(jdbcClient);
        support.drainPendingRuns();
    }

    /** 领取后首次续期把租约延长到 90s。 */
    @Test
    @DisplayName("领取后立即续期到部署租约")
    void start_renewsLease() {
        Fixture fixture = fixture();
        ClaimedWorkflowRun claim =
                claimer.claim("owner-1", Duration.ofSeconds(5)).orElseThrow();

        WorkflowLease lease = heartbeat.start(claim);

        assertThat(lease.lost()).isFalse();
        assertThat(lease.cancelled()).isFalse();
        LocalDateTime leaseUntil =
                support.readRun(fixture.tenant, fixture.runId).leaseUntil();
        assertThat(leaseUntil).isAfter(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(60));
        lease.close();
        assertThat(lease.stopped()).isTrue();
    }

    /** 续期 0 行且仍 RUNNING：租约丢失，写 lease_lost 指标。 */
    @Test
    @DisplayName("租约被重领时置 lost")
    void start_leaseTakenOver_marksLost() {
        Fixture fixture = fixture();
        ClaimedWorkflowRun claim =
                claimer.claim("owner-1", Duration.ofSeconds(5)).orElseThrow();
        jdbcClient
                .sql("""
                        UPDATE workflow_run SET lease_owner = 'intruder'
                         WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("tenantId", fixture.tenant)
                .param("runId", com.riccon.worker.persistence.UuidBinary.toBytes(fixture.runId))
                .update();
        double before = counter("riccon_workflow_lease_lost_total");

        WorkflowLease lease = heartbeat.start(claim);

        assertThat(lease.lost()).isTrue();
        assertThat(lease.stopped()).isTrue();
        assertThat(counter("riccon_workflow_lease_lost_total")).isEqualTo(before + 1.0);
        assertThat(support.readRun(fixture.tenant, fixture.runId).leaseOwner()).isEqualTo("intruder");
    }

    /** 运行已取消：置 cancelled，不增加 lease_lost。 */
    @Test
    @DisplayName("运行取消时置 cancelled 且不误报丢失")
    void start_cancelled_marksCancelled() {
        Fixture fixture = fixture();
        ClaimedWorkflowRun claim =
                claimer.claim("owner-1", Duration.ofSeconds(5)).orElseThrow();
        jdbcClient
                .sql("""
                        UPDATE workflow_run
                           SET status = 'CANCELLED', lease_owner = NULL, lease_until = NULL
                         WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("tenantId", fixture.tenant)
                .param("runId", com.riccon.worker.persistence.UuidBinary.toBytes(fixture.runId))
                .update();
        double before = counter("riccon_workflow_lease_lost_total");

        WorkflowLease lease = heartbeat.start(claim);

        assertThat(lease.cancelled()).isTrue();
        assertThat(lease.lost()).isFalse();
        assertThat(counter("riccon_workflow_lease_lost_total")).isEqualTo(before);
    }

    /** 槽位池与心跳池独立，大小按配置且为守护线程。 */
    @Test
    @DisplayName("槽位池与心跳池独立")
    void pools_areIndependent() {
        assertThat(slotExecutor).isNotSameAs(heartbeatExecutor);
        assertThat(((ScheduledThreadPoolExecutor) slotExecutor).getCorePoolSize())
                .isEqualTo(properties.schedulerPoolSize());
        assertThat(((ScheduledThreadPoolExecutor) heartbeatExecutor).getCorePoolSize())
                .isEqualTo(properties.heartbeatConcurrency());
        Thread slotThread =
                ((ScheduledThreadPoolExecutor) slotExecutor).getThreadFactory().newThread(() -> {});
        Thread heartbeatThread = ((ScheduledThreadPoolExecutor) heartbeatExecutor)
                .getThreadFactory()
                .newThread(() -> {});
        assertThat(slotThread.getName()).startsWith("riccon-wf-slot-");
        assertThat(heartbeatThread.getName()).startsWith("riccon-wf-heartbeat-");
        assertThat(slotThread.isDaemon()).isTrue();
        assertThat(heartbeatThread.isDaemon()).isTrue();
    }

    /**
     * 创建 PENDING 运行夹具。
     *
     * @return 夹具
     */
    private Fixture fixture() {
        String tenant = uniqueTenant("wf-hb");
        support.createTenant(tenant);
        UUID user = support.createUser(tenant);
        WorkflowQueueTestSupport.WorkflowSeed seed = support.createWorkflow(tenant, user);
        UUID runId = support.insertRun(
                tenant, seed, user, "PENDING", 0, null, null, WorkflowQueueTestSupport.CURRENT_NODE_KEY);
        return new Fixture(tenant, runId);
    }

    /**
     * 读取无标签 Counter。
     *
     * @param name 指标名
     * @return 计数
     */
    private double counter(String name) {
        Counter counter = meterRegistry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /**
     * 测试夹具。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     */
    private record Fixture(String tenant, UUID runId) {}
}
