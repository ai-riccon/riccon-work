package com.riccon.api.chat.service;

import com.riccon.api.chat.domain.ChatSubmission;
import com.riccon.api.chat.domain.ConversationRecord;
import com.riccon.api.chat.domain.MessageRecord;
import com.riccon.api.chat.dto.SendMessageRequest;
import com.riccon.api.chat.dto.StopRunRequest;
import com.riccon.api.chat.repository.MessageRepository;
import com.riccon.api.config.WorkerClientProperties;
import com.riccon.api.error.BadRequestException;
import com.riccon.api.error.ConflictException;
import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.error.RicconException;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.run.domain.RunSnapshot;
import com.riccon.api.run.repository.RunRepository;
import com.riccon.api.web.Validation;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.chat.MessageStatus;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.research.RunKind;
import com.riccon.platform.domain.research.RunStatus;
import java.io.Closeable;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * J1 对话流编排。
 *
 * <p>发送路径：持久化阶段由 {@link ChatSubmissionService} 完成；随后用 HMAC 服务令牌打开 Worker 内部 SSE，
 * 在返回浏览器前读取并校验 {@code started}，首字前失败映射为 ProblemDetail。转发阶段逐帧校验、落库
 * assistant 内容/引用/运行状态，并把每个事件的 {@code messageId} 改写为 API 生成的 assistant 消息 ID，
 * 保证数据库、SSE 与前端游标一致。
 *
 * <p>空闲/总时长由看门狗关闭上游流；客户端断开时取消 Worker 运行并保留已生成内容。所有 SSE 事件落库都在
 * 短事务中执行，并把租户显式传入读取线程（ADR-0003）。
 */
