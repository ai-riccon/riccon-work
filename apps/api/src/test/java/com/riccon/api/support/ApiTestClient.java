package com.riccon.api.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 集成测试用 HTTP 客户端。
 *
 * <p>使用 JDK {@link HttpClient} 访问随机端口上的真实 Tomcat，显式管理会话 Cookie 与 CSRF 头；
 * 不跟随重定向，避免把安全测试变成浏览器行为测试。
 */
public final class ApiTestClient {

    /** JSON 序列化器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 进程内共享的 HTTP 客户端。
     *
     * <p>JDK {@link HttpClient} 本身不保存 Cookie 或认证状态（Cookie/CSRF 都由每次请求显式传入），
     * 因此可安全跨测试复用连接池，省去每个测试方法新建客户端与 TCP 连接的开销。
     */
    private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** HTTP 客户端。 */
    private final HttpClient httpClient = SHARED_HTTP_CLIENT;

    /** 服务地址，例如 {@code http://127.0.0.1:12345}。 */
    private final String baseUrl;

    /**
     * 创建测试客户端。
     *
     * @param port 服务端口
     */
    public ApiTestClient(int port) {
        this.baseUrl = "http://127.0.0.1:" + port;
    }

    /**
     * GET 请求。
     *
     * @param path 路径
     * @param sessionCookie 会话 Cookie，可为空
     * @param extraHeaders 额外请求头
     * @return 响应
     */
    public ApiResponse get(String path, String sessionCookie, Map<String, String> extraHeaders) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        applyHeaders(builder, sessionCookie, extraHeaders);
        return send(builder.build());
    }

    /**
     * GET 请求（无额外头）。
     *
     * @param path 路径
     * @return 响应
     */
    public ApiResponse get(String path) {
        return get(path, null, Map.of());
    }

    /**
     * POST JSON 请求。
     *
     * @param path 路径
     * @param jsonBody JSON 体，可为空
     * @param sessionCookie 会话 Cookie，可为空
     * @param csrfToken CSRF 令牌，可为空
     * @param extraHeaders 额外请求头
     * @return 响应
     */
    public ApiResponse post(
            String path, String jsonBody, String sessionCookie, String csrfToken, Map<String, String> extraHeaders) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
        applyHeaders(builder, sessionCookie, csrfToken, extraHeaders);
        return send(builder.build());
    }

    /**
     * PATCH JSON 请求。
     *
     * @param path 路径
     * @param jsonBody JSON 体
     * @param sessionCookie 会话 Cookie
     * @param csrfToken CSRF 令牌
     * @return 响应
     */
    public ApiResponse patch(String path, String jsonBody, String sessionCookie, String csrfToken) {
        return sendWithBody("PATCH", path, jsonBody, sessionCookie, csrfToken, Map.of());
    }

    /**
     * POST JSON 并返回未读取的响应流，用于测试 SSE 增量消费。
     *
     * @param path 路径
     * @param jsonBody JSON 体
     * @param sessionCookie 会话 Cookie
     * @param csrfToken CSRF 令牌
     * @param extraHeaders 额外请求头
     * @return 状态、响应头与响应体流；调用方负责关闭
     */
    public StreamResponse postStream(
            String path, String jsonBody, String sessionCookie, String csrfToken, Map<String, String> extraHeaders) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
        applyHeaders(builder, sessionCookie, csrfToken, extraHeaders);
        try {
            HttpResponse<InputStream> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            return new StreamResponse(response.statusCode(), response.headers(), response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("streaming HTTP request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("streaming HTTP request interrupted", exception);
        }
    }

    /**
     * DELETE 请求。
     *
     * @param path 路径
     * @param sessionCookie 会话 Cookie
     * @param csrfToken CSRF 令牌
     * @return 响应
     */
    public ApiResponse delete(String path, String sessionCookie, String csrfToken) {
        return sendWithBody("DELETE", path, null, sessionCookie, csrfToken, Map.of());
    }

    /**
     * POST multipart/form-data 请求。
     *
     * @param path 路径
     * @param fields 普通表单字段
     * @param filePartName 文件 part 名
     * @param filename 文件名
     * @param contentType 文件 Content-Type
     * @param content 文件内容
     * @param sessionCookie 会话 Cookie
     * @param csrfToken CSRF 令牌
     * @param extraHeaders 额外请求头
     * @return 响应
     */
    public ApiResponse postMultipart(
            String path,
            Map<String, String> fields,
            String filePartName,
            String filename,
            String contentType,
            byte[] content,
            String sessionCookie,
            String csrfToken,
            Map<String, String> extraHeaders) {
        String boundary = "----riccon" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        fields.forEach((name, value) -> {
            writeAscii(body, "--" + boundary + "\r\n");
            writeAscii(body, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
            writeAscii(body, value);
            writeAscii(body, "\r\n");
        });
        writeAscii(body, "--" + boundary + "\r\n");
        writeAscii(
                body,
                "Content-Disposition: form-data; name=\""
                        + filePartName
                        + "\"; filename=\""
                        + filename.replace("\"", "")
                        + "\"\r\n");
        writeAscii(body, "Content-Type: " + contentType + "\r\n\r\n");
        body.writeBytes(content);
        writeAscii(body, "\r\n--" + boundary + "--\r\n");

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        applyHeaders(builder, sessionCookie, csrfToken, extraHeaders);
        return send(builder.build());
    }

    /**
     * 向 multipart 请求体写入 ASCII 边界与字段。
     *
     * @param output 输出流
     * @param text 文本
     */
    private static void writeAscii(ByteArrayOutputStream output, String text) {
        output.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * PUT JSON 请求。
     *
     * @param path 路径
     * @param jsonBody JSON 体
     * @param sessionCookie 会话 Cookie
     * @param csrfToken CSRF 令牌
     * @return 响应
     */
    public ApiResponse put(String path, String jsonBody, String sessionCookie, String csrfToken) {
        return sendWithBody("PUT", path, jsonBody, sessionCookie, csrfToken, Map.of());
    }

    /**
     * 解析 JSON 响应体。
     *
     * @param response 响应
     * @return JSON 树
     */
    public JsonNode json(ApiResponse response) {
        return objectMapper.readTree(response.body());
    }

    /**
     * 从响应读取会话 Cookie。
     *
     * @param response 响应
     * @return 形如 {@code RICCON_SESSION=...} 的 Cookie；不存在时为空
     */
    public Optional<String> sessionCookie(ApiResponse response) {
        return response.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith("RICCON_SESSION="))
                .map(value -> value.substring(0, value.indexOf(';')))
                .findFirst();
    }

    /**
     * 从 CSRF 响应读取令牌。
     *
     * @param response CSRF 响应
     * @return 令牌
     */
    public String csrfToken(ApiResponse response) {
        return json(response).path("token").asText();
    }

    /**
     * 发送带请求体的非 GET 请求。
     *
     * @param method HTTP 方法
     * @param path 路径
     * @param jsonBody JSON 体
     * @param sessionCookie 会话 Cookie
     * @param csrfToken CSRF 令牌
     * @param extraHeaders 额外请求头
     * @return 响应
     */
    private ApiResponse sendWithBody(
            String method,
            String path,
            String jsonBody,
            String sessionCookie,
            String csrfToken,
            Map<String, String> extraHeaders) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
        applyHeaders(builder, sessionCookie, csrfToken, extraHeaders);
        return send(builder.build());
    }

    /**
     * 应用 Cookie、CSRF 与额外请求头。
     *
     * @param builder 请求构造器
     * @param sessionCookie 会话 Cookie
     * @param extraHeaders 额外请求头
     */
    private void applyHeaders(HttpRequest.Builder builder, String sessionCookie, Map<String, String> extraHeaders) {
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        extraHeaders.forEach(builder::header);
    }

    /**
     * 应用 Cookie、CSRF 与额外请求头。
     *
     * @param builder 请求构造器
     * @param sessionCookie 会话 Cookie
     * @param csrfToken CSRF 令牌
     * @param extraHeaders 额外请求头
     */
    private void applyHeaders(
            HttpRequest.Builder builder, String sessionCookie, String csrfToken, Map<String, String> extraHeaders) {
        applyHeaders(builder, sessionCookie, extraHeaders);
        if (csrfToken != null) {
            builder.header("X-CSRF-TOKEN", csrfToken);
        }
    }

    /**
     * 执行请求。
     *
     * @param request 请求
     * @return 响应
     */
    private ApiResponse send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new ApiResponse(response.statusCode(), response.headers(), response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("HTTP request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP request interrupted", exception);
        }
    }

    /**
     * 构造简单 JSON 对象。
     *
     * @param pairs 键值对
     * @return JSON 字符串
     */
    public String jsonBody(Object... pairs) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            body.put((String) pairs[index], pairs[index + 1]);
        }
        return objectMapper.writeValueAsString(body);
    }

    /**
     * HTTP 响应。
     *
     * @param status 状态码
     * @param headers 响应头
     * @param body 响应体
     */
    public record ApiResponse(int status, java.net.http.HttpHeaders headers, String body) {

        /**
         * 读取响应头首个值。
         *
         * @param name 头名
         * @return 值或 {@code null}
         */
        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    /**
     * 未读取的流式响应。
     *
     * @param status 状态码
     * @param headers 响应头
     * @param body 响应体流；调用方负责关闭
     */
    public record StreamResponse(int status, java.net.http.HttpHeaders headers, InputStream body) {

        /**
         * 读取响应头首个值。
         *
         * @param name 头名
         * @return 值或 {@code null}
         */
        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }
}
