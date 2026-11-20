package com.riccon.api.admin.model.routing.web;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.web.ApiProblems;
import com.riccon.api.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * 模型路由写接口的冲突响应。
 *
 * <p>契约 {@code ProblemDetail} 的 {@code additionalProperties: false} 不允许额外返回 {@code currentVersion}
 * 属性，因此当前版本放在 {@code detail} 文本中，并保留 {@code field=version}/{@code reason=VERSION_CONFLICT}
 * 供管理端稳定识别；管理端刷新时重新 GET 即可拿到新版本（ADR-0021 §4.3/§7.3）。
 */
public final class RoutingProblems {

    /** 工具类禁止实例化。 */
    private RoutingProblems() {}

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
                "routing version conflict; current version is " + currentVersion,
                RequestContext.traceId(request));
        problem.setProperty("field", "version");
        problem.setProperty("reason", "VERSION_CONFLICT");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
