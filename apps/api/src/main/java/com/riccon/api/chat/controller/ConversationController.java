package com.riccon.api.chat.controller;

import com.riccon.api.chat.dto.ConversationCreateRequest;
import com.riccon.api.chat.dto.ConversationDetailResponse;
import com.riccon.api.chat.dto.ConversationPageResponse;
import com.riccon.api.chat.dto.ConversationResponse;
import com.riccon.api.chat.dto.ConversationUpdateRequest;
import com.riccon.api.chat.dto.MessagePageResponse;
import com.riccon.api.chat.dto.SendMessageRequest;
import com.riccon.api.chat.dto.StopRunRequest;
import com.riccon.api.chat.service.ChatService;
import com.riccon.api.chat.service.ConversationService;
import com.riccon.api.chat.service.SseEmitterSink;
import com.riccon.api.config.WorkerClientProperties;
import com.riccon.api.error.BadRequestException;
import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.RicconException;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.platform.domain.contract.HttpHeaders;
import com.riccon.platform.domain.identity.PermissionCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 会话、消息与 SSE 接口。
 *
 * <p>路径、权限码与 OpenAPI 一致：读取要求 {@code conversation.read}，创建/发送/停止/删除要求
 * {@code conversation.write}。所有写操作由 Spring Security 校验 CSRF；跨租户或他人会话统一 404。
 *
 * <p>SSE 使用 {@link SseEmitter} 逐事件 flush；不能使用 {@code StreamingResponseBody}，因为 Spring 传给业务代码的是
 * {@code NonFlushingOutputStream}，会缓冲到流结束，浏览器无法看到首字。
 */
