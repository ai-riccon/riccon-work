package com.riccon.worker.chat.controller;

import com.riccon.platform.domain.contract.HttpHeaders;
import com.riccon.worker.chat.domain.ChatException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 内部 API 错误映射。
 *
 * <p>输出 RFC 9457 风格 ProblemDetail，字段与 {@code contracts/internal-api.yaml} 一致；
 * 不返回堆栈、提示词、凭据或内部地址。未知异常统一 500 并保留服务端堆栈日志。
 */
@RestControllerAdvice(assignableTypes = {ChatStreamController.class, RunCancelController.class})
public class InternalApiExceptionHandler {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(InternalApiExceptionHandler.class);

    /**
     * 处理对话错误。
     *
     * @param exception 对话错误
     * @param request 请求
     * @return ProblemDetail
     */
    @ExceptionHandler(ChatException.class)
    public ResponseEntity<Map<String, Object>> handleChat(ChatException exception, HttpServletRequest request) {
        Map<String, Object> body = problem(
                exception.status(), exception.code(), exception.title(), exception.getMessage(), traceId(request));
        return ResponseEntity.status(exception.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    /**
     * 处理不可解析的请求体。
     *
     * @param exception 解析错误
     * @param request 请求
     * @return ProblemDetail
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return ResponseEntity.status(400)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(
                        400, "RICCON-CHAT-400", "Bad Request", "request body is not valid JSON", traceId(request)));
    }

    /**
     * 处理未预期异常；只记录堆栈，不返回内部细节。
     *
     * @param exception 异常
     * @param request 请求
     * @return ProblemDetail
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOG.error("internal.api.unexpected_failure path={}", request.getRequestURI(), exception);
        return ResponseEntity.status(500)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(
                        500,
                        "RICCON-WORKER-500",
                        "Internal Server Error",
                        "internal worker failure",
                        traceId(request)));
    }

    /**
     * 构造 ProblemDetail 字段。
     *
     * @param status HTTP 状态
     * @param code 错误码
     * @param title 标题
     * @param detail 脱敏说明
     * @param traceId 追踪 ID
     * @return 字段
     */
    static Map<String, Object> problem(int status, String code, String title, String detail, String traceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://riccon.example/problems/" + code);
        body.put("title", title);
        body.put("status", status);
        body.put("code", code);
        body.put("traceId", traceId);
        body.put("detail", detail == null ? title : detail);
        return body;
    }

    /**
     * 读取追踪 ID。
     *
     * @param request 请求
     * @return 追踪 ID；缺失时为 unknown
     */
    private static String traceId(HttpServletRequest request) {
        String value = request.getHeader(HttpHeaders.TRACE_ID);
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
