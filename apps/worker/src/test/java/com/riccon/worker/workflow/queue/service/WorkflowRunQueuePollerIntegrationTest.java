package com.riccon.worker.workflow.queue.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.workflow.heartbeat.domain.WorkflowLease;
import com.riccon.worker.workflow.heartbeat.service.WorkflowLeaseHeartbeat;
import com.riccon.worker.workflow.queue.config.WorkflowQueueProperties;
import com.riccon.worker.workflow.queue.domain.ClaimedWorkflowRun;
import com.riccon.worker.workflow.queue.domain.WorkflowRunExecutor;
import com.riccon.worker.workflow.queue.repository.WorkflowRunQueueRepository;
import com.riccon.worker.workflow.queue.support.WorkflowQueueTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * IT-WF-103：队列服务端到端领取编排（真实 MySQL）。
 *
 * <p>覆盖合法租户在租户上下文内执行、先心跳后续期、重复轮询不二次执行、非法租户 fail-closed
 * 与 platform denied 审计、claim_attempts 耗尽的 WF_INTERNAL 终态（含节点失败尝试创建）与指标。
 */
class WorkflowRunQueuePollerIntegrationTest extends WorkerIntegrationTestBase {

    /** 队列仓储。 */
    @Autowired
    private WorkflowRunQueueRepository repository;

    /** 租约心跳。 */
    @Autowired
    private WorkflowLeaseHeartbeat heartbeat;

    /** 部署配置。 */
    @Autowired
    private WorkflowQueueProperties properties;

    /** 指标。 */
    @Autowired
    private WorkflowQueueMetrics metrics;

    /** 审计。 */
    @Autowired
    private WorkflowQueueAuditor auditor;

    /** Micrometer 注册表。 */
    @Autowired
    private MeterRegistry meterRegistry;

    /** UTC 时钟。 */
    @Autowired
    private Clock clock;

    /** 测试夹具。 */
    private WorkflowQueueTestSupport support;

    /** 记录执行器。 */
    private RecordingExecutor executor;

    /** 被测轮询器。 */
    private WorkflowRunQueuePoller poller;

    /** 每个测试重建夹具与轮询器。 */
    @BeforeEach
    void setUp() {
        support = new WorkflowQueueTestSupport(jdbcClient);
        support.drainPendingRuns();
        executor = new RecordingExecutor();
        poller = new WorkflowRunQueuePoller(repository, heartbeat, properties, metrics, auditor, clock, executor);
    }

    /** 合法租户：在租户上下文内执行，租约由心跳续期到 90s。 */
    @Test
    @DisplayName("合法运行在租户上下文执行且心跳先启动")
    void validRun_executesInsideTenantWithLease() {
        Fixture fixture = fixture(uniqueTenant("wf-poll"));
        assertThat(support.readRun(fixture.tenant, fixture.runId).status()).isEqualTo("PENDING");
        assertThat(properties.claimEnabled()).isTrue();
        assertThat(poller.executorAvailable()).isTrue();

        assertThat(poller.pollOnce(0)).isTrue();

        assertThat(executor.runs).containsExactly(fixture.runId);
        assertThat(executor.tenantContextPresent).isTrue();
        assertThat(executor.leaseTenant).isEqualTo(fixture.tenant);
        assertThat(executor.lastLease.stopped()).isTrue();
        WorkflowQueueTestSupport.RunRow row = support.readRun(fixture.tenant, fixture.runId);
        assertThat(row.status()).isEqualTo("RUNNING");
        assertThat(row.leaseOwner()).isEqualTo(poller.leaseOwner(0));
        assertThat(row.leaseUntil()).isAfter(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(60));
        assertThat(TenantContext.get()).isEmpty();
    }

    /** 重复轮询不会再次执行仍在租约内的运行。 */
    @Test
    @DisplayName("重复轮询不二次执行")
    void secondPoll_doesNotExecuteAgain() {
        Fixture fixture = fixture(uniqueTenant("wf-poll"));
        assertThat(poller.pollOnce(0)).isTrue();

        assertThat(poller.pollOnce(0)).isFalse();
        assertThat(executor.runs).containsExactly(fixture.runId);
    }

    /** 非法租户 fail-closed：不执行、写 platform denied 审计与 INVALID_TENANT 指标。 */
    @Test
    @DisplayName("非法租户 fail-closed 并写 denied 审计")
    void invalidTenant_failsClosedWithAuditAndMetric() {
        String tenant = uniqueTenant("bad tenant");
        Fixture fixture = fixture(tenant);
        double before = counter("riccon_workflow_claim_failures_total", "reason", "INVALID_TENANT");

        assertThat(poller.pollOnce(0)).isTrue();

        assertThat(executor.runs).isEmpty();
        assertThat(counter("riccon_workflow_claim_failures_total", "reason", "INVALID_TENANT"))
                .isEqualTo(before + 1.0);
        assertThat(support.countAudit("platform", "tenant.access.denied", "workflow-claim-" + fixture.runId))
                .isEqualTo(1L);
        assertThat(support.readAuditDetails("platform", "tenant.access.denied", "workflow-claim-" + fixture.runId))
                .contains("INVALID_TENANT");
        assertThat(support.readRun(tenant, fixture.runId).status()).isEqualTo("RUNNING");
        assertThat(TenantContext.get()).isEmpty();
    }

