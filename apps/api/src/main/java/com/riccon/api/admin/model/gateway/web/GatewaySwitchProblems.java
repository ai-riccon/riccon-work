package com.riccon.api.admin.model.gateway.web;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.web.ApiProblems;
import com.riccon.api.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * 网关开关写接口的错误响应。
 *
 * <p>契约 {@code ProblemDetail} 的 {@code additionalProperties: false} 不允许附加 {@code currentVersion}
 * 等未声明字段，因此当前版本放在 {@code detail} 文本中，并保留 {@code field}/{@code reason} 供管理端稳定识别。
 * 环境覆盖返回 {@code field=envOverride}/{@code reason=ENV_OVERRIDE_ACTIVE}（ADR-0021 §3.4/§7.3）。
 */
public final class GatewaySwitchProblems {

    /** 工具类禁止实例化。 */
    private GatewaySwitchProblems() {}

    /**
     * 构造字段级 400 响应。
     *
     * @param field 出错字段
     * @param reason 稳定原因码
     * @param detail 脱敏说明
     * @param request HTTP 请求，用于 traceId
     * @return 400 ProblemDetail
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
     * 构造版本冲突 409 响应。
     *
     * @param currentVersion 数据库中的当前版本
     * @param request HTTP 请求，用于 traceId
     * @return 409 ProblemDetail
     */
    public static ResponseEntity<Object> versionConflict(int currentVersion, HttpServletRequest request) {
        ProblemDetail problem = ApiProblems.create(
                HttpStatus.CONFLICT,
                ErrorCodes.MODEL_CONFLICT,
                "Conflict",
                "model gateway switch version conflict; current version is " + currentVersion,
                RequestContext.traceId(request));
        problem.setProperty("field", "version");
        problem.setProperty("reason", "VERSION_CONFLICT");
        return conflict(problem);
    }

    /**
     * 构造环境变量覆盖 409 响应。
     *
     * <p>不返回被覆盖开关的具体部署值：值本身不是密钥，但管理端只需要知道“不可写”，
     * 具体生效值由 GET 返回（ADR-0021 §3.4）。
     *
     * @param request HTTP 请求，用于 traceId
     * @return 409 ProblemDetail
     */
    public static ResponseEntity<Object> envOverride(HttpServletRequest request) {
        ProblemDetail problem = ApiProblems.create(
                HttpStatus.CONFLICT,
                ErrorCodes.MODEL_CONFLICT,
                "Conflict",
                "model gateway switches are overridden by environment variables",
                RequestContext.traceId(request));
        problem.setProperty("field", "envOverride");
        problem.setProperty("reason", "ENV_OVERRIDE_ACTIVE");
        return conflict(problem);
    }

    /**
     * 统一 409 响应头与内容类型。
     *
     * @param problem 问题详情
     * @return 409 响应
     */
    private static ResponseEntity<Object> conflict(ProblemDetail problem) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
