package com.riccon.worker.chat.service;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.model.ModelPurpose;
import com.riccon.worker.agent.domain.AgentException;
import com.riccon.worker.agent.domain.AgentSession;
import com.riccon.worker.agent.domain.ConversationMessage;
import com.riccon.worker.agent.service.ChatAgentService;
import com.riccon.worker.audit.service.AuditService;
import com.riccon.worker.chat.domain.ChatEvent;
import com.riccon.worker.chat.domain.ChatException;
import com.riccon.worker.chat.domain.ChatStreamRequest;
import com.riccon.worker.chat.repository.ConversationRepository;
import com.riccon.worker.chat.repository.UserAccessRepository;
import com.riccon.worker.common.UuidV7;
import com.riccon.worker.config.ChatStreamProperties;
import com.riccon.worker.model.domain.ModelException;
import com.riccon.worker.model.domain.ResolvedModel;
import com.riccon.worker.model.service.ModelInvocationService;
import com.riccon.worker.model.service.ModelInvocationService.InvocationHandle;
import com.riccon.worker.model.service.ModelRegistry;
import com.riccon.worker.run.domain.RunDetail;
import com.riccon.worker.run.repository.RunRepository;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import java.io.IOException;
import java.time.Clock;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * 内部对话流编排。
 *
 * <p>流式路径不进入 outbox（ADR-0008）：校验运行与租户绑定、限并发、解析模型、检索上下文、
 * 构建 ReActAgent，并在首字前失败时返回 ProblemDetail；首字后失败发送 failed 事件。
 * 所有阻塞读取都在显式租户作用域内执行，取消通过 {@link RunCancellationRegistry} 中断 Agent。
 */
@Component
public class ChatStreamService {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(ChatStreamService.class);

    /** 运行仓库。 */
    private final RunRepository runRepository;

    /** 会话仓库。 */
    private final ConversationRepository conversationRepository;

    /** 用户状态仓库。 */
    private final UserAccessRepository userAccessRepository;

    /** 对话 Agent 构建。 */
    private final ChatAgentService chatAgentService;

    /** 模型解析。 */
    private final ModelRegistry modelRegistry;

    /** 调用记录。 */
    private final ModelInvocationService invocationService;

    /** 事件工厂。 */
    private final ChatEventFactory eventFactory;

    /** 审计服务。 */
    private final AuditService auditService;

    /** 取消注册表。 */
    private final RunCancellationRegistry cancellationRegistry;

    /** 流式配置。 */
    private final ChatStreamProperties properties;

    /** JSON 序列化。 */
    private final ObjectMapper objectMapper;

    /** UTC 时钟。 */
    private final Clock clock;

    /** 并发信号量。 */
    private final Semaphore semaphore;

    /**
     * 创建对话流服务。
     *
     * @param runRepository 运行仓库
     * @param conversationRepository 会话仓库
     * @param chatAgentService 对话 Agent 服务
     * @param modelRegistry 模型解析
     * @param invocationService 调用记录
     * @param eventFactory 事件工厂
     * @param cancellationRegistry 取消注册表
     * @param properties 流式配置
     * @param objectMapper JSON 序列化
     * @param clock UTC 时钟
     */
    public ChatStreamService(
            RunRepository runRepository,
            ConversationRepository conversationRepository,
            UserAccessRepository userAccessRepository,
            ChatAgentService chatAgentService,
            ModelRegistry modelRegistry,
            ModelInvocationService invocationService,
            ChatEventFactory eventFactory,
            AuditService auditService,
            RunCancellationRegistry cancellationRegistry,
            ChatStreamProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.conversationRepository = Objects.requireNonNull(conversationRepository, "conversationRepository");
        this.userAccessRepository = Objects.requireNonNull(userAccessRepository, "userAccessRepository");
        this.chatAgentService = Objects.requireNonNull(chatAgentService, "chatAgentService");
        this.modelRegistry = Objects.requireNonNull(modelRegistry, "modelRegistry");
        this.invocationService = Objects.requireNonNull(invocationService, "invocationService");
        this.eventFactory = Objects.requireNonNull(eventFactory, "eventFactory");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.cancellationRegistry = Objects.requireNonNull(cancellationRegistry, "cancellationRegistry");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.semaphore = new Semaphore(properties.maxConcurrentStreams(), true);
    }

