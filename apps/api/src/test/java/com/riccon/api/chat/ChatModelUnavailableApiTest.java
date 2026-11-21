package com.riccon.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.read.ListAppender;
import com.riccon.api.chat.service.ChatService;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.contract.HttpHeaders;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * J1 全链不可用与 Worker 错误映射集成测试。
 *
 * <p>用本地 Worker 桩返回不同 ProblemDetail，校验 API 对 {@code RICCON-MODEL-503} 输出 AC-05 的
 * HTTP 503、稳定错误码与固定中文文案，并确认 400/409/429/500 的既有语义不回归。Worker 内部 detail
 * 只进入服务端日志，不允许出现在员工可见响应或落库的失败说明中。
 */
class ChatModelUnavailableApiTest extends ApiIntegrationTestSupport {

    /** 共享 HMAC 密钥；桩不校验签名，只要求客户端能构造请求。 */
    private static final String SECRET = "test-service-token-secret-0123456789abcdef";

    /** 本地 Worker 桩。 */
    private static final WorkerStub WORKER = new WorkerStub();

    /** JSON 解析。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 注入 Worker 桩地址与密钥。
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void workerProperties(DynamicPropertyRegistry registry) {
        registry.add("riccon.worker.client.base-url", WORKER::url);
        registry.add("riccon.worker.client.service-token-secret", () -> SECRET);
        registry.add("riccon.worker.client.idle-timeout", () -> "5s");
    }

    /** 每个用例前重置桩响应。 */
    @BeforeEach
    void resetWorker() {
        WORKER.reset();
    }

    /** 关闭桩。 */
    @AfterAll
    static void stopWorker() {
        WORKER.close();
    }

    /** 全链不可用：HTTP 503 + 稳定错误码 + AC-05 文案，且不泄露 Worker 内部信息。 */
    @Test
    @DisplayName("Worker RICCON-MODEL-503 映射为 AC-05 稳定 503")
    void modelUnavailable_mapsToStableProblem() {
        Fixture fixture = fixture();
        WORKER.reply(
                500,
                "RICCON-MODEL-503",
                "model route is empty; provider=mock-openai baseUrl=http://10.9.8.7:8080/v1 apiKey=sk-live-0123456789");

        ApiTestClient.ApiResponse response = send(fixture);

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.header("content-type")).startsWith("application/problem+json");
        JsonNode problem = client.json(response);
        assertThat(problem.path("status").asInt()).isEqualTo(503);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-MODEL-503");
        assertThat(problem.path("title").asText()).isEqualTo("Service Unavailable");
        assertThat(problem.path("detail").asText()).isEqualTo("模型服务暂不可用，请稍后重试。");
        assertThat(problem.path("traceId").asText()).isNotBlank();
        assertThat(response.body())
                .doesNotContain("provider=")
                .doesNotContain("baseUrl")
                .doesNotContain("sk-live")
                .doesNotContain("model route is empty")
                .doesNotContain("at com.riccon");

