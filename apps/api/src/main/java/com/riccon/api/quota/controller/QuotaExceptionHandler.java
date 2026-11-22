package com.riccon.api.quota.controller;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.quota.domain.QuotaExceededException;
import com.riccon.api.quota.domain.QuotaWindows;
import com.riccon.api.quota.service.QuotaAuditService;
import com.riccon.api.web.ApiProblems;
import com.riccon.api.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 配额超限的 Web 边界。
 *
 * <p>放在 Web 边界而不是创建 run 的服务内，原因有两点：
 *
 * <ul>
 *   <li>服务层事务已经因超限异常回滚，此时写 {@code quota.exceeded} 不会与配额行锁同时占用第二个连接，
 *       避免 50 并发拒绝时 Hikari 连接池被“外层事务 + 内层 REQUIRES_NEW”互相等待耗尽；
 *   <li>审计失败必须返回 500 而不是 429；在 advice 内统一转换，保证任何调用入口（对话发送、报告任务）
 *       行为一致。
 * </ul>
 *
 * <p>排序高于全局 {@code GlobalExceptionHandler}，确保专用 429 响应体不被通用 {@code RicconException}
 * 处理器截获。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class QuotaExceptionHandler {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(QuotaExceptionHandler.class);

    /** 拒绝审计。 */
    private final QuotaAuditService quotaAuditService;

    /**
     * 创建配额异常处理器。
     *
     * @param quotaAuditService 拒绝审计
     */
    public QuotaExceptionHandler(QuotaAuditService quotaAuditService) {
        this.quotaAuditService = Objects.requireNonNull(quotaAuditService, "quotaAuditService must not be null");
    }

    /**
     * 写拒绝审计并返回专用 429。
     *
     * <p>审计失败时返回 500：这是有意的 fail-closed，表示“没有审计就不拒绝”，而不是把审计故障伪装成配额耗尽。
     *
     * @param exception 超限异常
     * @param request HTTP 请求
     * @return 429 专用响应或 500 审计失败
     */
    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ProblemDetail> handleQuotaExceeded(
            QuotaExceededException exception, HttpServletRequest request) {
        String traceId = RequestContext.traceId(request);
        try {
            quotaAuditService.recordExceeded(exception, traceId, RequestContext.requestId(request));
        } catch (RuntimeException auditFailure) {
            // 边界兜底：任何审计写入失败都必须以 500 结束，不能回退为 429；对外只返回通用错误。
            LOG.error(
                    "quota.exceeded.audit_failed tenantId={} scopeType={} window={} traceId={}",
                    exception.tenantId(),
                    exception.scopeType(),
                    exception.window(),
                    traceId,
                    auditFailure);
            return problem(
                    ApiProblems.create(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            ErrorCodes.INTERNAL,
                            "Internal server error",
                            "An unexpected error occurred.",
                            traceId),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        ProblemDetail problem = ApiProblems.create(
                HttpStatus.TOO_MANY_REQUESTS, exception.code(), exception.title(), exception.getMessage(), traceId);
        problem.setProperty("scopeType", exception.scopeType().name());
        problem.setProperty("window", exception.window().name());
        problem.setProperty("limit", exception.limit());
        problem.setProperty("used", exception.used());
        problem.setProperty("resetsAt", QuotaWindows.format(exception.resetsAt().toInstant()));
        LOG.info(
                "quota.exceeded tenantId={} scopeType={} window={} limit={} used={} traceId={}",
                exception.tenantId(),
                exception.scopeType(),
                exception.window(),
                exception.limit(),
                exception.used(),
                traceId);
        return problem(problem, HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * 包装为 problem+json 响应。
     *
     * @param problem 响应体
     * @param status HTTP 状态
     * @return 响应
     */
    private static ResponseEntity<ProblemDetail> problem(ProblemDetail problem, HttpStatus status) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
