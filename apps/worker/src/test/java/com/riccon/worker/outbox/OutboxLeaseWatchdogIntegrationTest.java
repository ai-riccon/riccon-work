package com.riccon.worker.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.platform.domain.research.RunKind;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.config.OutboxProperties;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.run.config.OutboxLaneProperties;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.domain.EventProcessingException;
import com.riccon.worker.run.domain.OutboxEventHandler;
import com.riccon.worker.run.domain.OutboxLane;
import com.riccon.worker.run.repository.OutboxRepository;
import com.riccon.worker.run.repository.RunRepository;
import com.riccon.worker.run.service.OutboxEventDispatcher;
import com.riccon.worker.run.service.OutboxLeaseHeartbeat;
import com.riccon.worker.run.service.OutboxPoller;
import com.riccon.worker.support.TestDocuments;
import java.time.Clock;
import java.time.Duration;
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
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Outbox 租约心跳与看门狗同轮询器的集成语义（M05-B1 F-002/F-004/F-010）。
 *
 * <p>租约丢失时轮询器必须放弃本次结果：不写 DONE、不写 DEAD、不推进运行到 SUCCEEDED/FAILED，
 * 让新租约持有者重放；超过事件通道的最大处理时长时必须映射 {@code RICCON-WORKER-504} 并按可重试
 * 失败退避。租约丢失通过另一连接抢占 {@code lease_owner} 触发，验证的是真实数据库续期路径，
 * 不是伪造心跳状态。
 */
class OutboxLeaseWatchdogIntegrationTest extends WorkerIntegrationTestBase {

    /** 真实 outbox 仓库，兼作 heartbeat 的续期端口。 */
    @Autowired
    private OutboxRepository outboxRepository;

    /** 运行状态仓库。 */
    @Autowired
    private RunRepository runRepository;

    /** UTC 时钟。 */
    @Autowired
    private Clock clock;

    /** 每个测试清空跨租户 outbox，保证精确领取计数。 */
    @BeforeEach
    void clearStaleOutbox() {
        clearOutbox();
    }

