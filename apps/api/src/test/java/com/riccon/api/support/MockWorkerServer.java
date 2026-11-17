package com.riccon.api.support;

import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.contract.HttpHeaders;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * API → Worker 内部 SSE 桩。
 *
 * <p>只监听 127.0.0.1 随机端口，校验 HMAC 服务令牌、trace 与运行绑定，按场景返回确定性 SSE。
 * 不访问外网、不 sleep：停止与超时通过 {@link CountDownLatch} 协调，测试以事件/锁等待而不是轮询。
 */
public final class MockWorkerServer implements AutoCloseable {

    /** JSON 解析。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 桩使用的固定模型 ID。 */
    public static final UUID MODEL_ID = UUID.fromString("01930000-0000-7000-8000-0000000000aa");

    /** 场景。 */
    public enum Scenario {
        /** started + citation + 两段 delta + completed。 */
        SUCCESS,
        /** started + delta + failed。 */
        MID_FAILURE,
        /** 首字前返回 500 ProblemDetail。 */
        PRE_FIRST_FAILURE,
        /** 首字前返回 409 ProblemDetail。 */
        PRE_FIRST_CONFLICT,
        /** started + delta 后等待取消，然后发送 stopped。 */
        STOP,
        /** started + delta 后保持连接，用于 API 空闲超时。 */
        HANG,
        /** 取消端点返回 409。 */
        CANCEL_CONFLICT,
        /** 取消端点返回 404。 */
        CANCEL_NOT_FOUND
    }

    /** HTTP 服务。 */
    private final HttpServer server;

    /** 执行器。 */
    private final ExecutorService executor;

    /** 共享 HMAC 密钥。 */
    private final String secret;

    /** 当前场景。 */
    private volatile Scenario scenario = Scenario.SUCCESS;

    /** 对话请求回调，用于测试插入 model_invocation 等前置数据。 */
    private volatile Consumer<JsonNode> chatHook = request -> {};

    /** 最近一次对话请求。 */
    private final AtomicReference<JsonNode> lastChatRequest = new AtomicReference<>();

    /** 最近一次取消请求。 */
    private final AtomicReference<JsonNode> lastCancelRequest = new AtomicReference<>();

    /** 最近一次通过校验的令牌声明。 */
    private final AtomicReference<JsonNode> lastTokenClaims = new AtomicReference<>();

    /** 对话请求计数。 */
    private final AtomicInteger chatRequests = new AtomicInteger();

    /** 取消请求计数。 */
    private final AtomicInteger cancelRequests = new AtomicInteger();

    /** 停止场景的取消信号。 */
    private volatile CountDownLatch cancelLatch = new CountDownLatch(1);

    /** 挂起场景的释放信号，测试结束或看门狗生效后释放。 */
    private volatile CountDownLatch hangRelease = new CountDownLatch(1);