        FailedRun failed = failedRun(fixture);
        assertThat(failed.runStatus()).isEqualTo("FAILED");
        assertThat(failed.runErrorCode()).isEqualTo("RICCON-MODEL-503");
        assertThat(failed.runErrorMessage()).isEqualTo("模型服务暂不可用，请稍后重试。");
        assertThat(failed.messageErrorCode()).isEqualTo("RICCON-MODEL-503");
    }

    /**
     * V1 F-001 负向用例：Worker detail 携带上游凭据与内网地址时，日志不得回显原文。
     *
     * <p>用 Logback {@link ListAppender} 捕获请求期间 {@code com.riccon} 命名空间的日志，断言只出现状态、
     * 错误码与关联 ID；surefire 输出由构建命令 grep 兜底。响应与落库说明仍保持 AC-05 文案。
     */
    @Test
    @DisplayName("Worker detail 中的 apiKey/baseUrl 不进入日志")
    void modelUnavailable_logsNoWorkerDetailCanary() {
        Fixture fixture = fixture();
        WORKER.reply(
                500,
                "RICCON-MODEL-503",
                "model route is empty; provider=mock-openai baseUrl=http://10.9.8.7:8080/v1 apiKey=sk-live-canary-9876543210");

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        ApiTestClient.ApiResponse response;
        try {
            response = send(fixture);
        } finally {
            root.detachAppender(captured);
            captured.stop();
        }

        assertThat(response.status()).isEqualTo(503);
        JsonNode problem = client.json(response);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-MODEL-503");
        assertThat(problem.path("detail").asText()).isEqualTo("模型服务暂不可用，请稍后重试。");
        assertThat(response.body())
                .doesNotContain("apiKey")
                .doesNotContain("baseUrl")
                .doesNotContain("sk-live")
                .doesNotContain("10.9.8.7");

        // 日志仍须保留可定位的安全字段，避免为了脱敏而删除整条日志。
        List<String> chatServiceLines = captured.list.stream()
                .filter(event -> ChatService.class.getName().equals(event.getLoggerName()))
                .map(ChatModelUnavailableApiTest::renderLogEvent)
                .toList();
        assertThat(chatServiceLines)
                .anySatisfy(line -> assertThat(line)
                        .contains("chat.worker.rejected")
                        .contains("status=500")
                        .contains("code=RICCON-MODEL-503")
                        .contains("runId="));

        List<String> ricconLines = captured.list.stream()
                .filter(event ->
                        event.getLoggerName() != null && event.getLoggerName().startsWith("com.riccon"))
                .map(ChatModelUnavailableApiTest::renderLogEvent)
                .toList();
        assertThat(String.join("\n", ricconLines))
                .doesNotContain("apiKey")
                .doesNotContain("baseUrl")
                .doesNotContain("sk-live")
                .doesNotContain("10.9.8.7")
                .doesNotContain("model route is empty");
    }

    /**
     * 渲染日志事件（含异常链），用于检查日志是否回显上游原文。
     *
     * @param event 日志事件
     * @return 可检索的文本
     */
    private static String renderLogEvent(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        IThrowableProxy throwable = event.getThrowableProxy();
        while (throwable != null) {
            text.append('\n').append(throwable.getClassName()).append(": ").append(throwable.getMessage());
            StackTraceElementProxy[] stack = throwable.getStackTraceElementProxyArray();
            if (stack != null) {
                for (StackTraceElementProxy element : stack) {
                    text.append('\n').append(element.getSTEAsString());
                }
            }
            throwable = throwable.getCause();
        }
        return text.toString();
    }

    /** 其他 Worker 错误保持既有状态与错误码语义。 */
    @Test
    @DisplayName("400/409/429/500 映射不回归")
    void otherWorkerErrors_keepExistingSemantics() {
        Fixture fixture = fixture();

        WORKER.reply(400, "RICCON-CHAT-400", "trace header is required");
        ApiTestClient.ApiResponse invalid = send(fixture);
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(client.json(invalid).path("code").asText()).isEqualTo("RICCON-REQUEST-400");

        WORKER.reply(409, "RICCON-CHAT-409", "run is already terminal");
        ApiTestClient.ApiResponse conflict = send(fixture);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(client.json(conflict).path("code").asText()).isEqualTo("RICCON-CONFLICT-409");

        WORKER.reply(429, "RICCON-QUEUE-429", "worker stream capacity is full");
        ApiTestClient.ApiResponse tooMany = send(fixture);
        assertThat(tooMany.status()).isEqualTo(429);
        assertThat(client.json(tooMany).path("code").asText()).isEqualTo("RICCON-QUEUE-429");

        WORKER.reply(500, "RICCON-CHAT-500", "worker request failed");
        ApiTestClient.ApiResponse upstream = send(fixture);
        assertThat(upstream.status()).isEqualTo(500);
        assertThat(client.json(upstream).path("code").asText()).isEqualTo("RICCON-CHAT-500");
    }

    /** Worker 以 HTTP 503 承载同一错误码时仍命中 AC-05 映射。 */
    @Test
    @DisplayName("Worker HTTP 503 + RICCON-MODEL-503 同样映射为稳定 503")
    void modelUnavailableWithServiceUnavailableStatus_mapsToStableProblem() {
        Fixture fixture = fixture();
        WORKER.reply(503, "RICCON-MODEL-503", "model chain is unavailable");

        ApiTestClient.ApiResponse response = send(fixture);

        assertThat(response.status()).isEqualTo(503);
        JsonNode problem = client.json(response);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-MODEL-503");
        assertThat(problem.path("detail").asText()).isEqualTo("模型服务暂不可用，请稍后重试。");
    }

    /** SSE 首字前 failed 帧携带 RICCON-MODEL-503 时同样映射为 AC-05 稳定 503。 */
    @Test
    @DisplayName("首字前 failed 帧 RICCON-MODEL-503 映射为稳定 503")
    void preFirstFailedFrame_mapsToStableProblem() {
        Fixture fixture = fixture();
        WORKER.replyPreFirstFailedFrame("RICCON-MODEL-503", "模型服务暂不可用，请稍后重试。");

        ApiTestClient.ApiResponse response = send(fixture);

        assertThat(response.status()).isEqualTo(503);
        JsonNode problem = client.json(response);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-MODEL-503");
        assertThat(problem.path("detail").asText()).isEqualTo("模型服务暂不可用，请稍后重试。");
        FailedRun failed = failedRun(fixture);
        assertThat(failed.runErrorMessage()).isEqualTo("模型服务暂不可用，请稍后重试。");
    }

    /** SSE 首字前 failed 帧的其他错误码维持通用上游失败语义。 */
    @Test
    @DisplayName("首字前 failed 帧其他错误码维持通用 500")
    void preFirstFailedFrameOtherCode_keepsUpstreamFailure() {
        Fixture fixture = fixture();
        WORKER.replyPreFirstFailedFrame("RICCON-MODEL-500", "model resolution failed");

        ApiTestClient.ApiResponse response = send(fixture);

        assertThat(response.status()).isEqualTo(500);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-CHAT-500");
    }

    /**
     * 创建租户、成员、个人空间与会话。
     *
     * @return 测试夹具
     */
    private Fixture fixture() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("chata05");
        createTenant(platform, tenant);
        LoginSession member = createAndLoginMember(platform, tenant);
        String workspaceId = personalWorkspaceId(member);
        ApiTestClient.ApiResponse created = client.post(
                "/api/v1/workspaces/" + workspaceId + "/conversations",
                "{}",
                member.sessionCookie(),
                member.csrfToken(),
                Map.of());
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return new Fixture(tenant, member, client.json(created).path("id").asText());
    }

    /**
     * 读取个人空间 ID。
     *
     * @param session 登录会话
     * @return 工作空间 ID
     */
    private String personalWorkspaceId(LoginSession session) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workspaces?page=1&size=100", session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response).path("items").get(0).path("id").asText();
    }

    /**
     * 发送一条文本消息。
     *
     * @param fixture 测试夹具
     * @return 响应
     */
    private ApiTestClient.ApiResponse send(Fixture fixture) {
        return client.post(
                "/api/v1/conversations/" + fixture.conversationId() + "/messages",
                client.jsonBody("content", "请总结 2026 年企业协同办公市场", "clientMessageId", "client-" + UUID.randomUUID()),
                fixture.session().sessionCookie(),
                fixture.session().csrfToken(),
                Map.of("Accept", "text/event-stream", "Idempotency-Key", "idem-" + UUID.randomUUID()));
    }

    /**
     * 读取当前会话最近一次运行的失败落库信息。
     *
     * @param fixture 测试夹具
     * @return 失败快照
     */
    private FailedRun failedRun(Fixture fixture) {
        byte[] runId = jdbcClient
                .sql("""
                        SELECT id FROM agent_run
                         WHERE tenant_id = :tenantId AND conversation_id = :conversationId
                        """)
                .param("tenantId", fixture.tenant())
                .param("conversationId", BinaryUuids.toBytes(UUID.fromString(fixture.conversationId())))
                .query(byte[].class)
                .single();
        RunFailure failure = jdbcClient
                .sql("""
                        SELECT status, error_code, error_message FROM agent_run
                         WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("tenantId", fixture.tenant())
                .param("runId", runId)
                .query((rs, rowNum) -> new RunFailure(
                        rs.getString("status"), rs.getString("error_code"), rs.getString("error_message")))
                .single();
        String messageErrorCode = jdbcClient
                .sql("""
                        SELECT error_code FROM message
                         WHERE tenant_id = :tenantId AND conversation_id = :conversationId AND role = 'ASSISTANT'
                        """)
                .param("tenantId", fixture.tenant())
                .param("conversationId", BinaryUuids.toBytes(UUID.fromString(fixture.conversationId())))
                .query(String.class)
                .single();
        return new FailedRun(failure.status(), failure.errorCode(), failure.errorMessage(), messageErrorCode);
    }

    /**
     * 测试夹具。
     *
     * @param tenant 租户
     * @param session 成员会话
     * @param conversationId 会话 ID
     */
    private record Fixture(String tenant, LoginSession session, String conversationId) {}

    /**
     * 运行失败列。
     *
     * @param status 状态
     * @param errorCode 错误码
     * @param errorMessage 失败说明
     */
    private record RunFailure(String status, String errorCode, String errorMessage) {}

    /**
     * 失败落库快照。
     *
     * @param runStatus 运行状态
     * @param runErrorCode 运行错误码
     * @param runErrorMessage 运行失败说明
     * @param messageErrorCode assistant 消息错误码
     */
    private record FailedRun(String runStatus, String runErrorCode, String runErrorMessage, String messageErrorCode) {}

    /**
     * 可配置的 Worker 桩：只实现对话流端点，按用例返回 ProblemDetail 或首字前 failed SSE 帧。
     */
    private static final class WorkerStub implements AutoCloseable {

        /** HTTP 服务。 */
        private final HttpServer server;

        /** 执行器。 */
        private final ExecutorService executor;

        /** 当前返回状态。 */
        private volatile int status = 500;

        /** 当前错误码。 */
        private volatile String code = "RICCON-MODEL-503";

        /** 当前失败说明。 */
        private volatile String detail = "model route is empty";

        /** 是否返回首字前 failed SSE 帧。 */
        private volatile boolean sseFailedFrame;

        /** 启动桩。 */
        private WorkerStub() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException exception) {
                throw new IllegalStateException("failed to start chat worker stub", exception);
            }
            server.createContext("/internal/v1/chat/streams", this::handle);
            executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "chat-worker-stub");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.start();
        }

        /**
         * 返回桩地址。
         *
         * @return 形如 {@code http://127.0.0.1:port}
         */
        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        /**
         * 设置 ProblemDetail 响应。
         *
         * @param status HTTP 状态
         * @param code 错误码
         * @param detail 失败说明
         */
        private void reply(int status, String code, String detail) {
            this.status = status;
            this.code = code;
            this.detail = detail;
            this.sseFailedFrame = false;
        }

        /**
         * 设置首字前 failed SSE 帧响应。
         *
         * @param code 错误码
         * @param detail 失败说明
         */
        private void replyPreFirstFailedFrame(String code, String detail) {
            this.code = code;
            this.detail = detail;
            this.sseFailedFrame = true;
        }

        /** 恢复默认的全链不可用响应。 */
        private void reset() {
            reply(500, "RICCON-MODEL-503", "model route is empty");
        }

        /**
         * 处理对话流请求。
         *
         * @param exchange HTTP 交换
         * @throws IOException 写失败
         */
        private void handle(HttpExchange exchange) throws IOException {
            try (InputStream input = exchange.getRequestBody()) {
                byte[] body = input.readNBytes(1024 * 1024);
                if (sseFailedFrame) {
                    writeFailedFrame(exchange, body);
                } else {
                    writeProblem(exchange, status, code, detail);
                }
            } finally {
                exchange.close();
            }
        }

        /**
         * 写首字前 failed 事件帧。
         *
         * @param exchange HTTP 交换
         * @param body 请求体
         * @throws IOException 写失败
         */
        private void writeFailedFrame(HttpExchange exchange, byte[] body) throws IOException {
            JsonNode request = MAPPER.readTree(body);
            String traceId = exchange.getRequestHeaders().getFirst(HttpHeaders.TRACE_ID);
            ObjectNode event = MAPPER.createObjectNode();
            event.put("eventId", UUID.randomUUID().toString());
            event.put("eventType", EventTypes.CHAT_FAILED);
            event.put("version", 1);
            event.put("occurredAt", Instant.now().toString());
            event.put("tenantId", request.path("tenantId").asText());
            event.put("runId", request.path("runId").asText());
            event.put("messageId", UUID.randomUUID().toString());
            event.put("traceId", traceId == null ? "" : traceId);
            ObjectNode data = MAPPER.createObjectNode();
            data.put("code", code);
            data.put("message", detail);
            data.put("retryable", true);
            event.set("data", data);
            byte[] frame = ("event: " + EventTypes.CHAT_FAILED + "\n" + "data: " + MAPPER.writeValueAsString(event)
                            + "\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(frame);
                output.flush();
            }
        }

        /**
         * 写 ProblemDetail。
         *
         * @param exchange HTTP 交换
         * @param status HTTP 状态
         * @param code 错误码
         * @param detail 失败说明
         * @throws IOException 写失败
         */
        private static void writeProblem(HttpExchange exchange, int status, String code, String detail)
                throws IOException {
            Map<String, Object> problem = new LinkedHashMap<>();
            problem.put("type", "https://riccon.example/problems/" + code);
            problem.put("title", "Internal Server Error");
            problem.put("status", status);
            problem.put("code", code);
            problem.put("traceId", "mock-trace");
            problem.put("detail", detail);
            byte[] bytes = MAPPER.writeValueAsString(problem).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        /** 关闭桩。 */
        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