    /**
     * 打开对话流并等待首个可发送事件。
     *
     * @param tenantId 令牌租户
     * @param request 请求
     * @param traceId 追踪 ID
     * @return 已就绪的流会话
     * @throws ChatException 校验、模型或首字失败
     */
    public ChatStreamSession open(TenantId tenantId, ChatStreamRequest request, String traceId) {
        return TenantContext.callWithTenant(tenantId, () -> openInTenant(tenantId, request, traceId));
    }

    /**
     * 在租户作用域内打开对话流。
     *
     * @param tenantId 租户
     * @param request 请求
     * @param traceId 追踪 ID
     * @return 流会话
     */
    private ChatStreamSession openInTenant(TenantId tenantId, ChatStreamRequest request, String traceId) {
        if (request.fileIds().size() > 5) {
            throw new ChatException(400, "RICCON-CHAT-400", "Bad Request", "fileIds must contain at most 5 items");
        }
        RunDetail run = runRepository.findDetail(tenantId, request.runId()).orElseThrow(() -> {
            auditService.tenantAccessDenied(tenantId, traceId, null, "unknown", "RUN_NOT_VISIBLE");
            return new ChatException(404, "RICCON-TENANT-404", "Not Found", "run does not exist for this tenant");
        });
        if (run.kind() != com.riccon.platform.domain.research.RunKind.CHAT) {
            throw new ChatException(403, "RICCON-CHAT-403", "Forbidden", "run is not a chat run");
        }
        if (!request.conversationId().equals(run.conversationId())
                || !request.userId().equals(run.userId())) {
            throw new ChatException(403, "RICCON-CHAT-403", "Forbidden", "run binding does not match request");
        }
        if (run.terminal()) {
            throw new ChatException(409, "RICCON-CHAT-409", "Conflict", "run is already terminal");
        }
        if (!userAccessRepository.isActive(tenantId, run.userId())) {
            auditService.userAccessDenied(tenantId, traceId, null, run.userId());
            throw new ChatException(403, "RICCON-TENANT-403", "Forbidden", "run owner is disabled");
        }
        if (!conversationRepository.existsForUser(tenantId, request.conversationId(), request.userId())) {
            throw new ChatException(404, "RICCON-TENANT-404", "Not Found", "conversation is not available");
        }
        ConversationMessage userMessage = conversationRepository
                .findUserMessage(tenantId, request.conversationId(), request.userMessageId())
                .orElseThrow(() ->
                        new ChatException(404, "RICCON-TENANT-404", "Not Found", "user message is not available"));
        List<ConversationMessage> history =
                conversationRepository.history(tenantId, request.conversationId(), properties.maxHistoryMessages());
        if (!semaphore.tryAcquire()) {
            throw new ChatException(429, "RICCON-QUEUE-429", "Too Many Requests", "worker stream capacity is full");
        }
        boolean sessionCreated = false;
        AgentSession agentSession = null;
        try {
            if (!runRepository.markStreamRunning(tenantId, request.runId(), run.attempt())) {
                throw new ChatException(409, "RICCON-CHAT-409", "Conflict", "run is already being streamed");
            }
            ResolvedModel model;
            try {
                model = modelRegistry.resolve(tenantId);
            } catch (ModelException ex) {
                runRepository.markFailed(tenantId, request.runId(), ex.errorCode(), ex.getMessage());
                throw new ChatException(500, "RICCON-MODEL-500", "Internal Server Error", ex.getMessage());
            } catch (RuntimeException ex) {
                runRepository.markFailed(tenantId, request.runId(), "RICCON-MODEL-500", "model resolution failed");
                throw new ChatException(500, "RICCON-MODEL-500", "Internal Server Error", "model resolution failed");
            }
            try {
                agentSession = chatAgentService.prepare(
                        tenantId,
                        request.runId(),
                        request.conversationId(),
                        request.userId(),
                        userMessage.content(),
                        request.fileIds(),
                        history,
                        model);
            } catch (AgentException ex) {
                runRepository.markFailed(tenantId, request.runId(), ex.errorCode(), ex.getMessage());
                throw new ChatException(statusFor(ex), ex.errorCode(), "Bad Request", ex.getMessage());
            } catch (RuntimeException ex) {
                // 检索/数据库失败属于 Worker 内部错误；运行必须落 FAILED，不能停在 RUNNING。
                runRepository.markFailed(tenantId, request.runId(), "RICCON-WORKER-500", "agent preparation failed");
                throw new ChatException(500, "RICCON-WORKER-500", "Internal Server Error", "agent preparation failed");
            }
            UUID messageId = UuidV7.generate();
            InvocationHandle invocation;
            try {
                invocation = invocationService.start(
                        tenantId,
                        request.runId(),
                        request.conversationId(),
                        // API 尚未持久化 assistant 占位消息；message_id 有外键，先留空，SSE 事件仍携带生成的 messageId。
                        null,
                        model.model().id(),
                        model.model().modelKey(),
                        ModelPurpose.CHAT,
                        traceId,
                        null);
            } catch (ModelException ex) {
                close(agentSession);
                runRepository.markFailed(tenantId, request.runId(), ex.errorCode(), ex.getMessage());
                throw new ChatException(500, "RICCON-MODEL-500", "Internal Server Error", ex.getMessage());
            }
            long startedAt = clock.millis();
            Iterator<AgentEvent> iterator;
            try {
                iterator = agentSession
                        .events()
                        .timeout(properties.idleTimeout())
                        .toIterable()
                        .iterator();
            } catch (RuntimeException ex) {
                ChatException failure = translate(ex);
                fail(request.runId(), invocation, traceId, failure, null);
                close(agentSession);
                throw failure;
            }
            String firstDelta = null;
            try {
                while (iterator.hasNext()) {
                    AgentEvent event = iterator.next();
                    if (event instanceof TextBlockDeltaEvent delta
                            && delta.getDelta() != null
                            && !delta.getDelta().isEmpty()) {
                        firstDelta = delta.getDelta();
                        break;
                    }
                    if (event instanceof RequireUserConfirmEvent) {
                        throw new ChatException(
                                500,
                                "RICCON-CHAT-500",
                                "Internal Server Error",
                                "chat run unexpectedly requires approval");
                    }
                }
            } catch (RuntimeException ex) {
                ChatException failure = translate(ex);
                fail(request.runId(), invocation, traceId, failure, null);
                close(agentSession);
                throw failure;
            }
            if (firstDelta == null) {
                ChatException failure =
                        new ChatException(500, "RICCON-MODEL-502", "Bad Gateway", "model returned no visible answer");
                fail(request.runId(), invocation, traceId, failure, null);
                close(agentSession);
                throw failure;
            }
            int firstTokenMs = (int) Math.max(0, clock.millis() - startedAt);
            ChatStreamSession session;
            try {
                session = new ChatStreamSession(
                        tenantId,
                        request,
                        traceId,
                        messageId,
                        agentSession,
                        iterator,
                        firstDelta,
                        firstTokenMs,
                        invocation,
                        startedAt);
            } catch (RuntimeException ex) {
                ChatException failure =
                        new ChatException(409, "RICCON-CHAT-409", "Conflict", "run is already being streamed");
                fail(request.runId(), invocation, traceId, failure, firstTokenMs);
                close(agentSession);
                throw failure;
            }
            sessionCreated = true;
            return session;
        } finally {
            if (!sessionCreated) {
                semaphore.release();
            }
        }
    }

