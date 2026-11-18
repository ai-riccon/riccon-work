package com.riccon.worker.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.domain.contract.HttpHeaders;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.support.TestCredentials;
import com.riccon.worker.support.TestModelStub;
import com.riccon.worker.support.TestServiceToken;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 对话 SSE 增量到达验证。
 *
 * <p>使用真实 HTTP 端口而不是 MockMvc：MockMvc 会收齐整个响应体，无法区分“逐事件 flush”和
 * “缓冲到结束”。模型桩在首个 content 帧后暂停，测试只有先读到 delta 才释放；若 Worker 仍用
 * {@code StreamingResponseBody} 缓冲，读取 delta 会超时。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"riccon.worker.outbox.scheduling-enabled=false"})
class ChatStreamFlushIntegrationTest extends WorkerIntegrationTestBase {

    /** 真实端口。 */
    @LocalServerPort
    private int port;

    /** JSON 序列化。 */
    @Autowired
    private ObjectMapper objectMapper;

    /** 模型桩。 */
    private TestModelStub stub;

    /** 启动模型桩。 */
    @BeforeEach
    void setUp() {
        stub = new TestModelStub();
    }

    /** 关闭模型桩并释放可能挂起的输出。 */
    @AfterEach
    void tearDown() {
        stub.releaseContent();
        stub.close();
    }

    /** 模型尚未结束时客户端已能读到首个 delta，证明逐事件 flush。 */
    @Test
    void stream_deltaArrivesBeforeModelCompletes() throws Exception {
        Fixture fixture = fixture("tenant-a", "市场问题");
        stub.holdAfterFirstContent();
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/internal/v1/chat/streams"))
                .timeout(Duration.ofSeconds(30))
                .header(HttpHeaders.SERVICE_TOKEN, fixture.token())
                .header(HttpHeaders.TRACE_ID, "trace-flush")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson(fixture), StandardCharsets.UTF_8))
                .build();

        ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body();
                BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            assertThat(response.statusCode()).isEqualTo(200);
            Future<String> deltaFuture = readerExecutor.submit(() -> readEventData(reader, "riccon.chat.delta.v1"));
            // 模型桩仍被 contentGate 阻塞；必须在桩的 15s 兜底超时之前读到 delta，
            // 否则“缓冲到流结束”的实现会在 gate 超时后同样通过（TASK-0024 F-002 的假绿）。
            String delta = deltaFuture.get(5, TimeUnit.SECONDS);
            JsonNode deltaJson = objectMapper.readTree(delta);
            assertThat(deltaJson.path("data").path("delta").asText()).isNotBlank();

            stub.releaseContent();
            Future<String> completedFuture =
                    readerExecutor.submit(() -> readEventData(reader, "riccon.chat.completed.v1"));
            String completed = completedFuture.get(20, TimeUnit.SECONDS);
            assertThat(objectMapper
                            .readTree(completed)
                            .path("data")
                            .path("finishReason")
                            .asText())
                    .isEqualTo("stop");
        } finally {
            stub.releaseContent();
            readerExecutor.shutdownNow();
        }
        assertThat(runStatus(fixture.runId())).isEqualTo("SUCCEEDED");
    }

    /**
     * 读取指定事件后的 data 行。
     *
     * @param reader SSE 行读取器
     * @param eventType 事件类型
     * @return data JSON
     * @throws Exception 读取失败
     */
    private static String readEventData(BufferedReader reader, String eventType) throws Exception {
        String line;
        boolean matched = false;
        while ((line = reader.readLine()) != null) {
            String normalized = line.startsWith("event:")
                    ? line.substring("event:".length()).strip()
                    : null;
            if (eventType.equals(normalized)) {
                matched = true;
                continue;
            }
            if (matched && line.startsWith("data:")) {
                return line.substring("data:".length()).strip();
            }
        }
        throw new IllegalStateException("event was not received: " + eventType);
    }

    /** 构造对话夹具。 */
    private Fixture fixture(String tenant, String question) {
        UUID user = seedTenant(tenant);
        UUID workspace = data.createWorkspace(tenant, user);
        UUID conversation = data.createConversation(tenant, workspace, user);
        UUID userMessage = data.createMessage(tenant, conversation, 1, "USER", question);
        UUID runId = data.createChatRun(tenant, conversation, user);
        UUID document = data.createKnowledgeDocument(tenant, "市场资料", null, user);
        data.createKnowledgeChunk(tenant, document, 1, "市场保持增长。", 1);
        jdbcClient
                .sql("UPDATE knowledge_document SET status = 'INDEXED' WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(document))
                .update();
        data.createModelCatalog(stub.url(), TestCredentials.API_KEY);
        String token = TestServiceToken.mint(tenant, user, runId);
        return new Fixture(tenant, user, conversation, userMessage, runId, token);
    }

    /** 请求 JSON。 */
    private String requestJson(Fixture fixture) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", fixture.runId().toString());
        body.put("tenantId", fixture.tenantId());
        body.put("userId", fixture.userId().toString());
        body.put("conversationId", fixture.conversationId().toString());
        body.put("userMessageId", fixture.userMessageId().toString());
        return objectMapper.writeValueAsString(body);
    }

    /** 运行状态。 */
    private String runStatus(UUID runId) {
        return jdbcClient
                .sql("SELECT status FROM agent_run WHERE id = :id")
                .param("id", UuidBinary.toBytes(runId))
                .query(String.class)
                .single();
    }

    /** 创建租户与用户。 */
    private UUID seedTenant(String tenant) {
        data.createTenant(tenant);
        return data.createUser(tenant);
    }

    /**
     * 对话夹具。
     *
     * @param tenantId 租户
     * @param userId 用户
     * @param conversationId 会话
     * @param userMessageId 用户消息
     * @param runId 运行
     * @param token 服务令牌
     */
    private record Fixture(
            String tenantId, UUID userId, UUID conversationId, UUID userMessageId, UUID runId, String token) {}
}
