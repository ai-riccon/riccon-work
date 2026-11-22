package com.riccon.worker.run.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.research.RunKind;
import com.riccon.platform.domain.research.RunStatus;
import com.riccon.worker.config.OutboxProperties;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.domain.OutboxEventHandler;
import com.riccon.worker.run.domain.RunRecord;
import com.riccon.worker.run.repository.OutboxRepository;
import com.riccon.worker.run.repository.RunRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Worker 运行失败说明脱敏测试（M04 F-008）。
 *
 * <p>Outbox 处理器的未预期运行时异常可能携带上游地址、凭据或堆栈；这些原文只允许进入服务端日志，
 * {@code agent_run.error_message} 必须写入稳定静态说明。用例通过仓库调用参数断言重试等待与终态
 * 两条落库路径，锁定“原始 detail 一出现就失败”的负向语义，并用 Mockito 隔离数据库。
 */
class RunFailureMessageSanitizationTest {

    /** 未预期异常的静态脱敏说明，与 {@link OutboxPoller} 的实现一致。 */
    private static final String UNEXPECTED_FAILURE_MESSAGE = "unexpected event processing failure";

    /** 稳定错误码。 */
    private static final String UNEXPECTED_FAILURE_CODE = "RICCON-WORKER-500";

    /** 测试租户。 */
    private static final TenantId TENANT = TenantId.of("tenant-raw-detail");

    /** 运行 ID。 */
    private static final UUID RUN_ID = UUID.fromString("01930000-0000-7000-8000-000000000158");

    /** 事件 ID。 */
    private static final UUID EVENT_ID = UUID.fromString("01930000-0000-7000-8000-000000000159");

    /** 固定时钟，避免依赖真实时间。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC);

    /** 含内部地址、密钥与堆栈的原始 detail canary。 */
    private static final String RAW_DETAIL =
            "provider=mock-openai baseUrl=http://198.51.100.7:8080/v1 apiKey=CANARY-KEY-0158\n"
                    + "java.lang.IllegalStateException: boom\n"
                    + "\tat com.riccon.worker.chat.service.ChatStreamService.open(ChatStreamService.java:42)";

    /**
     * F-008 负向用例：异常原文含内部地址/密钥/堆栈时，重试等待落库只保留静态说明。
     *
     * <p>若实现改为写入 {@code exception.getMessage()} 或异常 toString，本断言会立即失败。
     */
    @Test
    @DisplayName("重试等待阶段只写静态失败说明")
    void pollOnce_unexpectedRuntimeDetail_retryStoresStaticMessage() {
        OutboxRepository outboxRepository = mock(OutboxRepository.class);
        RunRepository runRepository = mock(RunRepository.class);
        ClaimedOutboxEvent event = event(1);
        when(outboxRepository.claim()).thenReturn(List.of(event));
        when(outboxRepository.markRetry(eq(event), any(Instant.class), any(String.class)))
                .thenReturn(1);
        stubRun(runRepository, 1);

        OutboxPoller poller = poller(outboxRepository, runRepository);

        assertThat(poller.pollOnce()).isEqualTo(1);

        FailureWrite failure = captureRetryWait(runRepository);
        assertThat(failure.code()).isEqualTo(UNEXPECTED_FAILURE_CODE);
        assertThat(failure.message()).isEqualTo(UNEXPECTED_FAILURE_MESSAGE);
        assertNoRawDetail(failure.message());

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(outboxRepository).markRetry(eq(event), any(Instant.class), reason.capture());
        assertThat(reason.getValue()).contains(UNEXPECTED_FAILURE_CODE).contains(UNEXPECTED_FAILURE_MESSAGE);
        assertNoRawDetail(reason.getValue());
    }