@Service
public class ChatService {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);

    /** 看门狗检查周期。 */
    private static final long WATCHDOG_PERIOD_SECONDS = 1L;

    /** 提交持久化。 */
    private final ChatSubmissionService submissionService;

    /** Worker 客户端。 */
    private final WorkerChatClient workerChatClient;

    /** 事件落库。 */
    private final ChatEventRecorder recorder;

    /** 会话服务，用于停止路径校验会话归属。 */
    private final ConversationService conversationService;

    /** 运行仓储。 */
    private final RunRepository runRepository;

    /** 消息仓储。 */
    private final MessageRepository messageRepository;

    /** 事件校验。 */
    private final ChatEventValidator validator;

    /** API 合成事件。 */
    private final ApiChatEventFactory eventFactory;

    /** JSON 解析。 */
    private final ObjectMapper objectMapper;

    /** 客户端配置。 */
    private final WorkerClientProperties properties;

    /** 看门狗调度器。 */
    private final ScheduledExecutorService watchdog;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建服务。
     *
     * @param submissionService 提交持久化
     * @param workerChatClient Worker 客户端
     * @param recorder 事件落库
     * @param conversationService 会话服务
     * @param runRepository 运行仓储
     * @param messageRepository 消息仓储
     * @param validator 事件校验
     * @param eventFactory API 合成事件
     * @param objectMapper JSON 解析
     * @param properties 客户端配置
     * @param watchdog 看门狗调度器
     * @param clock UTC 时钟
     */
    public ChatService(
            ChatSubmissionService submissionService,
            WorkerChatClient workerChatClient,
            ChatEventRecorder recorder,
            ConversationService conversationService,
            RunRepository runRepository,
            MessageRepository messageRepository,
            ChatEventValidator validator,
            ApiChatEventFactory eventFactory,
            ObjectMapper objectMapper,
            WorkerClientProperties properties,
            @Qualifier("chatStreamWatchdog") ScheduledExecutorService watchdog,
            Clock clock) {
        this.submissionService = Objects.requireNonNull(submissionService, "submissionService");
        this.workerChatClient = Objects.requireNonNull(workerChatClient, "workerChatClient");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.conversationService = Objects.requireNonNull(conversationService, "conversationService");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.eventFactory = Objects.requireNonNull(eventFactory, "eventFactory");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.watchdog = Objects.requireNonNull(watchdog, "watchdog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 发送消息并打开 Worker 流；返回前已收到并落库首个 {@code started} 事件。
     *
     * @param actor 当前员工
     * @param conversationId 会话 ID
     * @param request 发送请求
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 可写回浏览器的流句柄
     * @throws RicconException 首字前失败；调用方返回 ProblemDetail
     */
    public ChatStreamHandle send(
            RicconPrincipal actor, UUID conversationId, SendMessageRequest request, String traceId, String requestId) {
        ChatSubmission submission = submissionService.prepare(actor, conversationId, request, traceId, requestId);
        WorkerChatClient.WorkerStream stream = null;
        try {
            stream = workerChatClient.open(submission);
        } catch (WorkerChatClient.WorkerCallException exception) {
            recordPreFirstFailure(submission, exception.code(), exception.getMessage());
            throw mapWorkerCall(exception);
        } catch (WorkerChatClient.WorkerTimeoutException exception) {
            recordPreFirstFailure(
                    submission, ErrorCodes.CHAT_STREAM_TIMEOUT, "worker did not produce the first event in time");
            throw upstreamFailure("chat worker timed out before first event", exception);
        } catch (WorkerChatClient.WorkerUnavailableException exception) {
            recordPreFirstFailure(submission, ErrorCodes.CHAT_UPSTREAM_FAILED, "chat worker is unavailable");
            throw upstreamFailure("chat worker is unavailable", exception);
        }
        try {
            return openFirstFrame(stream, submission);
        } catch (PreFirstWorkerFailure failure) {
            closeQuietly(stream);
            recordPreFirstFailure(submission, failure.code(), failure.getMessage());
            throw new RicconException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorCodes.CHAT_UPSTREAM_FAILED,
                    "Internal server error",
                    "chat worker failed before first token");
        } catch (RuntimeException exception) {
            closeQuietly(stream);
            recordPreFirstFailure(submission, ErrorCodes.CHAT_STREAM_INVALID, "invalid worker stream");
            throw mapFirstFrameFailure(exception);
        }
    }

    /**
     * 读取并校验首个事件，成功时返回流句柄。
     *
     * @param stream Worker 流
     * @param submission 提交
     * @return 流句柄
     */
    private ChatStreamHandle openFirstFrame(WorkerChatClient.WorkerStream stream, ChatSubmission submission) {
        SseFrame first;
        try {
            first = stream.reader().next();
        } catch (IOException exception) {
            throw new ChatEventValidationException("failed to read the first worker event");
        }
        if (first == null || first.event() == null) {
            throw new ChatEventValidationException("worker stream ended before started");
        }
        ObjectNode event = parseEvent(first.data());
        ChatEventValidator.ValidatedEvent validated =
                validator.validate(event, submission.tenantId(), submission.runId(), submission.traceId());
        if (EventTypes.CHAT_FAILED.equals(validated.eventType())) {
            throw new PreFirstWorkerFailure(validated.failedCode(), validated.failedMessage());
        }
        if (!EventTypes.CHAT_STARTED.equals(validated.eventType())) {
            throw new ChatEventValidationException("first worker event must be started");
        }
        recorder.recordStarted(
                submission.tenantId(), submission.runId(), submission.assistantMessageId(), validated.modelId());
        event.put("messageId", submission.assistantMessageId().toString());
        return new ChatStreamHandle(submission, stream, first.id(), first.event(), writeJson(event));
    }

    /**
     * 把 Worker 事件流转发给浏览器，并消费事件落库。
     *
     * <p>方法在 Spring MVC 异步线程执行，必须显式恢复租户上下文；不关闭 Servlet 输出流。
     *
     * @param handle 已打开并处理首个事件的流
     * @param sink 输出目标
     */
    public void transfer(ChatStreamHandle handle, ChatEventSink sink) {
        AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        AtomicBoolean timedOut = new AtomicBoolean(false);
        long startedAt = System.nanoTime();
        long idleNanos = properties.idleTimeout().toNanos();
        long maxNanos = properties.maxDuration().toNanos();
        ScheduledFuture<?> watchdogTask = watchdog.scheduleAtFixedRate(
                () -> {
                    long now = System.nanoTime();
                    if (now - lastActivity.get() > idleNanos || now - startedAt > maxNanos) {
                        if (timedOut.compareAndSet(false, true)) {
                            closeQuietly(handle.stream());
                        }
                    }
                },
                WATCHDOG_PERIOD_SECONDS,
                WATCHDOG_PERIOD_SECONDS,
                TimeUnit.SECONDS);
        try {
            TenantContext.runWithTenant(
                    handle.submission().tenantId(), () -> transferInTenant(handle, sink, lastActivity, timedOut));
        } finally {
            watchdogTask.cancel(true);
            closeQuietly(handle.stream());
        }
    }

    /**
     * 在租户作用域内转发。
     *
     * @param handle 流句柄
     * @param sink 输出目标
     * @param lastActivity 最近活动时间
     * @param timedOut 看门狗是否已触发
     */
    private void transferInTenant(
            ChatStreamHandle handle, ChatEventSink sink, AtomicLong lastActivity, AtomicBoolean timedOut) {
        ChatSubmission submission = handle.submission();
        try {
            writeFrame(sink, handle.firstEventId(), handle.firstEventType(), handle.firstEventJson());
            String terminal = null;
            while (terminal == null) {
                SseFrame frame;
                try {
                    frame = handle.stream().reader().next();
                } catch (IOException exception) {
                    if (timedOut.get()) {
                        terminal = failStream(handle, sink, ErrorCodes.CHAT_STREAM_TIMEOUT, "对话流空闲或超过总时长上限", false);
                    } else {
                        terminal = failStream(handle, sink, ErrorCodes.CHAT_STREAM_INVALID, "回答流意外中断", true);
                    }
                    break;
                }
                if (frame == null) {
                    terminal = failStream(handle, sink, ErrorCodes.CHAT_STREAM_INVALID, "回答流意外结束", true);
                    break;
                }
                lastActivity.set(System.nanoTime());
                if (frame.event() == null || !validator.isKnownEventType(frame.event())) {
                    LOG.debug(
                            "chat.event.unknown runId={} tenantId={} eventType={}",
                            submission.runId(),
                            submission.tenantId().value(),
                            frame.event());
                    continue;
                }
                ObjectNode event = parseEvent(frame.data());
                ChatEventValidator.ValidatedEvent validated =
                        validator.validate(event, submission.tenantId(), submission.runId(), submission.traceId());
                String applied = applyEvent(handle, validated);
                if (applied != null && !applied.equals(validated.eventType())) {
                    // 当前增量未能落库（通常是员工停止后迟到的 delta）：不转发内容，直接发送合成终态。
                    if (EventTypes.CHAT_STOPPED.equals(applied)) {
                        writeGenerated(
                                sink,
                                eventFactory.stopped(
                                        submission.tenantId().value(),
                                        submission.runId(),
                                        submission.assistantMessageId(),
                                        submission.traceId(),
                                        "USER_CANCELLED"));
                        terminal = applied;
                    } else {
                        terminal = failStream(handle, sink, ErrorCodes.CHAT_STREAM_INVALID, "回答在停止后仍收到增量", false);
                    }
                    break;
                }
                terminal = applied;
                event.put("messageId", submission.assistantMessageId().toString());
                writeFrame(sink, frame.id(), frame.event(), writeJson(event));
            }
        } catch (ClientGoneException exception) {
            handleClientDisconnect(handle);
        } catch (ChatEventValidationException exception) {
            LOG.warn(
                    "chat.event.invalid runId={} tenantId={} reason={}",
                    submission.runId(),
                    submission.tenantId().value(),
                    exception.getMessage());
            failStream(handle, sink, ErrorCodes.CHAT_STREAM_INVALID, "回答流事件不符合契约", false);
        } catch (RuntimeException exception) {
            LOG.error(
                    "chat.stream.unexpected_failure runId={} tenantId={}",
                    submission.runId(),
                    submission.tenantId().value(),
                    exception);
            failStream(handle, sink, ErrorCodes.CHAT_UPSTREAM_FAILED, "回答生成失败", true);
        }
    }

    /**
     * 消费单个已校验事件并返回终态事件类型；非终态返回 {@code null}。
     *
     * @param handle 流句柄
     * @param validated 已校验事件
     * @return 终态类型或 {@code null}
     */
    private String applyEvent(ChatStreamHandle handle, ChatEventValidator.ValidatedEvent validated) {
        ChatSubmission submission = handle.submission();
        String eventType = validated.eventType();
        switch (eventType) {
            case EventTypes.CHAT_STARTED -> {
                // 重复 started 只转发，不重复写库。
                return null;
            }
            case EventTypes.CHAT_DELTA -> {
                boolean accepted =
                        recorder.recordDelta(submission.tenantId(), submission.assistantMessageId(), validated.delta());
                if (!accepted) {
                    MessageStatus status = messageRepository
                            .findStatus(submission.tenantId(), submission.assistantMessageId())
                            .orElse(MessageStatus.FAILED);
                    if (status == MessageStatus.STOPPED) {
                        return EventTypes.CHAT_STOPPED;
                    }
                    return EventTypes.CHAT_FAILED;
                }
                return null;
            }
            case EventTypes.CHAT_CITATION -> {
                recorder.recordCitations(submission.tenantId(), submission.assistantMessageId(), validated.citations());
                return null;
            }
            case EventTypes.CHAT_COMPLETED -> {
                recorder.recordCompleted(submission.tenantId(), submission.runId(), submission.assistantMessageId());
                return eventType;
            }
            case EventTypes.CHAT_FAILED -> {
                recorder.recordFailed(
                        submission.tenantId(),
                        submission.runId(),
                        submission.assistantMessageId(),
                        validated.failedCode(),
                        validated.failedMessage());
                return eventType;
            }
            case EventTypes.CHAT_STOPPED -> {
                recorder.recordStopped(submission.tenantId(), submission.runId(), submission.assistantMessageId());
                return eventType;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * 停止生成：校验空间访问与对象级停止权限后调用 Worker 取消，并原子落库 STOPPED/CANCELLED 与审计。
     *
     * <p>共享空间成员都能看到会话，但只有会话创建人或该运行的发起人可以停止生成（ADR-0018 第 3.2 节）。
     * 无停止权限与运行不存在统一返回 404，不泄露运行归属。
     *
     * @param actor 当前员工
     * @param conversationId 会话 ID
     * @param request 停止请求
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     */
    public void stop(
            RicconPrincipal actor, UUID conversationId, StopRunRequest request, String traceId, String requestId) {
        Validation.requireBody(request);
        UUID runId = Validation.requireNonNull(request.runId(), "runId");
        TenantId tenantId = TenantId.of(actor.tenantId());
        ConversationRecord conversation = conversationService.requireAccessible(actor, conversationId);
        RunSnapshot run = runRepository.findChat(tenantId, runId).orElseThrow(() -> new NotFoundException("run"));
        if (run.kind() != RunKind.CHAT
                || !conversation.id().equals(run.conversationId())
                || !canStop(actor, conversation, run)) {
            throw new NotFoundException("run");
        }
        if (run.status() == RunStatus.CANCELLED) {
            return;
        }
        if (run.terminal()) {
            throw new ConflictException("chat run is already terminal");
        }
        MessageRecord assistant =
                messageRepository.findAssistantByRun(tenantId, runId).orElse(null);
        UUID assistantId = assistant == null ? null : assistant.id();
        WorkerChatClient.CancelOutcome outcome;
        try {
            outcome = workerChatClient.cancel(runId, tenantId, actor.userId(), "USER_CANCELLED", traceId);
        } catch (WorkerChatClient.WorkerCallException exception) {
            if (exception.status() == 409) {
                RunSnapshot latest =
                        runRepository.findChat(tenantId, runId).orElseThrow(() -> new NotFoundException("run"));
                if (latest.status() == RunStatus.CANCELLED) {
                    return;
                }
                throw new ConflictException("chat run is already terminal");
            }
            throw mapWorkerCall(exception);
        } catch (WorkerChatClient.WorkerUnavailableException exception) {
            throw upstreamFailure("chat worker cancel failed", exception);
        }
        switch (outcome) {
            case ACCEPTED ->
                recorder.recordUserStop(
                        tenantId, actor.userId(), conversation.id(), runId, assistantId, traceId, requestId);
            case CONFLICT -> {
                RunSnapshot latest =
                        runRepository.findChat(tenantId, runId).orElseThrow(() -> new NotFoundException("run"));
                if (latest.status() == RunStatus.CANCELLED) {
                    return;
                }
                throw new ConflictException("chat run is already terminal");
            }
            case NOT_FOUND -> throw new NotFoundException("run");
        }
    }

    /**
     * 判断当前员工能否停止该运行：会话创建人或该运行的发起人。
     *
     * @param actor 当前员工
     * @param conversation 会话
     * @param run 运行
     * @return 允许停止时返回 {@code true}
     */
    private static boolean canStop(RicconPrincipal actor, ConversationRecord conversation, RunSnapshot run) {
        return actor.userId().equals(conversation.createdBy()) || actor.userId().equals(run.userId());
    }

    /**
     * 客户端断开：取消 Worker 并保留已生成内容；无法再向断开的连接写事件。
     *
     * @param handle 流句柄
     */
    private void handleClientDisconnect(ChatStreamHandle handle) {
        ChatSubmission submission = handle.submission();
        LOG.info(
                "chat.stream.client_disconnected runId={} tenantId={}",
                submission.runId(),
                submission.tenantId().value());
        try {
            workerChatClient.cancel(
                    submission.runId(),
                    submission.tenantId(),
                    submission.userId(),
                    "CLIENT_DISCONNECT",
                    submission.traceId());
        } catch (RuntimeException exception) {
            LOG.warn(
                    "chat.stream.cancel_after_disconnect_failed runId={} tenantId={}",
                    submission.runId(),
                    submission.tenantId().value());
        }
        try {
            recorder.recordClientDisconnect(
                    submission.tenantId(),
                    submission.userId(),
                    submission.conversationId(),
                    submission.runId(),
                    submission.assistantMessageId(),
                    submission.traceId());
        } catch (RuntimeException exception) {
            LOG.error(
                    "chat.stream.disconnect_record_failed runId={} tenantId={}",
                    submission.runId(),
                    submission.tenantId().value(),
                    exception);
        }
    }

    /**
     * 发送失败事件并落库失败终态，返回终态类型。
     *
     * @param handle 流句柄
     * @param sink 输出目标
     * @param code 错误码
     * @param message 脱敏说明
     * @param retryable 是否可重试
     * @return failed 事件类型
     */
    private String failStream(
            ChatStreamHandle handle, ChatEventSink sink, String code, String message, boolean retryable) {
        ChatSubmission submission = handle.submission();
        try {
            recorder.recordFailed(
                    submission.tenantId(), submission.runId(), submission.assistantMessageId(), code, message);
        } catch (RuntimeException exception) {
            LOG.error(
                    "chat.stream.failure_record_failed runId={} tenantId={}",
                    submission.runId(),
                    submission.tenantId().value(),
                    exception);
        }
        ObjectNode event = eventFactory.failed(
                submission.tenantId().value(),
                submission.runId(),
                submission.assistantMessageId(),
                submission.traceId(),
                code,
                message,
                retryable);
        try {
            writeFrame(
                    sink,
                    event.path("eventId").asText(),
                    event.path("eventType").asText(),
                    writeJson(event));
        } catch (ClientGoneException exception) {
            // 浏览器已断开：失败已落库，无需继续写流。
            LOG.debug("chat.stream.failed_event_write_skipped runId={}", submission.runId());
        }
        return EventTypes.CHAT_FAILED;
    }

    /**
     * 写 API 合成事件。
     *
     * @param sink 输出目标
     * @param event 事件 JSON
     */
    private void writeGenerated(ChatEventSink sink, ObjectNode event) {
        writeFrame(sink, event.path("eventId").asText(), event.path("eventType").asText(), writeJson(event));
    }

    /**
     * 首字前失败落库；失败时只记录日志，不掩盖原始错误。
     *
     * @param submission 提交
     * @param code 错误码
     * @param message 脱敏说明
     */
    private void recordPreFirstFailure(ChatSubmission submission, String code, String message) {
        try {
            recorder.recordPreFirstFailure(
                    submission.tenantId(), submission.runId(), submission.assistantMessageId(), code, message);
        } catch (RuntimeException exception) {
            LOG.error(
                    "chat.stream.prefirst_failure_record_failed runId={} tenantId={}",
                    submission.runId(),
                    submission.tenantId().value(),
                    exception);
        }
    }

    /**
     * 解析事件 JSON。
     *
     * @param data SSE data
     * @return 对象节点
     */
    private ObjectNode parseEvent(String data) {
        try {
            var node = objectMapper.readTree(data);
            if (!(node instanceof ObjectNode objectNode)) {
                throw new ChatEventValidationException("chat event data is not a JSON object");
            }
            return objectNode;
        } catch (JacksonException exception) {
            throw new ChatEventValidationException("chat event data is not valid JSON");
        }
    }

    /**
     * 序列化事件 JSON；必须为单行，避免破坏 SSE data 字段。
     *
     * @param event 事件
     * @return JSON
     */
    private String writeJson(ObjectNode event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException exception) {
            throw new IllegalStateException("failed to serialize chat event", exception);
        }
    }

    /**
     * 写一帧 SSE 并 flush；浏览器断开时抛 {@link ClientGoneException} 由上层停止上游。
     *
     * @param output 输出流
     * @param id 事件 ID
     * @param event 事件类型
     * @param data JSON
     */
    private static void writeFrame(ChatEventSink sink, String id, String event, String data) {
        try {
            sink.send(id, event, data);
        } catch (IOException exception) {
            throw new ClientGoneException(exception);
        }
    }

    /**
     * 映射 Worker 非 200 响应；对外不返回 Worker 内部 detail。
     *
     * @param exception Worker 错误
     * @return 对外业务异常
     */
    private RicconException mapWorkerCall(WorkerChatClient.WorkerCallException exception) {
        LOG.warn(
                "chat.worker.rejected status={} code={} detail={}",
                exception.status(),
                exception.code(),
                exception.getMessage());
        return switch (exception.status()) {
            case 400 -> new BadRequestException("chat request is invalid");
            case 403 ->
                new RicconException(
                        HttpStatus.FORBIDDEN, ErrorCodes.AUTH_FORBIDDEN, "Forbidden", "chat request is not allowed");
            case 404 -> new NotFoundException("conversation");
            case 409 -> new ConflictException("chat run is already active or finished");
            case 429 ->
                new RicconException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        ErrorCodes.CHAT_QUEUE_FULL,
                        "Too many requests",
                        "chat capacity is full");
            default ->
                new RicconException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        ErrorCodes.CHAT_UPSTREAM_FAILED,
                        "Internal server error",
                        "chat upstream failed");
        };
    }

    /**
     * 映射首字前读取/校验失败。
     *
     * @param exception 原始异常
     * @return 对外业务异常
     */
    private RicconException mapFirstFrameFailure(RuntimeException exception) {
        LOG.warn("chat.stream.prefirst_failure", exception);
        if (exception instanceof RicconException riccon) {
            return riccon;
        }
        return upstreamFailure("invalid chat worker stream", exception);
    }

    /**
     * 构造统一的上游失败异常。
     *
     * @param logMessage 日志说明
     * @param cause 原因
     * @return 对外业务异常
     */
    private RicconException upstreamFailure(String logMessage, RuntimeException cause) {
        LOG.error(logMessage, cause);
        return new RicconException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCodes.CHAT_UPSTREAM_FAILED,
                "Internal server error",
                "chat upstream failed");
    }

    /**
     * 静默关闭 Worker 流。
     *
     * @param stream Worker 流
     */
    private static void closeQuietly(WorkerChatClient.WorkerStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException exception) {
            LOG.debug("chat.stream.close_failed", exception);
        }
    }

    /**
     * 已打开并处理首个事件的对话流句柄。
     *
     * @param submission 已持久化提交
     * @param stream Worker 流
     * @param firstEventId 首个事件 ID
     * @param firstEventType 首个事件类型
     * @param firstEventJson 改写 messageId 后的首个事件 JSON
     */
    public record ChatStreamHandle(
            ChatSubmission submission,
            WorkerChatClient.WorkerStream stream,
            String firstEventId,
            String firstEventType,
            String firstEventJson)
            implements Closeable {

        /** 关闭 Worker 流。 */
        @Override
        public void close() throws IOException {
            stream.close();
        }
    }

    /** 浏览器已断开。 */
    private static final class ClientGoneException extends RuntimeException {

        /**
         * 创建异常。
         *
         * @param cause 写失败原因
         */
        private ClientGoneException(Throwable cause) {
            super(cause);
        }
    }

    /** Worker 在首字前返回 failed 事件。 */
    private static final class PreFirstWorkerFailure extends RuntimeException {

        /** 错误码。 */
        private final String code;

        /**
         * 创建异常。
         *
         * @param code 错误码
         * @param message 脱敏说明
         */
        private PreFirstWorkerFailure(String code, String message) {
            super(message);
            this.code = code;
        }

        /**
         * 返回错误码。
         *
         * @return 错误码
         */
        private String code() {
            return code;
        }
    }
}