    /**
     * 启动桩。
     *
     * @param secret API 与 Worker 共享的 HMAC 密钥
     */
    public MockWorkerServer(String secret) {
        this.secret = secret;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/", this::handle);
            executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "mock-worker");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.start();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to start mock worker", exception);
        }
    }

    /**
     * 返回内部地址。
     *
     * @return 形如 {@code http://127.0.0.1:port}
     */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * 重置场景与请求记录。
     *
     * @param nextScenario 新场景
     */
    public void reset(Scenario nextScenario) {
        this.scenario = nextScenario;
        this.cancelLatch = new CountDownLatch(1);
        this.hangRelease = new CountDownLatch(1);
        lastChatRequest.set(null);
        lastCancelRequest.set(null);
        lastTokenClaims.set(null);
        chatRequests.set(0);
        cancelRequests.set(0);
    }

    /**
     * 设置对话请求回调。
     *
     * @param hook 回调
     */
    public void setChatHook(Consumer<JsonNode> hook) {
        this.chatHook = hook == null ? request -> {} : hook;
    }

    /**
     * 释放挂起的流。
     */
    public void releaseHang() {
        hangRelease.countDown();
    }

    /** 返回最近一次对话请求。 */
    public JsonNode lastChatRequest() {
        return lastChatRequest.get();
    }

    /** 返回最近一次取消请求。 */
    public JsonNode lastCancelRequest() {
        return lastCancelRequest.get();
    }

    /** 返回最近一次令牌声明。 */
    public JsonNode lastTokenClaims() {
        return lastTokenClaims.get();
    }

    /** 返回对话请求次数。 */
    public int chatRequestCount() {
        return chatRequests.get();
    }

    /** 返回取消请求次数。 */
    public int cancelRequestCount() {
        return cancelRequests.get();
    }

    /**
     * 分发请求。
     *
     * @param exchange HTTP 交换
     */
    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            JsonNode body = readBody(exchange.getRequestBody());
            if ("/internal/v1/chat/streams".equals(path)) {
                handleChat(exchange, body);
            } else if (path.startsWith("/internal/v1/runs/") && path.endsWith("/cancel")) {
                handleCancel(exchange, path, body);
            } else {
                sendProblem(exchange, 404, "RICCON-TENANT-404", "Not Found", "unknown internal path");
            }
        } catch (RuntimeException exception) {
            sendProblemQuietly(exchange, 500, "RICCON-WORKER-500", "Internal Server Error", "mock failure");
        } finally {
            exchange.close();
        }
    }

    /**
     * 处理对话流。
     *
     * @param exchange HTTP 交换
     * @param body 请求体
     */
    private void handleChat(HttpExchange exchange, JsonNode body) {
        String traceId = exchange.getRequestHeaders().getFirst(HttpHeaders.TRACE_ID);
        JsonNode claims = verifyToken(
                exchange.getRequestHeaders().getFirst(HttpHeaders.SERVICE_TOKEN),
                body == null ? null : uuidText(body, "runId"));
        if (claims == null) {
            sendProblem(exchange, 401, "RICCON-AUTH-401", "Unauthorized", "invalid service token");
            return;
        }
        if (traceId == null || traceId.isBlank()) {
            sendProblem(exchange, 400, "RICCON-CHAT-400", "Bad Request", "trace header is required");
            return;
        }
        if (body == null
                || !claims.path("tenantId").asText().equals(text(body, "tenantId"))
                || !claims.path("sub").asText().equals("user:" + text(body, "userId"))) {
            sendProblem(exchange, 400, "RICCON-CHAT-400", "Bad Request", "token binding does not match body");
            return;
        }
        lastTokenClaims.set(claims);
        lastChatRequest.set(body);
        chatRequests.incrementAndGet();
        Scenario current = scenario;
        if (current == Scenario.PRE_FIRST_FAILURE) {
            sendProblem(exchange, 500, "RICCON-MODEL-500", "Internal Server Error", "mock model failure");
            return;
        }
        if (current == Scenario.PRE_FIRST_CONFLICT) {
            sendProblem(exchange, 409, "RICCON-CHAT-409", "Conflict", "run is already terminal");
            return;
        }
        try {
            chatHook.accept(body);
        } catch (RuntimeException exception) {
            sendProblem(exchange, 500, "RICCON-WORKER-500", "Internal Server Error", "mock hook failure");
            return;
        }
        stream(exchange, body, traceId, current);
    }

    /**
     * 处理取消请求。
     *
     * @param exchange HTTP 交换
     * @param path 路径
     * @param body 请求体
     */
    private void handleCancel(HttpExchange exchange, String path, JsonNode body) throws IOException {
        String runId = path.substring("/internal/v1/runs/".length(), path.length() - "/cancel".length());
        JsonNode claims = verifyToken(exchange.getRequestHeaders().getFirst(HttpHeaders.SERVICE_TOKEN), runId);
        if (claims == null) {
            sendProblem(exchange, 401, "RICCON-AUTH-401", "Unauthorized", "invalid service token");
            return;
        }
        lastTokenClaims.set(claims);
        lastCancelRequest.set(body);
        cancelRequests.incrementAndGet();
        switch (scenario) {
            case CANCEL_CONFLICT ->
                sendProblem(exchange, 409, "RICCON-CHAT-409", "Conflict", "run is already terminal");
            case CANCEL_NOT_FOUND -> sendProblem(exchange, 404, "RICCON-TENANT-404", "Not Found", "run not found");
            default -> {
                cancelLatch.countDown();
                exchange.sendResponseHeaders(202, -1);
            }
        }
    }

    /**
     * 写 SSE 场景。
     *
     * @param exchange HTTP 交换
     * @param request 请求体
     * @param traceId trace
     * @param current 场景
     */
    private void stream(HttpExchange exchange, JsonNode request, String traceId, Scenario current) {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        try {
            exchange.sendResponseHeaders(200, 0);
        } catch (IOException exception) {
            return;
        }
        OutputStream output = exchange.getResponseBody();
        String tenantId = text(request, "tenantId");
        String runId = text(request, "runId");
        UUID workerMessageId = UUID.randomUUID();
        try {
            ObjectNode started = MAPPER.createObjectNode();
            started.put("modelId", MODEL_ID.toString());
            started.put("modelKey", "fake-model");
            writeEvent(output, EventTypes.CHAT_STARTED, tenantId, runId, workerMessageId, traceId, started);
            switch (current) {
                case SUCCESS -> {
                    writeCitations(output, tenantId, runId, workerMessageId, traceId);
                    writeDelta(output, tenantId, runId, workerMessageId, traceId, "根据资料，");
                    writeDelta(output, tenantId, runId, workerMessageId, traceId, "答案是 42。");
                    ObjectNode completed = MAPPER.createObjectNode();
                    completed.put("finishReason", "stop");
                    completed.put("promptTokens", 10);
                    completed.put("completionTokens", 20);
                    completed.put("firstTokenMs", 5);
                    writeEvent(output, EventTypes.CHAT_COMPLETED, tenantId, runId, workerMessageId, traceId, completed);
                }
                case MID_FAILURE -> {
                    writeDelta(output, tenantId, runId, workerMessageId, traceId, "部分内容");
                    ObjectNode failed = MAPPER.createObjectNode();
                    failed.put("code", "RICCON-MODEL-500");
                    failed.put("message", "模型调用失败");
                    failed.put("retryable", true);
                    writeEvent(output, EventTypes.CHAT_FAILED, tenantId, runId, workerMessageId, traceId, failed);
                }
                case STOP -> {
                    writeDelta(output, tenantId, runId, workerMessageId, traceId, "部分内容");
                    boolean cancelled = await(cancelLatch);
                    if (cancelled) {
                        ObjectNode stopped = MAPPER.createObjectNode();
                        stopped.put("reason", "USER_CANCELLED");
                        writeEvent(output, EventTypes.CHAT_STOPPED, tenantId, runId, workerMessageId, traceId, stopped);
                    }
                }
                case HANG -> {
                    writeDelta(output, tenantId, runId, workerMessageId, traceId, "部分内容");
                    await(hangRelease);
                }
                default -> {
                    // 首字前失败场景已在 handleChat 返回；不会到这里。
                }
            }
        } catch (IOException exception) {
            // API 看门狗关闭连接或测试结束，属于预期路径。
        } finally {
            try {
                output.close();
            } catch (IOException ignored) {
                // 连接已关闭。
            }
        }
    }

    /**
     * 等待信号。
     *
     * @param latch 信号
     * @return 是否在 10 秒内到达
     */
    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 写引用事件。
     *
     * @param output 输出
     * @param tenantId 租户
     * @param runId 运行
     * @param messageId 消息
     * @param traceId trace
     * @throws IOException 写失败
     */
    private void writeCitations(OutputStream output, String tenantId, String runId, UUID messageId, String traceId)
            throws IOException {
        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode citations = data.putArray("citations");
        ObjectNode citation = citations.addObject();
        citation.put("ordinal", 1);
        citation.put("sourceType", "KNOWLEDGE");
        citation.put("knowledgeDocumentId", UUID.randomUUID().toString());
        citation.put("chunkId", UUID.randomUUID().toString());
        citation.put("locatorType", "PAGE");
        citation.put("pageNo", 1);
        citation.put("title", "制度手册");
        citation.put("snippet", "这是引用片段。");
        citation.put("score", 0.9);
        writeEvent(output, EventTypes.CHAT_CITATION, tenantId, runId, messageId, traceId, data);
    }

    /**
     * 写增量事件。
     *
     * @param output 输出
     * @param tenantId 租户
     * @param runId 运行
     * @param messageId 消息
     * @param traceId trace
     * @param delta 文本
     * @throws IOException 写失败
     */
    private void writeDelta(
            OutputStream output, String tenantId, String runId, UUID messageId, String traceId, String delta)
            throws IOException {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("delta", delta);
        writeEvent(output, EventTypes.CHAT_DELTA, tenantId, runId, messageId, traceId, data);
    }

    /**
     * 写单个 SSE 事件。
     *
     * @param output 输出
     * @param eventType 类型
     * @param tenantId 租户
     * @param runId 运行
     * @param messageId 消息
     * @param traceId trace
     * @param data 数据
     * @throws IOException 写失败
     */
    private void writeEvent(
            OutputStream output,
            String eventType,
            String tenantId,
            String runId,
            UUID messageId,
            String traceId,
            ObjectNode data)
            throws IOException {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", eventType);
        event.put("version", 1);
        event.put("occurredAt", Instant.now().toString());
        event.put("tenantId", tenantId);
        event.put("runId", runId);
        event.put("messageId", messageId.toString());
        event.put("traceId", traceId);
        event.set("data", data);
        String frame = "id: " + event.path("eventId").asText() + "\n"
                + "event: " + eventType + "\n"
                + "data: " + MAPPER.writeValueAsString(event) + "\n\n";
        output.write(frame.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    /**
     * 校验服务令牌。
     *
     * @param token 令牌
     * @param expectedRunId 期望运行
     * @return 声明；无效时返回 {@code null}
     */
    private JsonNode verifyToken(String token, String expectedRunId) {
        if (token == null || token.isBlank() || expectedRunId == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !"riccon1".equals(parts[0])) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            byte[] provided = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expected, provided)) {
                return null;
            }
            JsonNode payload = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (!"riccon-api".equals(payload.path("iss").asText())
                    || !"riccon-worker".equals(payload.path("aud").asText())
                    || !"agent:run".equals(payload.path("scope").asText())) {
                return null;
            }
            if (!expectedRunId.equals(payload.path("runId").asText())) {
                return null;
            }
            long issuedAt = payload.path("iat").asLong();
            long expiresAt = payload.path("exp").asLong();
            if (issuedAt <= 0 || expiresAt <= issuedAt || expiresAt - issuedAt > 300) {
                return null;
            }
            String subject = payload.path("sub").asText();
            if (!subject.startsWith("user:")
                    || payload.path("tenantId").asText().isBlank()) {
                return null;
            }
            return payload;
        } catch (GeneralSecurityException | IllegalArgumentException | JacksonException exception) {
            return null;
        }
    }

    /**
     * 读取有界请求体。
     *
     * @param input 输入
     * @return JSON；空体返回 {@code null}
     */
    private static JsonNode readBody(InputStream input) {
        try (InputStream body = input) {
            byte[] bytes = body.readNBytes(1024 * 1024);
            return bytes.length == 0 ? null : MAPPER.readTree(bytes);
        } catch (IOException exception) {
            return null;
        }
    }

    /**
     * 写 ProblemDetail。
     *
     * @param exchange HTTP 交换
     * @param status 状态
     * @param code 错误码
     * @param title 标题
     * @param detail 说明
     */
    private static void sendProblem(HttpExchange exchange, int status, String code, String title, String detail) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "https://riccon.example/problems/" + code);
            body.put("title", title);
            body.put("status", status);
            body.put("code", code);
            body.put("traceId", "mock-trace");
            body.put("detail", detail);
            byte[] bytes = MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException ignored) {
            // 连接已关闭。
        }
    }

    /**
     * 尽力写 ProblemDetail。
     *
     * @param exchange HTTP 交换
     * @param status 状态
     * @param code 错误码
     * @param title 标题
     * @param detail 说明
     */
    private static void sendProblemQuietly(
            HttpExchange exchange, int status, String code, String title, String detail) {
        try {
            sendProblem(exchange, status, code, title, detail);
        } catch (RuntimeException ignored) {
            // 响应可能已提交。
        }
    }

    /**
     * 读取文本字段。
     *
     * @param node 节点
     * @param field 字段
     * @return 文本
     */
    private static String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("");
    }

    /**
     * 读取 UUID 文本。
     *
     * @param node 节点
     * @param field 字段
     * @return 文本
     */
    private static String uuidText(JsonNode node, String field) {
        return text(node, field);
    }

    /** 关闭桩。 */
    @Override
    public void close() {
        hangRelease.countDown();
        cancelLatch.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
}
