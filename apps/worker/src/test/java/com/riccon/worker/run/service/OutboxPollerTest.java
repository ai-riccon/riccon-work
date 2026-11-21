package com.riccon.worker.run.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.platform.domain.research.RunKind;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.config.OutboxProperties;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.domain.EventProcessingException;
import com.riccon.worker.run.domain.OutboxEventHandler;
import com.riccon.worker.run.repository.OutboxRepository;
import com.riccon.worker.run.repository.RunRepository;
import com.riccon.worker.support.TestDocuments;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Outbox 轮询、失败重试与租户隔离测试。 */
class OutboxPollerTest extends WorkerIntegrationTestBase {

    /** 真实轮询器。 */
    @Autowired
    private OutboxPoller poller;

    /** 真实仓库，用于构造自定义 dispatcher 的轮询器。 */
    @Autowired
    private OutboxRepository outboxRepository;

    /** 运行仓库。 */
    @Autowired
    private RunRepository runRepository;

    /** 配置。 */
    @Autowired
    private OutboxProperties properties;

    /** UTC 时钟。 */
    @Autowired
    private Clock clock;

    /** pollOnce 跨租户领取事件；每个测试先清空残留 outbox，保证精确计数。 */
    @BeforeEach
    void clearStaleOutbox() {
        clearOutbox();
    }

    /** 未实现事件安全跳过并进入 DEAD。 */
    @Test
    void pollOnce_unsupportedEvent_marksDead() {
        Seed seed = seedFileRun("tenant-a");
        UUID eventId =
                data.createOutbox(seed.tenant(), seed.runId(), "riccon.agent.run.unknown.v1", "{}", "unsupported-1");

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(outboxStatus(eventId)).isEqualTo("DEAD");
        assertThat(outboxLastError(eventId)).contains("unsupported event type");
        assertThat(runStatus(seed.runId())).isEqualTo("PENDING");
    }

    /** 非法 payload 不可重试，运行失败。 */
    @Test
    void pollOnce_invalidPayload_marksDeadAndFailsRun() {
        Seed seed = seedFileRun("tenant-a");
        UUID eventId =
                data.createOutbox(seed.tenant(), seed.runId(), EventTypes.FILE_PARSE_REQUESTED, "{}", "invalid-1");

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(outboxStatus(eventId)).isEqualTo("DEAD");
        assertThat(outboxLastError(eventId)).contains("RICCON-WORKER-422");
        assertThat(runStatus(seed.runId())).isEqualTo("FAILED");
    }

    /** 运行类别与事件不匹配时拒绝执行。 */
    @Test
    void pollOnce_runKindMismatch_marksDead() {
        String tenant = uniqueTenant("tenant-a");
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        UUID runId = data.createRun(tenant, RunKind.KNOWLEDGE_INDEX, null, userId, 3);
        UUID eventId = data.createOutbox(
                tenant,
                runId,
                EventTypes.FILE_PARSE_REQUESTED,
                data.fileParsePayload(UuidBinary.newV7(), UuidBinary.newV7(), 1),
                "kind-mismatch-1");

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(outboxStatus(eventId)).isEqualTo("DEAD");
        assertThat(outboxLastError(eventId)).contains("kind mismatch");
        assertThat(runStatus(runId)).isEqualTo("PENDING");
    }

    /** 终态运行的重复投递直接完成，不再执行处理器。 */
    @Test
    void pollOnce_terminalRun_marksDone() {
        Seed seed = seedFileRun("tenant-a");
        jdbcClient
                .sql("UPDATE agent_run SET status = 'SUCCEEDED' WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", seed.tenant())
                .param("id", UuidBinary.toBytes(seed.runId()))
                .update();
        UUID eventId =
                data.createOutbox(seed.tenant(), seed.runId(), EventTypes.FILE_PARSE_REQUESTED, "{}", "terminal-1");

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(outboxStatus(eventId)).isEqualTo("DONE");
    }

    /**
     * 声明处理终态运行的处理器仍会执行，且不再推进运行状态。
     *
     * <p>取消事件依赖这一语义：API 先置 CANCELLED 再投递事件，Worker 仍需补齐阶段终态。
     */
    @Test
    void pollOnce_terminalRun_withOptInHandler_dispatchesWithoutStateChange() {
        Seed seed = seedFileRun("tenant-a");
        jdbcClient
                .sql("UPDATE agent_run SET status = 'CANCELLED', finished_at = NOW(6) "
                        + "WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", seed.tenant())
                .param("id", UuidBinary.toBytes(seed.runId()))
                .update();
        UUID eventId = data.createOutbox(
                seed.tenant(), seed.runId(), EventTypes.FILE_PARSE_REQUESTED, "{}", "terminal-optin-1");
        java.util.concurrent.atomic.AtomicInteger handled = new java.util.concurrent.atomic.AtomicInteger();
        OutboxEventHandler reconciler = new OutboxEventHandler() {
            @Override
            public String eventType() {
                return EventTypes.FILE_PARSE_REQUESTED;
            }

            @Override
            public RunKind runKind() {
                return RunKind.FILE_PARSE;
            }

            @Override
            public boolean handlesTerminalRun() {
                return true;
            }

            @Override
            public void handle(ClaimedOutboxEvent event) {
                handled.incrementAndGet();
            }
        };
        OutboxPoller custom = customPoller(reconciler);

        assertThat(custom.pollOnce()).isEqualTo(1);

        assertThat(handled).as("终态运行的收尾事件必须执行处理器").hasValue(1);
        assertThat(outboxStatus(eventId)).isEqualTo("DONE");
        assertThat(runStatus(seed.runId())).as("终态不得被改回 RUNNING").isEqualTo("CANCELLED");
    }