@RestController
@RequestMapping("/api/v1")
public class ConversationController {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ConversationController.class);

    /** Idempotency-Key 最小长度。 */
    private static final int MIN_IDEMPOTENCY_KEY_LENGTH = 8;

    /** Idempotency-Key 最大长度。 */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    /** SSE 异步超时相对总时长上限的余量。 */
    private static final long SSE_TIMEOUT_MARGIN_MS = 60_000L;

    /** 会话服务。 */
    private final ConversationService conversationService;

    /** 对话流服务。 */
    private final ChatService chatService;

    /** 对话流执行器。 */
    private final ExecutorService chatStreamExecutor;

    /** Worker 客户端配置，提供流总时长上限。 */
    private final WorkerClientProperties workerClientProperties;

    /**
     * 创建控制器。
     *
     * @param conversationService 会话服务
     * @param chatService 对话流服务
     * @param chatStreamExecutor 对话流执行器
     * @param workerClientProperties Worker 客户端配置
     */
    public ConversationController(
            ConversationService conversationService,
            ChatService chatService,
            @Qualifier("chatStreamExecutor") ExecutorService chatStreamExecutor,
            WorkerClientProperties workerClientProperties) {
        this.conversationService = conversationService;
        this.chatService = chatService;
        this.chatStreamExecutor = chatStreamExecutor;
        this.workerClientProperties = workerClientProperties;
    }

    /**
     * 列出工作空间会话。
     *
     * @param workspaceId 工作空间 ID
     * @param page 页码
     * @param size 每页大小
     * @param authentication 当前认证
     * @return 会话分页
     */
    @GetMapping("/workspaces/{workspaceId}/conversations")
    @RequirePermission(PermissionCodes.CONVERSATION_READ)
    public ConversationPageResponse list(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return conversationService.list(actor, workspaceId, PageQuery.of(page, size));
    }

    /**
     * 新建会话。
     *
     * @param workspaceId 工作空间 ID
     * @param body 可选标题
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 新会话
     */
    @PostMapping("/workspaces/{workspaceId}/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(PermissionCodes.CONVERSATION_WRITE)
    public ConversationResponse create(
            @PathVariable UUID workspaceId,
            @RequestBody(required = false) ConversationCreateRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return conversationService.create(
                actor, workspaceId, body, RequestContext.traceId(request), RequestContext.requestId(request));
    }

    /**
     * 读取会话详情与最近消息。
     *
     * @param conversationId 会话 ID
     * @param authentication 当前认证
     * @return 会话详情
     */
    @GetMapping("/conversations/{conversationId}")
    @RequirePermission(PermissionCodes.CONVERSATION_READ)
    public ConversationDetailResponse get(@PathVariable UUID conversationId, Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return conversationService.get(actor, conversationId);
    }

    /**
     * 修改会话标题。
     *
     * @param conversationId 会话 ID
     * @param body 标题请求
     * @param authentication 当前认证
     * @return 更新后的会话
     */
    @PatchMapping("/conversations/{conversationId}")
    @RequirePermission(PermissionCodes.CONVERSATION_WRITE)
    public ConversationResponse update(
            @PathVariable UUID conversationId,
            @RequestBody ConversationUpdateRequest body,
            Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return conversationService.update(actor, conversationId, body);
    }

    /**
     * 删除会话（软删除）。
     *
     * @param conversationId 会话 ID
     * @param authentication 当前认证
     * @param request HTTP 请求
     */
    @DeleteMapping("/conversations/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(PermissionCodes.CONVERSATION_WRITE)
    public void delete(@PathVariable UUID conversationId, Authentication authentication, HttpServletRequest request) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        conversationService.delete(
                actor, conversationId, RequestContext.traceId(request), RequestContext.requestId(request));
    }

    /**
     * 列出会话消息。
     *
     * @param conversationId 会话 ID
     * @param beforeSeq 向前翻页游标
     * @param size 每页大小
     * @param authentication 当前认证
     * @return 消息分页
     */
    @GetMapping("/conversations/{conversationId}/messages")
    @RequirePermission(PermissionCodes.CONVERSATION_READ)
    public MessagePageResponse listMessages(
            @PathVariable UUID conversationId,
            @RequestParam(required = false) Long beforeSeq,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return conversationService.listMessages(actor, conversationId, beforeSeq, size);
    }

    /**
     * 发送消息并以 SSE 返回回答。
     *
     * <p>首字前失败由 {@link ChatService#send} 抛业务异常，统一映射为 ProblemDetail；首字后失败由 failed 事件结束流。
     * 流在独立执行器上写 SseEmitter，请求线程立即返回，避免占用容器线程。
     *
     * @param conversationId 会话 ID
     * @param body 发送请求
     * @param idempotencyKey 可选幂等键
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return SSE 响应
     */
    @PostMapping("/conversations/{conversationId}/messages")
    @RequirePermission(PermissionCodes.CONVERSATION_WRITE)
    public ResponseEntity<SseEmitter> sendMessage(
            @PathVariable UUID conversationId,
            @RequestBody SendMessageRequest body,
            @RequestHeader(name = HttpHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        validateIdempotencyKey(idempotencyKey);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        String traceId = RequestContext.traceId(request);
        ChatService.ChatStreamHandle handle =
                chatService.send(actor, conversationId, body, traceId, RequestContext.requestId(request));
        long timeoutMs = workerClientProperties.maxDuration().toMillis() + SSE_TIMEOUT_MARGIN_MS;
        SseEmitter emitter = new SseEmitter(timeoutMs);
        emitter.onCompletion(() -> closeQuietly(handle));
        emitter.onError(error -> closeQuietly(handle));
        emitter.onTimeout(() -> closeQuietly(handle));
        try {
            chatStreamExecutor.execute(() -> {
                try {
                    chatService.transfer(handle, new SseEmitterSink(emitter));
                    emitter.complete();
                } catch (RuntimeException exception) {
                    LOG.error(
                            "chat.stream.writer_failed runId={} tenantId={}",
                            handle.submission().runId(),
                            handle.submission().tenantId().value(),
                            exception);
                    emitter.completeWithError(exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            closeQuietly(handle);
            LOG.warn(
                    "chat.stream.capacity_rejected runId={} tenantId={}",
                    handle.submission().runId(),
                    handle.submission().tenantId().value());
            throw new RicconException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    ErrorCodes.CHAT_QUEUE_FULL,
                    "Too many requests",
                    "chat capacity is full");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.TRACE_ID, traceId)
                .body(emitter);
    }

    /**
     * 停止当前回答。
     *
     * @param conversationId 会话 ID
     * @param body 停止请求
     * @param idempotencyKey 可选幂等键
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 202
     */
    @PostMapping("/conversations/{conversationId}/stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequirePermission(PermissionCodes.CONVERSATION_WRITE)
    public void stopGeneration(
            @PathVariable UUID conversationId,
            @RequestBody StopRunRequest body,
            @RequestHeader(name = HttpHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        validateIdempotencyKey(idempotencyKey);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        chatService.stop(
                actor, conversationId, body, RequestContext.traceId(request), RequestContext.requestId(request));
    }

    /**
     * 校验可选 Idempotency-Key 长度。
     *
     * @param idempotencyKey 请求头
     */
    private static void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return;
        }
        if (idempotencyKey.length() < MIN_IDEMPOTENCY_KEY_LENGTH
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BadRequestException("Idempotency-Key must be between " + MIN_IDEMPOTENCY_KEY_LENGTH + " and "
                    + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
    }

    /**
     * 静默关闭 Worker 流；SSE 完成/错误/超时回调可能多次触发。
     *
     * @param handle 流句柄
     */
    private static void closeQuietly(ChatService.ChatStreamHandle handle) {
        try {
            handle.close();
        } catch (IOException exception) {
            LOG.debug("chat.stream.handle_close_failed", exception);
        }
    }
}
