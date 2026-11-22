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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

/**
 * 对话首字到达时间验证（M02-AC-03、F-002/F-008）。
 *
 * <p>模型桩按 180ms 间隔分片输出，因此“首字被缓冲到流结束”会表现为首个 delta 接近整段流耗时。
 * 测试读取真实 HTTP 端口上的 SSE 行并记录每帧到达时间：既断言首字预算，也断言增量在模型流
 * 结束前逐条到达。只校验最终 body 的用例无法发现缓冲回归。
 *
 * <p>先发一次预热请求：首个请求包含 Tomcat 线程、JIT 与连接初始化开销（实测约 300ms），
 * 会把与业务无关的冷启动计入首字；预热后按真实的持续服务状态测量。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"riccon.worker.outbox.scheduling-enabled=false"})
class ChatStreamFirstTokenIntegrationTest extends WorkerIntegrationTestBase {

    /** 首字预算：客户端读到首个 delta 的时间上限（毫秒）。 */
    private static final int FIRST_DELTA_BUDGET_MS = 500;

    /** 模型分片：首个 content 帧之前的延迟。 */
    private static final int FIRST_FRAME_DELAY_MS = 60;

    /** 模型分片：相邻 content 帧间隔。 */
    private static final int FRAME_INTERVAL_MS = 180;

    /** 模型分片数量；8 片 × 180ms ≈ 1.3s，足以区分“首字”和“流结束”。 */
    private static final int SLICES = 8;

    /** 真实端口。 */
    @LocalServerPort
    private int port;

    /** JSON 序列化。 */
    @Autowired
    private ObjectMapper objectMapper;

    /** 模型桩。 */
    private TestModelStub stub;

    /** 模型目录是全局表，同一测试内多次请求只建一次。 */
    private boolean catalogCreated;

    /** 启动模型桩；恢复 M04 全局模型基线。 */
    @BeforeEach
    void setUp() {
        resetModelGatewayState();
        stub = new TestModelStub();
        catalogCreated = false;
    }

    /** 关闭模型桩。 */
    @AfterEach
    void tearDown() {
        stub.close();
    }

    /** 首个 delta 在预算内到达，且后续增量继续逐条到达，不是流结束后一次性吐出。 */
    @Test
    void stream_firstDeltaArrivesWithinBudgetAndBeforeStreamEnd() throws Exception {
        stub.timeSliced(FIRST_FRAME_DELAY_MS, FRAME_INTERVAL_MS, SLICES);
        // 预热请求：只消耗完整流，不参与断言。
        Measurement warmUp = measure(fixture("tenant-warmup", "预热问题"));
        assertThat(warmUp.deltaArrivals()).as("预热流应有增量").isNotEmpty();

        Measurement measured = measure(fixture("tenant-measured", "市场问题"));

        assertThat(measured.deltaArrivals()).as("至少一个 delta 事件").isNotEmpty();
        assertThat(measured.deltaArrivals()).as("分片模型应产生多个增量").hasSizeGreaterThan(1);
        long firstDeltaMs = measured.deltaArrivals().get(0);
        long lastDeltaMs = measured.deltaArrivals().get(measured.deltaArrivals().size() - 1);
        assertThat(firstDeltaMs)
                .as("首个 delta 到达时间 %dms（分片模型共 %d 片 × 180ms）", firstDeltaMs, SLICES)
                .isLessThanOrEqualTo((long) FIRST_DELTA_BUDGET_MS);
        assertThat(lastDeltaMs).as("最后一个 delta 必须晚于首个 delta，证明是逐条流式而不是结束回放").isGreaterThan(firstDeltaMs + 500);
        assertThat(measured.completedAtMs()).as("completed 事件必须出现").isGreaterThan(0);
        assertThat(measured.completedAtMs() - firstDeltaMs)
                .as("首字与完成之间的间隔应覆盖模型流剩余分片")
                .isGreaterThan((long) FIRST_DELTA_BUDGET_MS);

        InvocationMetrics metrics = invocationMetrics(measured.runId());
        assertThat(metrics.status()).isEqualTo("SUCCEEDED");
        assertThat(metrics.firstTokenMs())
                .as("first_token_ms 必须记录首个可见增量，而不是流结束")
                .isNotNull()
                .isLessThanOrEqualTo(FIRST_DELTA_BUDGET_MS + 200);
        assertThat((long) metrics.durationMs())
                .as("duration_ms 必须覆盖整段分片流，且远大于 first_token_ms")
                .isGreaterThan(2L * metrics.firstTokenMs());
        assertThat(runStatus(measured.runId())).isEqualTo("SUCCEEDED");
        // 输出实测值：注释与证据都以真实时间戳为准，而不是“应该很快”。
        System.out.println("FIRST_TOKEN_SSE_MS=" + firstDeltaMs
                + " lastDeltaMs=" + lastDeltaMs
                + " completedMs=" + measured.completedAtMs()
                + " workerFirstTokenMs=" + metrics.firstTokenMs()
                + " durationMs=" + metrics.durationMs());
    }

    /**
     * 发起一次对话流，记录每个 delta 与 completed 的到达时间。
     *
     * @param fixture 对话夹具
     * @return 时间测量
     * @throws Exception 请求或读取失败
     */
    private Measurement measure(Fixture fixture) throws Exception {
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/internal/v1/chat/streams"))
                .timeout(Duration.ofSeconds(30))
                .header(HttpHeaders.SERVICE_TOKEN, fixture.token())
                .header(HttpHeaders.TRACE_ID, "trace-first-token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson(fixture), StandardCharsets.UTF_8))
                .build();

        long requestStartedAt = System.nanoTime();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        List<Long> deltaArrivals = new ArrayList<>();
        long completedAt = -1;
        try (InputStream body = response.body();
                BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            String pendingEvent = null;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    pendingEvent = line.substring("event:".length()).strip();
                    continue;
                }
                if (line.startsWith("data:") && pendingEvent != null) {
                    if ("riccon.chat.delta.v1".equals(pendingEvent)) {
                        deltaArrivals.add(elapsedMs(requestStartedAt));
                    } else if ("riccon.chat.completed.v1".equals(pendingEvent)) {
                        completedAt = elapsedMs(requestStartedAt);
                        break;
                    }
                    pendingEvent = null;
                }
            }
        }
        assertThat(completedAt).as("流必须以 completed 结束").isGreaterThan(0);
        return new Measurement(fixture.runId(), deltaArrivals, completedAt);
    }

    /**
     * 计算自请求开始以来的毫秒数。
     *
     * @param startedAt 开始纳秒
     * @return 毫秒
     */
    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /** 构造对话夹具；模型目录全局唯一，只创建一次；每个租户 ID 带随机后缀。 */
    private Fixture fixture(String tenantPrefix, String question) {
        String tenant = uniqueTenant(tenantPrefix);
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
        if (!catalogCreated) {
            data.createModelCatalog(stub.url(), TestCredentials.API_KEY);
            catalogCreated = true;
        }
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

    /** 读取调用指标。 */
    private InvocationMetrics invocationMetrics(UUID runId) {
        return jdbcClient
                .sql("""
                        SELECT status, first_token_ms, duration_ms
                          FROM model_invocation
                         WHERE run_id = :runId
                         ORDER BY started_at DESC
                         LIMIT 1
                        """)
                .param("runId", UuidBinary.toBytes(runId))
                .query((rs, rowNum) -> new InvocationMetrics(
                        rs.getString("status"),
                        rs.getObject("first_token_ms", Integer.class),
                        rs.getInt("duration_ms")))
                .single();
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

    /**
     * 一次流的到达时间测量。
     *
     * @param runId 运行 ID
     * @param deltaArrivals 每个 delta 的到达毫秒
     * @param completedAtMs completed 到达毫秒
     */
    private record Measurement(UUID runId, List<Long> deltaArrivals, long completedAtMs) {}

    /**
     * 模型调用指标。
     *
     * @param status 状态
     * @param firstTokenMs 首字毫秒
     * @param durationMs 总耗时
     */
    private record InvocationMetrics(String status, Integer firstTokenMs, int durationMs) {}
}