    /** 未到期租约不会被轮询器重复处理。 */
    @Test
    void pollOnce_futureLease_skips() {
        Seed seed = seedFileRun("tenant-a");
        UUID eventId = data.createOutbox(seed.tenant(), seed.runId(), EventTypes.FILE_PARSE_REQUESTED, "{}", "lease-1");
        jdbcClient
                .sql("""
                        UPDATE agent_run_outbox
                           SET status = 'DISPATCHED', lease_until = DATE_ADD(NOW(6), INTERVAL 60 SECOND),
                               lease_owner = 'busy'
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", seed.tenant())
                .param("id", UuidBinary.toBytes(eventId))
                .update();

        assertThat(poller.pollOnce()).isZero();
    }

    /** 可重试失败按退避回到 PENDING，达到上限后 DEAD。 */
    @Test
    void pollOnce_retryableFailure_schedulesRetryThenDead() {
        Seed seed = seedFileRun("tenant-a");
        UUID eventId = data.createOutbox(seed.tenant(), seed.runId(), EventTypes.FILE_PARSE_REQUESTED, "{}", "retry-1");
        OutboxPoller custom = customPoller(failingHandler(true));

        assertThat(custom.pollOnce()).isEqualTo(1);
        assertThat(outboxStatus(eventId)).isEqualTo("PENDING");
        assertThat(runStatus(seed.runId())).isEqualTo("RETRY_WAIT");
        assertThat(outboxLastError(eventId)).contains("RICCON-TEST-503");

        makeAvailableNow(eventId);
        assertThat(custom.pollOnce()).isEqualTo(1);
        assertThat(outboxStatus(eventId)).isEqualTo("PENDING");

        makeAvailableNow(eventId);
        assertThat(custom.pollOnce()).isEqualTo(1);
        assertThat(outboxStatus(eventId)).isEqualTo("DEAD");
        assertThat(runStatus(seed.runId())).isEqualTo("FAILED");
    }

    /** 不可重试失败立即 DEAD。 */
    @Test
    void pollOnce_nonRetryableFailure_marksDeadImmediately() {
        Seed seed = seedFileRun("tenant-a");
        UUID eventId =
                data.createOutbox(seed.tenant(), seed.runId(), EventTypes.FILE_PARSE_REQUESTED, "{}", "non-retry-1");
        OutboxPoller custom = customPoller(failingHandler(false));

        assertThat(custom.pollOnce()).isEqualTo(1);

        assertThat(outboxStatus(eventId)).isEqualTo("DEAD");
        assertThat(runStatus(seed.runId())).isEqualTo("FAILED");
    }

    /** 未预期运行时异常按有界可重试处理，并记录 RICCON-WORKER-500。 */
    @Test
    void pollOnce_unexpectedRuntime_retriesWithWorker500() {
        Seed seed = seedFileRun("tenant-a");
        UUID eventId =
                data.createOutbox(seed.tenant(), seed.runId(), EventTypes.FILE_PARSE_REQUESTED, "{}", "unexpected-1");
        OutboxEventHandler broken = new OutboxEventHandler() {
            @Override
            public String eventType() {
                return EventTypes.FILE_PARSE_REQUESTED;
            }

            @Override
            public RunKind runKind() {
                return RunKind.FILE_PARSE;
            }

            @Override
            public void handle(ClaimedOutboxEvent event) {
                throw new IllegalStateException("boom");
            }
        };
        OutboxPoller custom = customPoller(broken);

        assertThat(custom.pollOnce()).isEqualTo(1);

        assertThat(outboxStatus(eventId)).isEqualTo("PENDING");
        assertThat(runStatus(seed.runId())).isEqualTo("RETRY_WAIT");
        assertThat(outboxLastError(eventId)).contains("RICCON-WORKER-500");
    }

    /** 跨租户 fileId 不得读取其他租户数据。 */
    @Test
    void pollOnce_crossTenantFile_doesNotLeak() {
        Seed seedA = seedFileRun("tenant-a");
        Seed seedB = seedFileRun("tenant-b");
        UUID eventId = data.createOutbox(
                seedA.tenant(),
                seedA.runId(),
                EventTypes.FILE_PARSE_REQUESTED,
                data.fileParsePayload(seedB.fileId(), seedB.parseId(), 1),
                "cross-tenant-1");

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(outboxStatus(eventId)).isEqualTo("DEAD");
        assertThat(runStatus(seedA.runId())).isEqualTo("FAILED");
        assertThat(parseStatus(seedB.tenant(), seedB.parseId())).isEqualTo("PENDING");
    }

    /** 退避序列与配置一致，超出长度取最后一个。 */
    @Test
    void retryDelay_usesConfiguredSequence() {
        Seed seed = seedFileRun("tenant-a");
        UUID eventId = data.createOutbox(seed.tenant(), seed.runId(), EventTypes.FILE_PARSE_REQUESTED, "{}", "delay-1");
        OutboxPoller custom = customPoller(failingHandler(true));

        assertThat(custom.retryDelay(1)).isEqualTo(Duration.ofMillis(10));
        assertThat(custom.retryDelay(2)).isEqualTo(Duration.ofMillis(20));
        assertThat(custom.retryDelay(3)).isEqualTo(Duration.ofMillis(30));
        assertThat(custom.retryDelay(9)).isEqualTo(Duration.ofMillis(30));
    }

    /**
     * 构造带自定义处理器的轮询器。
     *
     * @param handler 处理器
     * @return 轮询器
     */
    private OutboxPoller customPoller(OutboxEventHandler handler) {
        return new OutboxPoller(
                outboxRepository, runRepository, new OutboxEventDispatcher(List.of(handler)), properties, clock);
    }

    /**
     * 构造失败处理器。
     *
     * @param retryable 是否可重试
     * @return 处理器
     */
    private static OutboxEventHandler failingHandler(boolean retryable) {
        return new OutboxEventHandler() {
            @Override
            public String eventType() {
                return EventTypes.FILE_PARSE_REQUESTED;
            }

            @Override
            public RunKind runKind() {
                return RunKind.FILE_PARSE;
            }

            @Override
            public void handle(ClaimedOutboxEvent event) {
                throw new EventProcessingException("RICCON-TEST-503", retryable, "transient failure");
            }
        };
    }

    /**
     * 让重试事件立即可领取。
     *
     * @param eventId 事件 ID
     */
    private void makeAvailableNow(UUID eventId) {
        jdbcClient
                .sql("UPDATE agent_run_outbox SET available_at = NOW(6) WHERE id = :id")
                .param("id", UuidBinary.toBytes(eventId))
                .update();
    }

    /**
     * 创建带文件和解析尝试的运行与 outbox；每次生成唯一租户。
     *
     * @param tenantPrefix 可读租户前缀
     * @return 种子
     */
    private Seed seedFileRun(String tenantPrefix) {
        String tenant = uniqueTenant(tenantPrefix);
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        UUID workspaceId = data.createWorkspace(tenant, userId);
        UUID fileId = data.createFile(tenant, workspaceId, userId, FileFormat.PDF, TestDocuments.pdf(1));
        UUID parseId = data.createParse(tenant, fileId, 1);
        UUID runId = data.createRun(tenant, RunKind.FILE_PARSE, fileId, userId, 3);
        return new Seed(tenant, runId, fileId, parseId);
    }

    /**
     * 查询 outbox 状态。
     *
     * @param eventId 事件 ID
     * @return 状态
     */
    private String outboxStatus(UUID eventId) {
        return jdbcClient
                .sql("SELECT status FROM agent_run_outbox WHERE id = :id")
                .param("id", UuidBinary.toBytes(eventId))
                .query(String.class)
                .single();
    }

    /**
     * 查询 outbox 最后错误。
     *
     * @param eventId 事件 ID
     * @return 错误摘要
     */
    private String outboxLastError(UUID eventId) {
        return jdbcClient
                .sql("SELECT last_error FROM agent_run_outbox WHERE id = :id")
                .param("id", UuidBinary.toBytes(eventId))
                .query(String.class)
                .optional()
                .orElse("");
    }

    /**
     * 查询运行状态。
     *
     * @param runId 运行 ID
     * @return 状态
     */
    private String runStatus(UUID runId) {
        return jdbcClient
                .sql("SELECT status FROM agent_run WHERE id = :id")
                .param("id", UuidBinary.toBytes(runId))
                .query(String.class)
                .single();
    }

    /**
     * 查询解析状态。
     *
     * @param tenant 租户
     * @param parseId 解析 ID
     * @return 状态
     */
    private String parseStatus(String tenant, UUID parseId) {
        return jdbcClient
                .sql("SELECT status FROM file_parse WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(parseId))
                .query(String.class)
                .single();
    }

    /**
     * 测试种子。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param fileId 文件 ID
     * @param parseId 解析 ID
     */
    private record Seed(String tenant, UUID runId, UUID fileId, UUID parseId) {}
}