    /**
     * 将 Agent 异常映射为 HTTP 状态。
     *
     * @param ex Agent 异常
     * @return 状态码
     */
    private static int statusFor(AgentException ex) {
        return AgentException.NOT_FOUND.equals(ex.errorCode()) ? 404 : 400;
    }

    /**
     * 失败时写调用终态与运行状态；写失败只记录日志，避免掩盖原始错误。
     *
     * @param runId 运行 ID
     * @param invocation 调用句柄
     * @param traceId 追踪 ID
     * @param failure 对话错误
     * @param firstTokenMs 首字延迟
     */
    private void fail(
            UUID runId, InvocationHandle invocation, String traceId, ChatException failure, Integer firstTokenMs) {
        try {
            invocationService.failed(invocation, traceId, null, failure.code(), false, firstTokenMs);
        } catch (RuntimeException ex) {
            LOG.error(
                    "chat.stream.terminal_write_failed tenantId={} errorCode={}",
                    invocation.tenantId().value(),
                    failure.code(),
                    ex);
        }
        try {
            runRepository.markFailed(invocation.tenantId(), runId, failure.code(), failure.getMessage());
        } catch (RuntimeException ex) {
            LOG.error("chat.stream.run_failed_write_failed runId={}", runId, ex);
        }
    }