    /** 已有节点尝试：claim_attempts 耗尽把运行与节点一起置 FAILED，并写指标/审计。 */
    @Test
    @DisplayName("claim 次数耗尽把运行和节点置失败")
    void claimExhausted_existingNode_marksRunAndNodeFailed() {
        Fixture fixture = fixture(uniqueTenant("wf-exhaust"));
        support.expireLease(fixture.tenant, fixture.runId, 5, "dead-worker", WorkflowQueueTestSupport.CURRENT_NODE_KEY);
        support.insertNodeRun(
                fixture.tenant, fixture.runId, WorkflowQueueTestSupport.CURRENT_NODE_KEY, "MODEL", 1, "RUNNING");
        double before = counter("riccon_workflow_claim_exhausted_total");

        assertThat(poller.pollOnce(0)).isTrue();

        WorkflowQueueTestSupport.RunRow row = support.readRun(fixture.tenant, fixture.runId);
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.errorCode()).isEqualTo("WF_INTERNAL");
        assertThat(row.claimAttempts()).isEqualTo(6);
        assertThat(support.readNodes(fixture.tenant, fixture.runId, WorkflowQueueTestSupport.CURRENT_NODE_KEY))
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.status()).isEqualTo("FAILED");
                    assertThat(node.errorCode()).isEqualTo("WF_INTERNAL");
                });
        assertThat(counter("riccon_workflow_claim_exhausted_total")).isEqualTo(before + 1.0);
        assertThat(support.countAudit(fixture.tenant, "workflow.run.failed", "workflow-claim-" + fixture.runId))
                .isEqualTo(1L);
        assertThat(executor.runs).isEmpty();
    }

    /** 无节点尝试：从发布图快照创建一次失败尝试，保证 POST /retry 可用。 */
    @Test
    @DisplayName("claim 次数耗尽时补建失败节点尝试")
    void claimExhausted_missingNode_createsFailedAttempt() {
        Fixture fixture = fixture(uniqueTenant("wf-exhaust-new"));
        support.expireLease(fixture.tenant, fixture.runId, 5, "dead-worker", WorkflowQueueTestSupport.CURRENT_NODE_KEY);

        assertThat(poller.pollOnce(0)).isTrue();

        assertThat(support.readNodes(fixture.tenant, fixture.runId, WorkflowQueueTestSupport.CURRENT_NODE_KEY))
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.attempt()).isEqualTo(1);
                    assertThat(node.status()).isEqualTo("FAILED");
                    assertThat(node.errorCode()).isEqualTo("WF_INTERNAL");
                    assertThat(node.nodeType()).isEqualTo("MODEL");
                    assertThat(node.nodeName()).isEqualTo("生成草稿");
                });
        assertThat(support.readRun(fixture.tenant, fixture.runId).status()).isEqualTo("FAILED");
    }

    /**
     * 创建 PENDING 运行夹具。
     *
     * @param tenant 租户
     * @return 夹具
     */
    private Fixture fixture(String tenant) {
        support.createTenant(tenant);
        UUID user = support.createUser(tenant);
        WorkflowQueueTestSupport.WorkflowSeed seed = support.createWorkflow(tenant, user);
        UUID runId = support.insertRun(
                tenant, seed, user, "PENDING", 0, null, null, WorkflowQueueTestSupport.CURRENT_NODE_KEY);
        return new Fixture(tenant, runId);
    }

    /**
     * 读取 Counter 当前值。
     *
     * @param name 指标名
     * @param tags 标签键值对
     * @return 计数
     */
    private double counter(String name, String... tags) {
        var search = meterRegistry.find(name);
        for (int index = 0; index < tags.length; index += 2) {
            search = search.tag(tags[index], tags[index + 1]);
        }
        Counter counter = search.counter();
        return counter == null ? 0.0 : counter.count();
    }

    /**
     * 记录执行器：断言租户上下文与租约传递。
     */
    private static final class RecordingExecutor implements WorkflowRunExecutor {

        /** 已执行运行 ID。 */
        private final List<UUID> runs = new CopyOnWriteArrayList<>();

        /** 执行时是否存在租户上下文。 */
        private volatile boolean tenantContextPresent;

        /** 租约租户。 */
        private volatile String leaseTenant;

        /** 最近一次租约句柄。 */
        private volatile WorkflowLease lastLease;

        /** {@inheritDoc} */
        @Override
        public void execute(ClaimedWorkflowRun claim, WorkflowLease lease) {
            runs.add(claim.runId());
            tenantContextPresent = TenantContext.get().isPresent();
            leaseTenant = lease.tenantId();
            lastLease = lease;
        }
    }

    /**
     * 测试夹具。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     */
    private record Fixture(String tenant, UUID runId) {}
}
