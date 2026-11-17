package com.riccon.api.web;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.RicconException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * REST 边界统一错误映射。
 *
 * <p>所有错误都转换为 RFC 9457 {@code application/problem+json}，并附带稳定 {@code code} 与 {@code traceId}。
 * 内部异常只写日志，不把堆栈、SQL、主机或密钥返回给客户端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 错误日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 映射业务异常。
     *
     * @param exception 业务异常
     * @param request HTTP 请求
     * @return ProblemDetail 响应
     */
    @ExceptionHandler(RicconException.class)
    public ResponseEntity<ProblemDetail> handleRiccon(RicconException exception, HttpServletRequest request) {
        String detail = publicDetail(exception);
        if (exception.status().is5xxServerError()) {
            LOG.error(
                    "request failed code={} traceId={} path={}",
                    exception.code(),
                    RequestContext.traceId(request),
                    request.getRequestURI(),
                    exception);
        } else {
            LOG.info(
                    "request rejected code={} traceId={} path={}",
                    exception.code(),
                    RequestContext.traceId(request),
                    request.getRequestURI());
        }
        ProblemDetail problem = ApiProblems.create(
                exception.status(), exception.code(), exception.title(), detail, RequestContext.traceId(request));
        return ResponseEntity.status(exception.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /**
     * 映射 JSON 解析失败。
     *
     * @param exception 解析异常
     * @param request HTTP 请求
     * @return 400 响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        LOG.info(
                "request body unreadable traceId={} path={}", RequestContext.traceId(request), request.getRequestURI());
        return problem(
                HttpStatus.BAD_REQUEST,
                ErrorCodes.REQUEST_INVALID,
                "Invalid request",
                "request body is invalid",
                request);
    }

    /**
     * 映射参数类型错误。
     *
     * @param exception 参数类型异常
     * @param request HTTP 请求
     * @return 400 响应
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ProblemDetail> handleBadParameter(Exception exception, HttpServletRequest request) {
        LOG.info(
                "request parameter invalid traceId={} path={}",
                RequestContext.traceId(request),
                request.getRequestURI());
        return problem(
                HttpStatus.BAD_REQUEST,
                ErrorCodes.REQUEST_INVALID,
                "Invalid request",
                "request parameter is invalid",
                request);
    }

    /**
     * 映射请求体校验失败。
     *
     * @param exception 校验异常
     * @param request HTTP 请求
     * @return 400 响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        LOG.info(
                "request validation failed traceId={} path={}",
                RequestContext.traceId(request),
                request.getRequestURI());
        return problem(
                HttpStatus.BAD_REQUEST,
                ErrorCodes.REQUEST_INVALID,
                "Invalid request",
                "request validation failed",
                request);
    }

    /**
     * 映射不支持的媒体类型。
     *
     * @param exception 媒体类型异常
     * @param request HTTP 请求
     * @return 415 响应
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaType(
            HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                ErrorCodes.REQUEST_UNSUPPORTED_MEDIA_TYPE,
                "Unsupported media type",
                "content type is not supported",
                request);
    }

    /**
     * 映射上传超过 200 MB 的容器级异常。
     *
     * @param exception 大小超限异常
     * @param request HTTP 请求
     * @return 413 响应
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleUploadTooLarge(
            MaxUploadSizeExceededException exception, HttpServletRequest request) {
        LOG.info("upload exceeds limit traceId={} path={}", RequestContext.traceId(request), request.getRequestURI());
        return problem(
                HttpStatus.PAYLOAD_TOO_LARGE,
                ErrorCodes.FILE_TOO_LARGE,
                "Payload too large",
                "file must not exceed 200 MB",
                request);
    }

    /**
     * 映射缺少 multipart 文件 part 的请求。
     *
     * @param exception 缺少 part 异常
     * @param request HTTP 请求
     * @return 400 响应
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ProblemDetail> handleMissingPart(
            MissingServletRequestPartException exception, HttpServletRequest request) {
        LOG.info("upload missing part traceId={} path={}", RequestContext.traceId(request), request.getRequestURI());
        return problem(HttpStatus.BAD_REQUEST, ErrorCodes.FILE_INVALID, "Invalid file", "file is required", request);
    }

    /**
     * 映射无法解析的 multipart 请求。
     *
     * @param exception multipart 异常
     * @param request HTTP 请求
     * @return 400 响应
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ProblemDetail> handleMultipart(MultipartException exception, HttpServletRequest request) {
        LOG.info(
                "multipart request invalid traceId={} path={}",
                RequestContext.traceId(request),
                request.getRequestURI());
        return problem(
                HttpStatus.BAD_REQUEST,
                ErrorCodes.FILE_INVALID,
                "Invalid file",
                "multipart request is invalid",
                request);
    }

    /**
     * 映射 MVC 之外的权限拒绝。
     *
     * @param exception 权限异常
     * @param request HTTP 请求
     * @return 403 响应
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(
            AccessDeniedException exception, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, ErrorCodes.AUTH_FORBIDDEN, "Forbidden", "access denied", request);
    }

    /**
     * 映射未知路由。
     *
     * @param exception 路由异常
     * @param request HTTP 请求
     * @return 404 响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResource(
            NoResourceFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, ErrorCodes.REQUEST_NOT_FOUND, "Not found", "resource not found", request);
    }

    /**
     * 兜底异常：记录堆栈，对外只返回通用错误。
     *
     * @param exception 未预期异常
     * @param request HTTP 请求
     * @return 500 响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOG.error(
                "unexpected error traceId={} path={}",
                RequestContext.traceId(request),
                request.getRequestURI(),
                exception);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCodes.INTERNAL,
                "Internal server error",
                "An unexpected error occurred.",
                request);
    }

    /**
     * 为业务异常选择对外说明，避免泄露登录失败的具体原因。
     *
     * @param exception 业务异常
     * @return 脱敏说明
     */
    private String publicDetail(RicconException exception) {
        return switch (exception.code()) {
            case ErrorCodes.AUTH_INVALID_CREDENTIALS -> "Invalid tenant code, username or password.";
            case ErrorCodes.AUTH_UNAUTHENTICATED -> "Authentication is required.";
            case ErrorCodes.AUTH_FORBIDDEN -> "You do not have permission to perform this action.";
            case ErrorCodes.TENANT_NOT_FOUND -> "Resource not found.";
            case ErrorCodes.RATE_LIMITED -> "Too many login attempts. Try again later.";
            case ErrorCodes.INTERNAL -> "An unexpected error occurred.";
            default -> exception.getMessage();
        };
    }

    /**
     * 构造 ProblemDetail 响应。
     *
     * @param status HTTP 状态
     * @param code 错误码
     * @param title 标题
     * @param detail 脱敏说明
     * @param request HTTP 请求
     * @return 响应
     */
    private ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String code, String title, String detail, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiProblems.create(status, code, title, detail, RequestContext.traceId(request)));
    }
}