    /**
     * 翻译底层异常。
     *
     * @param ex 异常
     * @return 对话错误
     */
    private static ChatException translate(RuntimeException ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ModelException model) {
                return new ChatException(500, model.errorCode(), "Internal Server Error", model.getMessage());
            }
            if (current instanceof ChatException chat) {
                return chat;
            }
            if (current instanceof TimeoutException) {
                return new ChatException(504, "RICCON-MODEL-504", "Gateway Timeout", "model stream idle timeout");
            }
            current = current.getCause();
        }
        return new ChatException(500, "RICCON-MODEL-502", "Bad Gateway", "model stream failed");
    }

    /**
     * 关闭 Agent，忽略重复关闭错误。
     *
     * @param session Agent 会话
     */
    private static void close(AgentSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (RuntimeException ex) {
            LOG.warn("chat.agent.close_failed", ex);
        }
    }

    /**
     * 单个对话流会话。
     *
     * <p>持有 Agent、事件迭代器、取消句柄与信号量；{@link #writeTo(SseEmitter)} 必须在
     * 请求线程之外执行，并在方法内部显式恢复租户上下文。每个事件通过 {@code SseEmitter.send}
     * 立即 flush，不能用 {@code StreamingResponseBody}（其输出流会忽略 flush）。
     */
    public final class ChatStreamSession {

        /** 租户。 */
        private final TenantId tenantId;

        /** 请求。 */
        private final ChatStreamRequest request;

        /** 追踪 ID。 */
        private final String traceId;

        /** 回答消息 ID。 */
        private final UUID messageId;

        /** Agent 会话。 */
        private final AgentSession agentSession;

        /** 事件迭代器。 */
        private final Iterator<AgentEvent> iterator;

        /** 首个增量。 */
        private final String firstDelta;

        /** 首字延迟。 */
        private final int firstTokenMs;

        /** 调用句柄。 */
        private final InvocationHandle invocation;

        /** 开始时间。 */
        private final long startedAt;

        /** 取消句柄。 */
        private final RunCancellationRegistry.RunCancellation cancellation;

        /** 是否已清理。 */
        private boolean cleaned;

        /**
         * 创建会话并注册取消句柄。
         *
         * @param tenantId 租户
         * @param request 请求
         * @param traceId 追踪 ID
         * @param messageId 消息 ID
         * @param agentSession Agent 会话
         * @param iterator 事件迭代器
         * @param firstDelta 首个增量
         * @param firstTokenMs 首字延迟
         * @param invocation 调用句柄
         * @param startedAt 开始时间
         */
        private ChatStreamSession(
                TenantId tenantId,
                ChatStreamRequest request,
                String traceId,
                UUID messageId,
                AgentSession agentSession,
                Iterator<AgentEvent> iterator,
                String firstDelta,
                int firstTokenMs,
                InvocationHandle invocation,
                long startedAt) {
            this.tenantId = tenantId;
            this.request = request;
            this.traceId = traceId;
            this.messageId = messageId;
            this.agentSession = agentSession;
            this.iterator = iterator;
            this.firstDelta = firstDelta;
            this.firstTokenMs = firstTokenMs;
            this.invocation = invocation;
            this.startedAt = startedAt;
            this.cancellation = cancellationRegistry.register(request.runId(), agentSession.agent());
        }

        /**
         * 将事件写入 SSE 发射器。
         *
         * @param emitter SSE 发射器
         */
        public void writeTo(SseEmitter emitter) {
            try (TenantContext.TenantScope ignored = TenantContext.open(tenantId)) {
                writeInTenant(new SseEmitterChatSink(emitter, objectMapper));
            } finally {
                cleanup();
            }
        }

        /**
         * 请求取消 Agent；用于客户端断开或发射器超时，由流线程写终态并清理。
         *
         * @param reason 取消原因
         */
        public void abort(String reason) {
            cancellation.cancel(reason);
            close(agentSession);
        }

        /**
         * 在执行器拒绝前终结运行，避免留下 RUNNING 状态。
         *
         * @param failure 对话错误
         */
        public void failBeforeStream(ChatException failure) {
            fail(request.runId(), invocation, traceId, failure, null);
            cleanup();
        }

        /**
         * 在租户作用域内写流。
         *
         * @param writer SSE 写入器
         */
        private void writeInTenant(ChatEventSink writer) {
            Msg result = null;
            boolean ended = false;
            try {
                writer.write(eventFactory.started(
                        tenantId.value(),
                        request.runId(),
                        messageId,
                        traceId,
                        agentSession.model().model().id(),
                        agentSession.model().model().modelKey()));
                ChatEvent citation = eventFactory.citations(
                        tenantId.value(), request.runId(), messageId, traceId, agentSession.citations());
                if (citation != null) {
                    writer.write(citation);
                }
                writer.write(eventFactory.delta(tenantId.value(), request.runId(), messageId, traceId, firstDelta));
                while (iterator.hasNext()) {
                    if (cancellation.cancelled()) {
                        throw new StreamCancelledException(cancellation.reason());
                    }
                    if (clock.millis() - startedAt > properties.maxDuration().toMillis()) {
                        throw new ChatException(
                                504, "RICCON-MODEL-504", "Gateway Timeout", "chat stream exceeded total duration");
                    }
                    AgentEvent event = iterator.next();
                    if (event instanceof TextBlockDeltaEvent delta
                            && delta.getDelta() != null
                            && !delta.getDelta().isEmpty()) {
                        writer.write(eventFactory.delta(
                                tenantId.value(), request.runId(), messageId, traceId, delta.getDelta()));
                    } else if (event instanceof AgentResultEvent resultEvent) {
                        result = resultEvent.getResult();
                    } else if (event instanceof AgentEndEvent) {
                        ended = true;
                        break;
                    } else if (event instanceof RequireUserConfirmEvent) {
                        throw new ChatException(
                                500,
                                "RICCON-CHAT-500",
                                "Internal Server Error",
                                "chat run unexpectedly requires approval");
                    }
                }
                if (cancellation.cancelled()) {
                    throw new StreamCancelledException(cancellation.reason());
                }
                if (!ended && result == null && !iterator.hasNext()) {
                    // 流正常结束但既无终止事件也无结果：视为上游协议不完整。
                    throw new ChatException(
                            502, "RICCON-MODEL-502", "Bad Gateway", "model stream ended without result");
                }
                ChatUsage usage = result == null ? null : result.getChatUsage();
                invocationService.succeeded(
                        invocation,
                        traceId,
                        null,
                        firstTokenMs,
                        usage == null ? null : usage.getInputTokens(),
                        usage == null ? null : usage.getOutputTokens());
                runRepository.markSucceeded(tenantId, request.runId());
                writer.write(eventFactory.completed(
                        tenantId.value(),
                        request.runId(),
                        messageId,
                        traceId,
                        finishReason(result),
                        usage == null ? null : usage.getInputTokens(),
                        usage == null ? null : usage.getOutputTokens(),
                        firstTokenMs));
            } catch (StreamCancelledException ex) {
                cancel(writer, ex.reason());
            } catch (IOException ex) {
                // 客户端断开：停止上游模型，保留已生成内容；无法再向断开的连接写事件。
                LOG.info("chat.stream.client_disconnected runId={} tenantId={}", request.runId(), tenantId.value());
                cancelState("CLIENT_DISCONNECT");
            } catch (ChatException ex) {
                failStream(writer, ex);
            } catch (RuntimeException ex) {
                if (cancellation.cancelled()) {
                    cancel(writer, cancellation.reason());
                } else {
                    failStream(writer, translate(ex));
                }
            }
        }

        /**
         * 写失败事件并推进运行终态。
         *
         * @param writer SSE 写入器
         * @param failure 对话错误
         */
        private void failStream(ChatEventSink writer, ChatException failure) {
            try {
                invocationService.failed(invocation, traceId, null, failure.code(), false, firstTokenMs);
            } catch (RuntimeException ex) {
                LOG.error("chat.stream.failure_terminal_write_failed runId={}", request.runId(), ex);
            }
            try {
                runRepository.markFailed(tenantId, request.runId(), failure.code(), failure.getMessage());
            } catch (RuntimeException ex) {
                LOG.error("chat.stream.failure_run_write_failed runId={}", request.runId(), ex);
            }
            try {
                writer.write(eventFactory.failed(
                        tenantId.value(),
                        request.runId(),
                        messageId,
                        traceId,
                        failure.code(),
                        failure.getMessage(),
                        false));
            } catch (IOException ex) {
                LOG.warn("chat.stream.failed_event_write_failed runId={}", request.runId(), ex);
            }
            LOG.warn(
                    "chat.stream.failed runId={} tenantId={} errorCode={}",
                    request.runId(),
                    tenantId.value(),
                    failure.code());
        }

        /**
         * 取消流并写 stopped。
         *
         * @param writer SSE 写入器
         * @param reason 原因
         */
        private void cancel(ChatEventSink writer, String reason) {
            String normalized = cancelState(reason);
            try {
                writer.write(eventFactory.stopped(tenantId.value(), request.runId(), messageId, traceId, normalized));
            } catch (IOException ex) {
                LOG.warn("chat.stream.stopped_event_write_failed runId={}", request.runId(), ex);
            }
        }

        /**
         * 推进取消终态并返回契约原因。
         *
         * @param reason 原始原因
         * @return 合法原因
         */
        private String cancelState(String reason) {
            String normalized = normalizeReason(reason);
            try {
                invocationService.cancelled(invocation, traceId, null, firstTokenMs);
                runRepository.markCancelled(tenantId, request.runId());
            } catch (RuntimeException ex) {
                LOG.error("chat.stream.cancel_terminal_write_failed runId={}", request.runId(), ex);
            }
            LOG.info(
                    "chat.stream.stopped runId={} tenantId={} reason={}",
                    request.runId(),
                    tenantId.value(),
                    normalized);
            return normalized;
        }

        /**
         * 清理 Agent、注册表与信号量；幂等。
         */
        private synchronized void cleanup() {
            if (cleaned) {
                return;
            }
            cleaned = true;
            cancellationRegistry.unregister(request.runId(), cancellation);
            close(agentSession);
            semaphore.release();
        }
    }

    /**
     * 将取消原因限制为契约枚举。
     *
     * @param reason 原始原因
     * @return 合法原因
     */
    private static String normalizeReason(String reason) {
        if ("CLIENT_DISCONNECT".equals(reason) || "PERMISSION_REVOKED".equals(reason)) {
            return reason;
        }
        return "USER_CANCELLED";
    }

    /**
     * 根据结果生成完成原因。
     *
     * @param result 结果消息
     * @return stop/length/error
     */
    private static String finishReason(Msg result) {
        if (result == null) {
            return "stop";
        }
        GenerateReason reason = result.getGenerateReason();
        if (reason == GenerateReason.MAX_ITERATIONS) {
            return "length";
        }
        if (reason == GenerateReason.INTERRUPTED) {
            return "error";
        }
        return "stop";
    }

    /** 流取消信号，携带契约原因。 */
    private static final class StreamCancelledException extends RuntimeException {

        /** 原因。 */
        private final String reason;

        /**
         * 创建取消信号。
         *
         * @param reason 原因
         */
        private StreamCancelledException(String reason) {
            super("stream cancelled");
            this.reason = reason;
        }

        /**
         * 返回原因。
         *
         * @return 原因
         */
        private String reason() {
            return reason;
        }
    }
}
