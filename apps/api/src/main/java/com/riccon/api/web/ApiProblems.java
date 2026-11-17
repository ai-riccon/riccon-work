package com.riccon.api.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * 构造对外 ProblemDetail。
 *
 * <p>OpenAPI 的 {@code ProblemDetail} 要求至少包含 {@code type/title/status/code/traceId}，且禁止未知字段；
 * 这里只写入合同声明过的属性。
 */
public final class ApiProblems {

    /** 工具类禁止实例化。 */
    private ApiProblems() {}

    /**
     * 创建 ProblemDetail。
     *
     * @param status HTTP 状态
     * @param code 稳定错误码
     * @param title 标题
     * @param detail 脱敏说明
     * @param traceId 追踪 ID
     * @return ProblemDetail
     */
    public static ProblemDetail create(HttpStatus status, String code, String title, String detail, String traceId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        problem.setProperty("traceId", traceId);
        return problem;
    }
}