    /** F-008 负向用例：耗尽重试进入 FAILED 时仍只写静态说明。 */
    @Test
    @DisplayName("终态阶段只写静态失败说明")
    void pollOnce_unexpectedRuntimeDetail_terminalStoresStaticMessage() {
        OutboxRepository outboxRepository = mock(OutboxRepository.class);
        RunRepository runRepository = mock(RunRepository.class);
        ClaimedOutboxEvent event = event(3);
        when(outboxRepository.claim()).thenReturn(List.of(event));
        when(outboxRepository.markDead(eq(event), any(String.class))).thenReturn(1);
        stubRun(runRepository, 3);

        OutboxPoller poller = poller(outboxRepository, runRepository);

        assertThat(poller.pollOnce()).isEqualTo(1);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(runRepository).markFailed(eq(TENANT), eq(RUN_ID), code.capture(), message.capture());
        assertThat(code.getValue()).isEqualTo(UNEXPECTED_FAILURE_CODE);
        assertThat(message.getValue()).isEqualTo(UNEXPECTED_FAILURE_MESSAGE);
        assertNoRawDetail(message.getValue());

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(outboxRepository).markDead(eq(event), reason.capture());
        assertThat(reason.getValue()).contains(UNEXPECTED_FAILURE_CODE).contains(UNEXPECTED_FAILURE_MESSAGE);
        assertNoRawDetail(reason.getValue());
    }

    /**
     * 构造带自定义处理器的轮询器。
     *
     * @param outboxRepository outbox 仓库
     * @param runRepository 运行仓库
     * @return 轮询器
     */
    private static OutboxPoller poller(OutboxRepository outboxRepository, RunRepository runRepository) {
        OutboxProperties properties = new OutboxProperties(
                Duration.ofMillis(500),
                Duration.ZERO,
                10,
                Duration.ofSeconds(90),
                3,
                List.of(Duration.ofMillis(10)),
                Duration.ZERO,
                "");
        OutboxEventHandler handler = new OutboxEventHandler() {
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
                throw new IllegalStateException(RAW_DETAIL);
            }
        };
        return new OutboxPoller(
                outboxRepository, runRepository, new OutboxEventDispatcher(List.of(handler)), properties, CLOCK);
    }

    /**
     * 模拟可执行的 PENDING 运行。
     *
     * @param runRepository 运行仓库
     * @param attempt 事件尝试号
     */
    private static void stubRun(RunRepository runRepository, int attempt) {
        when(runRepository.find(TENANT, RUN_ID))
                .thenReturn(Optional.of(new RunRecord(RUN_ID, RunKind.FILE_PARSE, RunStatus.PENDING, attempt, 3)));
        when(runRepository.markRunning(TENANT, RUN_ID, attempt)).thenReturn(true);
    }

    /**
     * 截获重试等待的失败码与说明。
     *
     * @param runRepository 运行仓库
     * @return 失败写入
     */
    private static FailureWrite captureRetryWait(RunRepository runRepository) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(runRepository).markRetryWait(eq(TENANT), eq(RUN_ID), eq(1), code.capture(), message.capture());
        return new FailureWrite(code.getValue(), message.getValue());
    }

    /**
     * 断言失败说明不含原始 detail canary。
     *
     * @param value 失败码或说明
     */
    private static void assertNoRawDetail(String value) {
        assertThat(value)
                .doesNotContain(
                        "provider=",
                        "baseUrl",
                        "198.51.100.7",
                        "CANARY-KEY-0158",
                        "IllegalStateException",
                        "ChatStreamService.java");
    }

    /**
     * 构造已领取事件。
     *
     * @param attempts 尝试号
     * @return 事件
     */
    private static ClaimedOutboxEvent event(int attempts) {
        Instant now = CLOCK.instant();
        return new ClaimedOutboxEvent(
                EVENT_ID,
                TENANT.value(),
                RUN_ID,
                EventTypes.FILE_PARSE_REQUESTED,
                1,
                "raw-detail-" + attempts,
                "{}",
                attempts,
                now,
                now.plus(Duration.ofMinutes(1)));
    }

    /**
     * 一次失败写入。
     *
     * @param code 错误码
     * @param message 失败说明
     */
    private record FailureWrite(String code, String message) {}
}
