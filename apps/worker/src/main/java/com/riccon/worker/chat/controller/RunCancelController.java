package com.riccon.worker.chat.controller;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.domain.contract.HttpHeaders;
import com.riccon.worker.chat.domain.CancelRunRequest;
import com.riccon.worker.chat.domain.ChatException;
import com.riccon.worker.chat.service.ServiceTokenValidator;
import com.riccon.worker.chat.service.ServiceTokenValidator.ServiceTokenClaims;
import com.riccon.worker.run.service.RunCancellationService;
import com.riccon.worker.run.service.RunCancellationService.Outcome;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部运行取消端点。
 *
 * <p>幂等：已取消返回 202；已成功/失败返回 409；不存在或跨租户统一 404。活跃对话流会被中断，
 * 等待审批或未执行的任务直接推进数据库状态。
 */
@RestController
public class RunCancelController {

    /** 允许的取消原因。 */
    private static final Set<String> REASONS = Set.of("USER_CANCELLED", "CLIENT_DISCONNECT", "PERMISSION_REVOKED");

    /** 取消服务。 */
    private final RunCancellationService cancellationService;

    /** 令牌校验。 */
    private final ServiceTokenValidator tokenValidator;

    /**
     * 创建控制器。
     *
     * @param cancellationService 取消服务
     * @param tokenValidator 令牌校验
     */
    public RunCancelController(RunCancellationService cancellationService, ServiceTokenValidator tokenValidator) {
        this.cancellationService = cancellationService;
        this.tokenValidator = tokenValidator;
    }

    /**
     * 取消运行。
     *
     * @param runId 运行 ID
     * @param token 服务令牌
     * @param traceId 追踪 ID
     * @param request 可选原因
     * @return 202/404/409
     */
    @PostMapping(path = "/internal/v1/runs/{runId}/cancel")
    public ResponseEntity<Void> cancel(
            @PathVariable UUID runId,
            @RequestHeader(name = HttpHeaders.SERVICE_TOKEN, required = false) String token,
            @RequestHeader(name = HttpHeaders.TRACE_ID, required = false) String traceId,
            @RequestBody(required = false) CancelRunRequest request) {
        requireTrace(traceId);
        ServiceTokenClaims claims = tokenValidator.validate(token, runId);
        String reason = normalize(request == null ? null : request.reason());
        Outcome outcome = TenantContext.callWithTenant(
                claims.tenantId(), () -> cancellationService.cancel(claims.tenantId(), runId, reason, traceId));
        return switch (outcome) {
            case REQUESTED, CANCELLED, ALREADY_CANCELLED ->
                ResponseEntity.accepted().build();
            case CONFLICT -> throw new ChatException(409, "RICCON-CHAT-409", "Conflict", "run is already terminal");
            case NOT_FOUND ->
                throw new ChatException(404, "RICCON-TENANT-404", "Not Found", "run does not exist for this tenant");
        };
    }

    /**
     * 校验并返回原因。
     *
     * @param reason 原始原因
     * @return 合法原因
     */
    private static String normalize(String reason) {
        if (reason == null || reason.isBlank()) {
            return "USER_CANCELLED";
        }
        if (!REASONS.contains(reason)) {
            throw new ChatException(400, "RICCON-CHAT-400", "Bad Request", "cancel reason is not allowed");
        }
        return reason;
    }

    /**
     * 校验追踪 ID。
     *
     * @param traceId 请求头
     */
    private static void requireTrace(String traceId) {
        if (traceId == null || traceId.isBlank() || traceId.length() > 64) {
            throw new ChatException(400, "RICCON-CHAT-400", "Bad Request", "X-Trace-Id is required");
        }
    }
}
