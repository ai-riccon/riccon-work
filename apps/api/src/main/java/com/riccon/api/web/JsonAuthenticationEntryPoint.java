package com.riccon.api.web;

import com.riccon.api.error.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 未认证请求的 401 响应。
 *
 * <p>默认入口点对匿名请求返回 403，会让前端无法区分“未登录”和“无权限”；这里统一返回 RFC 9457
 * {@code RICCON-AUTH-401}，不泄露受保护路径是否存在。
 */
@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /** 错误响应写入器。 */
    private final ProblemWriter problemWriter;

    /**
     * 创建 401 入口点。
     *
     * @param problemWriter 错误响应写入器
     */
    public JsonAuthenticationEntryPoint(ProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    /**
     * 写出 401 响应。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param authException 认证异常
     * @throws IOException 写入失败
     */
    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        problemWriter.write(
                response,
                HttpStatus.UNAUTHORIZED,
                ErrorCodes.AUTH_UNAUTHENTICATED,
                "Authentication required",
                "Authentication is required to access this resource.",
                RequestContext.traceId(request));
    }
}
