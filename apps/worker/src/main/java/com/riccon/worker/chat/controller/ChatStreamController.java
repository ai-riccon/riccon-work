package com.riccon.worker.chat.controller;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.domain.contract.HttpHeaders;
import com.riccon.worker.chat.domain.ChatException;
import com.riccon.worker.chat.domain.ChatStreamRequest;
import com.riccon.worker.chat.service.ChatStreamService;
import com.riccon.worker.chat.service.ChatStreamService.ChatStreamSession;
import com.riccon.worker.chat.service.ServiceTokenValidator;
import com.riccon.worker.chat.service.ServiceTokenValidator.ServiceTokenClaims;
import com.riccon.worker.config.ChatStreamProperties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * API → Worker 内部对话流端点。
 *
 * <p>只接受 HMAC 服务令牌，不接受浏览器 Cookie；令牌租户、路径运行与请求体必须三方一致。
 * 首字前失败返回 ProblemDetail；首字后失败由 SSE failed 事件结束。
 *
 * <p>流使用 {@link SseEmitter} 并在独立执行器上写事件，每个 delta 立即 flush；不能使用
 * {@code StreamingResponseBody}，因为 Spring 传给业务代码的输出流会忽略 flush（TASK-0019 同源问题）。
 */
@RestController
public class ChatStreamController {

    /** 对话流服务。 */
    private final ChatStreamService chatStreamService;

    /** 服务令牌校验。 */
    private final ServiceTokenValidator tokenValidator;

    /** 流写入执行器。 */
    private final ExecutorService streamExecutor;

    /** 流配置。 */
    private final ChatStreamProperties properties;

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(ChatStreamController.class);

    /**
     * 创建控制器。
     *
     * @param chatStreamService 对话流服务
     * @param tokenValidator 令牌校验
     * @param streamExecutor 流写入执行器
     * @param properties 流配置
     */
    public ChatStreamController(
            ChatStreamService chatStreamService,
            ServiceTokenValidator tokenValidator,
            @Qualifier("chatStreamExecutor") ExecutorService streamExecutor,
            ChatStreamProperties properties) {
        this.chatStreamService = chatStreamService;
        this.tokenValidator = tokenValidator;
        this.streamExecutor = streamExecutor;
        this.properties = properties;
    }

    /**
     * 执行一次对话流。
     *
     * @param token 服务令牌
     * @param traceId 追踪 ID
     * @param request 请求
     * @return SSE 响应
     */
    @PostMapping(
            path = "/internal/v1/chat/streams",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(
            @RequestHeader(name = HttpHeaders.SERVICE_TOKEN, required = false) String token,
            @RequestHeader(name = HttpHeaders.TRACE_ID, required = false) String traceId,
            @RequestBody ChatStreamRequest request) {
        String normalizedTrace = requireTrace(traceId);
        ServiceTokenClaims claims = tokenValidator.validate(token, request.runId());
        if (!claims.tenantId().value().equals(request.tenantId())) {
            throw new ChatException(
                    400, "RICCON-CHAT-400", "Bad Request", "token tenant does not match request tenant");
        }
        if (!claims.userId().equals(request.userId())) {
            throw new ChatException(403, "RICCON-CHAT-403", "Forbidden", "token subject does not match user");
        }
        ChatStreamSession session = TenantContext.callWithTenant(
                claims.tenantId(), () -> chatStreamService.open(claims.tenantId(), request, normalizedTrace));
        long timeoutMs = properties.maxDuration().toMillis() + 30_000;
        SseEmitter emitter = new SseEmitter(timeoutMs);
        emitter.onTimeout(() -> session.abort("CLIENT_DISCONNECT"));
        emitter.onError(error -> session.abort("CLIENT_DISCONNECT"));
        try {
            streamExecutor.execute(() -> {
                try {
                    session.writeTo(emitter);
                    emitter.complete();
                } catch (RuntimeException ex) {
                    LOG.error(
                            "chat.stream.writer_failed runId={} tenantId={}",
                            request.runId(),
                            claims.tenantId().value(),
                            ex);
                    emitter.completeWithError(ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            session.failBeforeStream(
                    new ChatException(429, "RICCON-QUEUE-429", "Too Many Requests", "worker stream capacity is full"));
            throw new ChatException(429, "RICCON-QUEUE-429", "Too Many Requests", "worker stream capacity is full");
        }
        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.TRACE_ID, normalizedTrace)
                .body(emitter);
    }

    /**
     * 校验并返回追踪 ID。
     *
     * @param traceId 请求头
     * @return 追踪 ID
     */
    private static String requireTrace(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            throw new ChatException(400, "RICCON-CHAT-400", "Bad Request", "X-Trace-Id is required");
        }
        if (traceId.length() > 64) {
            throw new ChatException(400, "RICCON-CHAT-400", "Bad Request", "X-Trace-Id is too long");
        }
        return traceId;
    }
}
