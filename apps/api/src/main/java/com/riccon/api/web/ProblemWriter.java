package com.riccon.api.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 在 MVC 之外（安全过滤器、租户过滤器）写 RFC 9457 错误响应。
 *
 * <p>响应只暴露 {@code type/title/status/detail/code/traceId}，不包含堆栈、SQL、内部主机或密钥。
 */
@Component
public class ProblemWriter {

    /** JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建错误响应写入器。
     *
     * @param objectMapper JSON 序列化器
     */
    public ProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 写出错误响应。
     *
     * @param response HTTP 响应
     * @param status HTTP 状态
     * @param code 稳定错误码
     * @param title 标题
     * @param detail 脱敏说明
     * @param traceId 追踪 ID
     * @throws IOException 写入失败
     */
    public void write(
            HttpServletResponse response, HttpStatus status, String code, String title, String detail, String traceId)
            throws IOException {
        ProblemDetail problem = ApiProblems.create(status, code, title, detail, traceId);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
