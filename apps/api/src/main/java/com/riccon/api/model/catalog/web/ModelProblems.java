package com.riccon.api.model.catalog.web;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.web.ApiProblems;
import com.riccon.api.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * 模型端点写接口的字段级错误响应。
 *
 * <p>契约 {@code ProblemDetail} 允许可选的 {@code field}/{@code reason}：{@code field} 指向具体字段，
 * {@code reason} 使用稳定原因码（{@code ENDPOINT_*} 或 {@code INVALID_*}）。控制器直接返回 ProblemDetail，
 * 不改动全局异常映射，避免字段错误被降级为通用 400（ADR-0021 §2.4/§7.3）。
 */
public final class ModelProblems {

    /** 工具类禁止实例化。 */
    private ModelProblems() {}

    /**
     * 构造字段级 400 响应。
     *
     * @param field 出错字段
     * @param reason 稳定原因码
     * @param detail 脱敏说明
     * @param request HTTP 请求，用于 traceId
     * @return ProblemDetail 响应
     */
    public static ResponseEntity<Object> invalidField(
            String field, String reason, String detail, HttpServletRequest request) {
        ProblemDetail problem = ApiProblems.create(
                HttpStatus.BAD_REQUEST,
                ErrorCodes.MODEL_INVALID_REQUEST,
                "Invalid request",
                detail,
                RequestContext.traceId(request));
        problem.setProperty("field", field);
        problem.setProperty("reason", reason);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /**
     * 返回只读部署字段错误。
     *
     * @param field 字段名
     * @param request HTTP 请求
     * @return 400 响应
     */
    public static ResponseEntity<Object> immutableDeploymentField(String field, HttpServletRequest request) {
        return invalidField(
                field,
                "ENDPOINT_IMMUTABLE_DEPLOYMENT_MANAGED",
                field + " is managed by deployment configuration and cannot be changed",
                request);
    }
}