    /** 租约丢失且 handler 正常返回时，不写成功结果也不推进运行。 */
    @Test
    @DisplayName("租约丢失丢弃成功结果且不落库")
    void leaseLost_discardsSuccessWithoutPersistence() throws Exception {
        Seed seed = seedFileRun("lease-lost-ok");
        UUID eventId = data.createOutbox(
                seed.tenant(), seed.runId(), EventTypes.FILE_PARSE_REQUESTED, "{}", "lease-lost-ok-" + seed.runId());
        CountDownLatch started = new CountDownLatch(1);
        OutboxLaneProperties laneProperties = laneProperties(Duration.ofSeconds(30), Duration.ofSeconds(30));
        Logger pollerLogger = (Logger) LoggerFactory.getLogger(OutboxPoller.class);
        ListAppender<ILoggingEvent> logs = attach(pollerLogger);
        OutboxLeaseHeartbeat heartbeat = new OutboxLeaseHeartbeat(outboxRepository);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            OutboxPoller poller = poller(
                    blockingHandler(EventTypes.FILE_PARSE_REQUESTED, RunKind.FILE_PARSE, started, false),
                    laneProperties,
                    Duration.ofMillis(150),
                    heartbeat);

            Future<Integer> result = executor.submit(() -> poller.pollOnce(OutboxLane.HEAVY));
            assertThat(started.await(5, TimeUnit.SECONDS))
                    .as("handler 必须在 5s 内开始")
                    .isTrue();
            stealLease(eventId);

            assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(outboxStatus(eventId)).as("租约丢失不得写 DONE/DEAD/PENDING").isEqualTo("DISPATCHED");
            assertThat(outboxLastError(eventId)).as("租约丢失不得写业务失败原因").isNull();
            assertThat(runStatus(seed.runId())).as("运行保持 RUNNING 等待新租约重放").isEqualTo("RUNNING");
            assertThat(runErrorCode(seed.runId())).isNull();
            assertThat(logs.list)
                    .as("租约丢失日志必须带稳定错误码")
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .contains("outbox.event.lease_lost_discarded")
                            .contains("RICCON-WORKER-409"));
        } finally {
            executor.shutdownNow();
            heartbeat.shutdown();
            detach(pollerLogger, logs);
        }
    }

    /** 租约丢失且 handler 抛出可重试失败时，不写重试状态、不写 last_error。 */
    @Test
    @DisplayName("租约丢失丢弃失败结果且不写重试")
    void leaseLostDuringFailure_discardsRetryWrite() throws Exception {
        Seed seed = seedFileRun("lease-lost-fail");
        UUID eventId = data.createOutbox(
                seed.tenant(), seed.runId(), EventTypes.FILE_PARSE_REQUESTED, "{}", "lease-lost-fail-" + seed.runId());
        CountDownLatch started = new CountDownLatch(1);
        OutboxLaneProperties laneProperties = laneProperties(Duration.ofSeconds(30), Duration.ofSeconds(30));
        OutboxLeaseHeartbeat heartbeat = new OutboxLeaseHeartbeat(outboxRepository);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            OutboxPoller poller = poller(
                    blockingHandler(EventTypes.FILE_PARSE_REQUESTED, RunKind.FILE_PARSE, started, true),
                    laneProperties,
                    Duration.ofMillis(150),
                    heartbeat);

            Future<Integer> result = executor.submit(() -> poller.pollOnce(OutboxLane.HEAVY));
            assertThat(started.await(5, TimeUnit.SECONDS))
                    .as("handler 必须在 5s 内开始")
                    .isTrue();
            stealLease(eventId);

            assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(outboxStatus(eventId)).isEqualTo("DISPATCHED");
            assertThat(outboxLastError(eventId)).isNull();
            assertThat(runStatus(seed.runId())).as("不得写 RETRY_WAIT/FAILED").isEqualTo("RUNNING");
            assertThat(runErrorCode(seed.runId())).isNull();
        } finally {
            executor.shutdownNow();
            heartbeat.shutdown();
        }
    }

    /** heavy 通道超时按通道 heavy-max-processing 映射 504 并可重试。 */
    @Test
    @DisplayName("heavy 通道超时映射 504 且可重试")
    void heavyLaneTimeout_mapsTo504Retryable() throws Exception {
        Seed seed = seedFileRun("heavy-timeout");
        UUID eventId = data.createOutbox(
                seed.tenant(), seed.runId(), EventTypes.FILE_PARSE_REQUESTED, "{}", "heavy-timeout-" + seed.runId());
        CountDownLatch started = new CountDownLatch(1);
        OutboxLaneProperties laneProperties = laneProperties(Duration.ofSeconds(30), Duration.ofMillis(150));
        OutboxLeaseHeartbeat heartbeat = new OutboxLeaseHeartbeat(outboxRepository);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            OutboxPoller poller = poller(
                    blockingHandler(EventTypes.FILE_PARSE_REQUESTED, RunKind.FILE_PARSE, started, false),
                    laneProperties,
                    Duration.ofSeconds(30),
                    heartbeat);

            Future<Integer> result = executor.submit(() -> poller.pollOnce(OutboxLane.HEAVY));
            assertThat(started.await(5, TimeUnit.SECONDS))
                    .as("handler 必须在 5s 内开始")
                    .isTrue();

            assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(outboxStatus(eventId)).isEqualTo("PENDING");
            assertThat(outboxLastError(eventId)).contains("RICCON-WORKER-504");
            assertThat(runStatus(seed.runId())).isEqualTo("RETRY_WAIT");
            assertThat(runErrorCode(seed.runId())).isEqualTo("RICCON-WORKER-504");
        } finally {
            executor.shutdownNow();
            heartbeat.shutdown();
        }
    }

    /** default 通道使用 max-processing，不受 heavy 值影响。 */
    @Test
    @DisplayName("default 通道超时使用 default 处理时长")
    void defaultLaneTimeout_usesDefaultMaxProcessing() throws Exception {
        Seed seed = seedResearchRun("default-timeout");
        UUID eventId = data.createOutbox(
                seed.tenant(), seed.runId(), EventTypes.AGENT_RUN_REQUESTED, "{}", "default-timeout-" + seed.runId());
        CountDownLatch started = new CountDownLatch(1);
        OutboxLaneProperties laneProperties = laneProperties(Duration.ofMillis(150), Duration.ofSeconds(30));
        OutboxLeaseHeartbeat heartbeat = new OutboxLeaseHeartbeat(outboxRepository);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            OutboxPoller poller = poller(
                    blockingHandler(EventTypes.AGENT_RUN_REQUESTED, RunKind.RESEARCH_REPORT, started, false),
                    laneProperties,
                    Duration.ofSeconds(30),
                    heartbeat);

            Future<Integer> result = executor.submit(() -> poller.pollOnce(OutboxLane.DEFAULT));
            assertThat(started.await(5, TimeUnit.SECONDS))
                    .as("handler 必须在 5s 内开始")
                    .isTrue();

            assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(outboxStatus(eventId)).isEqualTo("PENDING");
            assertThat(outboxLastError(eventId)).contains("RICCON-WORKER-504");
            assertThat(runStatus(seed.runId())).isEqualTo("RETRY_WAIT");
        } finally {
            executor.shutdownNow();
            heartbeat.shutdown();
        }
    }

    /**
     * 构造使用真实心跳的轮询器。
     *
     * @param handler 处理器
     * @param laneProperties 通道配置
     * @param leaseDuration 轮询器使用的租约时长（决定续期间隔）
     * @param heartbeat 心跳组件
     * @return 轮询器
     */
    private OutboxPoller poller(
            OutboxEventHandler handler,
            OutboxLaneProperties laneProperties,
            Duration leaseDuration,
            OutboxLeaseHeartbeat heartbeat) {
        OutboxProperties properties = new OutboxProperties(
                Duration.ofMillis(500),
                Duration.ZERO,
                10,
                leaseDuration,
                3,
                List.of(Duration.ofMillis(10)),
                Duration.ZERO,
                "");
        return new OutboxPoller(
                outboxRepository,
                runRepository,
                new OutboxEventDispatcher(List.of(handler)),
                properties,
                clock,
                heartbeat,
                laneProperties);
    }

    /**
     * 构造阻塞到被中断的处理器。
     *
     * @param eventType 事件类型
     * @param runKind 运行类别
     * @param started handler 已开始信号
     * @param failureAfterInterrupt true 表示中断后抛出可重试失败
     * @return 处理器
     */
    private static OutboxEventHandler blockingHandler(
            String eventType, RunKind runKind, CountDownLatch started, boolean failureAfterInterrupt) {
        return new OutboxEventHandler() {
            @Override
            public String eventType() {
                return eventType;
            }

            @Override
            public RunKind runKind() {
                return runKind;
            }

            @Override
            public void handle(ClaimedOutboxEvent event) {
                started.countDown();
                try {
                    // 等待心跳丢失或看门狗超时中断；测试不依赖 sleep。
                    new CountDownLatch(1).await();
                } catch (InterruptedException exception) {
                    // 清空中断标记，让轮询器能在同一线程继续做租约校验/数据库断言。
                    Thread.interrupted();
                }
                if (failureAfterInterrupt) {
                    throw new EventProcessingException("RICCON-TEST-503", true, "transient failure after lease lost");
                }
            }
        };
    }

    /**
     * 构造指定通道处理时长的配置。
     *
     * @param defaultMaxProcessing default 通道最大处理时长
     * @param heavyMaxProcessing heavy 通道最大处理时长
     * @return 已校验的通道配置
     */
    private static OutboxLaneProperties laneProperties(Duration defaultMaxProcessing, Duration heavyMaxProcessing) {
        OutboxLaneProperties properties = new OutboxLaneProperties();
        properties.setMaxProcessing(defaultMaxProcessing);
        properties.setHeavyMaxProcessing(heavyMaxProcessing);
        properties.afterPropertiesSet();
        return properties;
    }

    /**
     * 用另一连接抢占租约，模拟租约过期后被其他 Worker 接管。
     *
     * @param eventId 事件 ID
     */
    private void stealLease(UUID eventId) {
        int updated = jdbcClient
                .sql("UPDATE agent_run_outbox SET lease_owner = 'intruder' WHERE id = :id")
                .param("id", UuidBinary.toBytes(eventId))
                .update();
        assertThat(updated).as("抢占租约必须命中 1 行").isEqualTo(1);
    }

    /**
     * 创建文件解析运行与 outbox 的前置数据。
     *
     * @param tenantPrefix 租户前缀
     * @return 种子
     */
    private Seed seedFileRun(String tenantPrefix) {
        String tenant = uniqueTenant(tenantPrefix);
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        UUID workspaceId = data.createWorkspace(tenant, userId);
        UUID fileId = data.createFile(tenant, workspaceId, userId, FileFormat.PDF, TestDocuments.pdf(1));
        UUID runId = data.createRun(tenant, RunKind.FILE_PARSE, fileId, userId, 3);
        return new Seed(tenant, runId);
    }

    /**
     * 创建报告运行与 outbox 的前置数据。
     *
     * @param tenantPrefix 租户前缀
     * @return 种子
     */
    private Seed seedResearchRun(String tenantPrefix) {
        String tenant = uniqueTenant(tenantPrefix);
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        UUID workspaceId = data.createWorkspace(tenant, userId);
        UUID taskId = data.createResearchTask(tenant, workspaceId, userId, "lease watchdog", null);
        UUID runId = data.createResearchRun(tenant, taskId, userId);
        return new Seed(tenant, runId);
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
     * @return 错误摘要；未写入时为空
     */
    private String outboxLastError(UUID eventId) {
        return jdbcClient
                .sql("SELECT last_error FROM agent_run_outbox WHERE id = :id")
                .param("id", UuidBinary.toBytes(eventId))
                .query(String.class)
                .optional()
                .orElse(null);
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
     * 查询运行错误码。
     *
     * @param runId 运行 ID
     * @return 错误码；未写入时为空
     */
    private String runErrorCode(UUID runId) {
        return jdbcClient
                .sql("SELECT error_code FROM agent_run WHERE id = :id")
                .param("id", UuidBinary.toBytes(runId))
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /**
     * 挂载日志收集器。
     *
     * @param logger 目标 logger
     * @return 收集器
     */
    private static ListAppender<ILoggingEvent> attach(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /**
     * 卸载日志收集器，避免污染其他测试。
     *
     * @param logger 目标 logger
     * @param appender 收集器
     */
    private static void detach(Logger logger, ListAppender<ILoggingEvent> appender) {
        logger.detachAppender(appender);
        appender.stop();
    }

    /**
     * 测试种子。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     */
    private record Seed(String tenant, UUID runId) {}
}
