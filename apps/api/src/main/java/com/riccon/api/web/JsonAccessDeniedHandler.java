package com.riccon.api.web;

import com.riccon.api.error.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 已认证但被拒绝的 403 响应。
 *
 * <p>同时覆盖 RBAC 拒绝与 CSRF/同源校验失败；响应体不区分具体原因，避免向攻击者提供探测信息。
 */
@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    /** 错误响应写入器。 */
    private final ProblemWriter problemWriter;

    /**
     * 创建 403 处理器。
     *
     * @param problemWriter 错误响应写入器
     */
    public JsonAccessDeniedHandler(ProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    /**
     * 写出 403 响应。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param accessDeniedException 权限异常
     * @throws IOException 写入失败
     */
    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        problemWriter.write(
                response,
                HttpStatus.FORBIDDEN,
                ErrorCodes.AUTH_FORBIDDEN,
                "Forbidden",
                "You do not have permission to perform this action.",
                RequestContext.traceId(request));
    }
}
