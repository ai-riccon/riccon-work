package com.riccon.api.chat.service;

import com.riccon.api.chat.domain.ChatSubmission;
import com.riccon.api.config.WorkerClientProperties;
import com.riccon.platform.domain.contract.HttpHeaders;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * API → Worker 内部对话流客户端。
 *
 * <p>使用 HMAC 服务令牌调用 {@code POST /internal/v1/chat/streams} 与
 * {@code POST /internal/v1/runs/{runId}/cancel}。打开流时使用 {@code sendAsync + 首字超时}：Worker 在首字前
 * 失败会直接返回 ProblemDetail，API 据此返回 ProblemDetail 而不是半截 SSE。非 200 的响应体只读取有限字节用于
 * 服务端日志，不向外返回 Worker 内部细节。
 */
@Component
public class WorkerChatClient {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(WorkerChatClient.class);

    /** 非 200 响应体最多读取的字节数。 */
    private static final int ERROR_BODY_LIMIT = 8192;

    /** HTTP 客户端。 */
    private final HttpClient httpClient;

    /** 客户端配置。 */
    private final WorkerClientProperties properties;

    /** 服务令牌签发。 */
    private final ServiceTokenSigner tokenSigner;

    /** JSON 序列化。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建客户端。
     *
     * @param httpClient HTTP 客户端
     * @param properties 客户端配置
     * @param tokenSigner 服务令牌签发
     * @param objectMapper JSON 序列化
     */
    public WorkerChatClient(
            HttpClient httpClient,
            WorkerClientProperties properties,
            ServiceTokenSigner tokenSigner,
            ObjectMapper objectMapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.tokenSigner = Objects.requireNonNull(tokenSigner, "tokenSigner must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 打开 Worker 对话流。
     *
     * @param submission 已持久化的对话提交
     * @return 已收到响应头的流
     * @throws WorkerCallException Worker 返回非 200
     * @throws WorkerUnavailableException 连接失败
     * @throws WorkerTimeoutException 等待首字超过空闲上限
     */
    public WorkerStream open(ChatSubmission submission) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", submission.runId().toString());
        body.put("tenantId", submission.tenantId().value());
        body.put("userId", submission.userId().toString());
        body.put("conversationId", submission.conversationId().toString());
        body.put("userMessageId", submission.userMessageId().toString());
        if (!submission.fileIds().isEmpty()) {
            body.put(
                    "fileIds", submission.fileIds().stream().map(UUID::toString).toList());
        }
        HttpRequest request = HttpRequest.newBuilder(uri("/internal/v1/chat/streams"))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .header(
                        HttpHeaders.SERVICE_TOKEN,
                        tokenSigner.sign(submission.tenantId(), submission.userId(), submission.runId()))
                .header(HttpHeaders.TRACE_ID, submission.traceId())
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body)))
                .build();
        CompletableFuture<HttpResponse<InputStream>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response;
        try {
            response = future.get(properties.idleTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new WorkerTimeoutException("worker did not produce the first event in time");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new WorkerUnavailableException("interrupted while opening worker stream", exception);
        } catch (ExecutionException exception) {
            throw new WorkerUnavailableException("failed to open worker stream", exception.getCause());
        }
        if (response.statusCode() == 200) {
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.toLowerCase(java.util.Locale.ROOT).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)) {
                closeQuietly(response.body());
                throw new WorkerCallException(502, "RICCON-CHAT-502", "worker returned non-SSE content");
            }
            return new WorkerStream(response.body());
        }
        WorkerError error = readError(response.body());
        throw new WorkerCallException(response.statusCode(), error.code(), error.detail());
    }

    /**
     * 请求 Worker 取消运行。
     *
     * @param runId 运行 ID
     * @param tenantId 租户
     * @param userId 发起人
     * @param reason 契约原因
     * @param traceId 追踪 ID
     * @return 取消结果
     * @throws WorkerCallException Worker 返回未预期状态或错误
     * @throws WorkerUnavailableException 连接失败
     */
    public CancelOutcome cancel(
            UUID runId,
            com.riccon.platform.common.tenant.TenantId tenantId,
            UUID userId,
            String reason,
            String traceId) {
        Map<String, Object> body = Map.of("reason", reason);
        HttpRequest request = HttpRequest.newBuilder(uri("/internal/v1/runs/" + runId + "/cancel"))
                .timeout(properties.cancelTimeout())
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.SERVICE_TOKEN, tokenSigner.sign(tenantId, userId, runId))
                .header(HttpHeaders.TRACE_ID, traceId)
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WorkerUnavailableException("interrupted while cancelling worker run", exception);
        } catch (IOException exception) {
            throw new WorkerUnavailableException("failed to cancel worker run", exception);
        }
        return switch (response.statusCode()) {
            case 202 -> CancelOutcome.ACCEPTED;
            case 409 -> CancelOutcome.CONFLICT;
            case 404 -> CancelOutcome.NOT_FOUND;
            default ->
                throw new WorkerCallException(response.statusCode(), "RICCON-CHAT-500", "unexpected cancel response");
        };
    }

    /**
     * 读取并限制 Worker 错误响应体。
     *
     * @param body 响应体
     * @return 错误码与内部说明
     */
    private WorkerError readError(InputStream body) {
        String code = "RICCON-CHAT-500";
        String detail = "worker request failed";
        try (InputStream input = body) {
            byte[] bytes = input.readNBytes(ERROR_BODY_LIMIT);
            if (bytes.length == 0) {
                return new WorkerError(code, detail);
            }
            JsonNode root = objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            JsonNode codeNode = root.get("code");
            if (codeNode != null && codeNode.isTextual() && !codeNode.asText().isBlank()) {
                code = codeNode.asText();
            }
            JsonNode detailNode = root.get("detail");
            if (detailNode != null
                    && detailNode.isTextual()
                    && !detailNode.asText().isBlank()) {
                detail = detailNode.asText();
            }
        } catch (IOException | JacksonException exception) {
            LOG.warn("worker.error_body_unreadable", exception);
        }
        return new WorkerError(code, detail);
    }

    /**
     * 序列化请求体。
     *
     * @param body 字段
     * @return JSON
     */
    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException exception) {
            throw new IllegalStateException("failed to serialize worker request", exception);
        }
    }

    /**
     * 构造内部端点 URI。
     *
     * @param path 路径
     * @return URI
     */
    private URI uri(String path) {
        String base = properties.baseUrl();
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(normalized + path);
    }

    /**
     * 静默关闭流。
     *
     * @param input 输入流
     */
    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException exception) {
            LOG.debug("worker.stream_close_failed", exception);
        }
    }

    /**
     * Worker 返回的错误摘要；{@code detail} 只进日志。
     *
     * @param code 错误码
     * @param detail 内部说明
     */
    private record WorkerError(String code, String detail) {}

    /**
     * 取消结果。
     */
    public enum CancelOutcome {
        /** 已请求取消或已取消。 */
        ACCEPTED,
        /** 运行已成功/失败。 */
        CONFLICT,
        /** 运行不存在或不属于令牌租户。 */
        NOT_FOUND
    }

    /**
     * Worker 返回非 200。
     */
    public static final class WorkerCallException extends RuntimeException {

        /** HTTP 状态。 */
        private final int status;

        /** Worker 错误码。 */
        private final String code;

        /**
         * 创建异常。
         *
         * @param status HTTP 状态
         * @param code 错误码
         * @param detail 内部说明
         */
        public WorkerCallException(int status, String code, String detail) {
            super(detail);
            this.status = status;
            this.code = code;
        }

        /** 返回 HTTP 状态。 */
        public int status() {
            return status;
        }

        /** 返回 Worker 错误码。 */
        public String code() {
            return code;
        }
    }

    /**
     * Worker 连接不可用。
     */
    public static final class WorkerUnavailableException extends RuntimeException {

        /**
         * 创建异常。
         *
         * @param message 内部说明
         * @param cause 原因
         */
        public WorkerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 等待 Worker 首字超时。
     */
    public static final class WorkerTimeoutException extends RuntimeException {

        /**
         * 创建异常。
         *
         * @param message 内部说明
         */
        public WorkerTimeoutException(String message) {
            super(message);
        }
    }

    /**
     * Worker 已打开的事件流。
     */
    public static final class WorkerStream implements Closeable {

        /** SSE 读取器。 */
        private final SseFrameReader reader;

        /**
         * 创建流。
         *
         * @param body Worker 响应体
         */
        private WorkerStream(InputStream body) {
            this.reader = new SseFrameReader(body);
        }

        /**
         * 返回读取器。
         *
         * @return 读取器
         */
        public SseFrameReader reader() {
            return reader;
        }

        /**
         * 关闭底层连接。
         *
         * @throws IOException 关闭失败
         */
        @Override
        public void close() throws IOException {
            reader.close();
        }
    }
}
