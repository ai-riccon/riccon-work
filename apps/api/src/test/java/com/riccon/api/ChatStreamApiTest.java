package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.MockWorkerServer;
import com.riccon.api.support.MockWorkerServer.Scenario;
import com.riccon.api.support.TestSseReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * J1 发送消息与 SSE 集成测试。
 *
 * <p>使用本地 mock Worker 校验服务令牌、请求绑定与六类 SSE 事件的转发/落库：成功、引用、幂等、首字前失败、
 * 首字后失败、停止、空闲超时、跨租户与参数边界。所有等待使用 latch 或 HTTP 流结束，不 sleep。
 */
class ChatStreamApiTest extends ApiIntegrationTestSupport {

    /** 测试共享密钥。 */
    private static final String SECRET = "test-service-token-secret-0123456789abcdef";

    /** mock Worker。 */
    private static final MockWorkerServer WORKER = new MockWorkerServer(SECRET);

    /** JSON 解析。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 模型供应商 ID。 */
    private static final UUID PROVIDER_ID = UUID.fromString("01930000-0000-7000-8000-0000000000ab");

    /**
     * 注入 mock Worker 地址与密钥。
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void workerProperties(DynamicPropertyRegistry registry) {
        registry.add("riccon.worker.client.base-url", WORKER::url);
        registry.add("riccon.worker.client.service-token-secret", () -> SECRET);
        // 空闲上限 5 秒：停止用例有足够窗口，超时用例约 6 秒结束。
        registry.add("riccon.worker.client.idle-timeout", () -> "5s");
    }

    /** 每个用例前重置场景与回调。 */
    @BeforeEach
    void resetMock() {
        WORKER.reset(Scenario.SUCCESS);
        WORKER.setChatHook(this::prepareModelInvocation);
    }

    /** 释放挂起的 mock 流。 */
    @AfterEach
    void releaseMock() {
        WORKER.releaseHang();
    }

    /** 关闭 mock Worker。 */
    @AfterAll
    static void stopMock() {
        WORKER.close();
    }

    /** 成功流：透传六类事件、落库内容/引用/运行状态并回填模型调用。 */
    @Test
    @DisplayName("发送消息成功并落库内容、引用、运行与模型调用")
    void sendMessage_persistsContentCitationsAndRun() throws Exception {
        Fixture fixture = fixture();
        String clientMessageId = "client-" + UUID.randomUUID();

        ApiTestClient.ApiResponse response = send(fixture, "你好，介绍一下制度", clientMessageId, Map.of());

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.header("content-type")).startsWith("text/event-stream");
        assertThat(response.header("cache-control")).contains("no-store");
        assertThat(response.header("X-Trace-Id")).isNotBlank();

        List<TestSseReader.Frame> frames = readFrames(response.body());
        assertThat(frames)
                .extracting(TestSseReader.Frame::event)
                .containsExactly(
                        "riccon.chat.started.v1",
                        "riccon.chat.citation.v1",
                        "riccon.chat.delta.v1",
                        "riccon.chat.delta.v1",
                        "riccon.chat.completed.v1");

        JsonNode started = MAPPER.readTree(frames.get(0).data());
        String runId = started.path("runId").asText();
        String assistantId = started.path("messageId").asText();
        assertThat(assistantId).isEqualTo(messageId(fixture.tenant(), fixture.conversationId(), "ASSISTANT"));
        for (TestSseReader.Frame frame : frames) {
            assertThat(MAPPER.readTree(frame.data()).path("messageId").asText()).isEqualTo(assistantId);
        }

        MessageState user = messageState(fixture.tenant(), fixture.conversationId(), "USER");
        assertThat(user.content()).isEqualTo("你好，介绍一下制度");
        assertThat(user.status()).isEqualTo("COMPLETED");
        MessageState assistant = messageState(fixture.tenant(), fixture.conversationId(), "ASSISTANT");
        assertThat(assistant.content()).isEqualTo("根据资料，答案是 42。");
        assertThat(assistant.status()).isEqualTo("COMPLETED");
        assertThat(assistant.errorCode()).isNull();
        assertThat(BinaryUuids.fromBytes(assistant.runId()).toString()).isEqualTo(runId);
        assertThat(BinaryUuids.fromBytes(assistant.modelId())).isEqualTo(MockWorkerServer.MODEL_ID);

        String runStatus = jdbcClient
                .sql("SELECT status FROM agent_run WHERE tenant_id = :tenantId AND id = :runId")
                .param("tenantId", fixture.tenant())
                .param("runId", BinaryUuids.toBytes(UUID.fromString(runId)))
                .query(String.class)
                .single();
        assertThat(runStatus).isEqualTo("SUCCEEDED");

        Long citations = jdbcClient
                .sql("""
                        SELECT COUNT(*) FROM message_citation
                         WHERE tenant_id = :tenantId AND message_id = :messageId
                        """)
                .param("tenantId", fixture.tenant())
                .param("messageId", BinaryUuids.toBytes(UUID.fromString(assistantId)))
                .query(Long.class)
                .single();
        assertThat(citations).isEqualTo(1L);
        BigDecimal score = jdbcClient
                .sql("""
                        SELECT score FROM message_citation
                         WHERE tenant_id = :tenantId AND message_id = :messageId
                        """)
                .param("tenantId", fixture.tenant())
                .param("messageId", BinaryUuids.toBytes(UUID.fromString(assistantId)))
                .query(BigDecimal.class)
                .single();
        assertThat(score).isEqualByComparingTo("0.9");

        Long invocationLinked = jdbcClient
                .sql("""
                        SELECT COUNT(*) FROM model_invocation
                         WHERE tenant_id = :tenantId AND run_id = :runId AND message_id = :messageId
                        """)
                .param("tenantId", fixture.tenant())
                .param("runId", BinaryUuids.toBytes(UUID.fromString(runId)))
                .param("messageId", BinaryUuids.toBytes(UUID.fromString(assistantId)))
                .query(Long.class)
                .single();
        assertThat(invocationLinked).isEqualTo(1L);

        JsonNode workerRequest = WORKER.lastChatRequest();
        assertThat(workerRequest.path("runId").asText()).isEqualTo(runId);
        assertThat(workerRequest.path("tenantId").asText()).isEqualTo(fixture.tenant());
        assertThat(workerRequest.path("userId").asText()).isEqualTo(fixture.userId());
        assertThat(workerRequest.path("conversationId").asText()).isEqualTo(fixture.conversationId());
        assertThat(workerRequest.path("userMessageId").asText())
                .isEqualTo(messageId(fixture.tenant(), fixture.conversationId(), "USER"));
        assertThat(WORKER.lastTokenClaims().path("tenantId").asText()).isEqualTo(fixture.tenant());
        assertThat(WORKER.lastTokenClaims().path("sub").asText()).isEqualTo("user:" + fixture.userId());
        assertThat(WORKER.chatRequestCount()).isEqualTo(1);

        JsonNode conversation = getConversation(fixture);
        assertThat(conversation.path("title").asText()).isEqualTo("你好，介绍一下制度");
        assertThat(conversation.path("messages")).hasSize(2);
        assertThat(conversation.path("messages").get(1).path("citations")).hasSize(1);

        assertThat(auditRows("message.sent"))
                .filteredOn(row -> fixture.tenant().equals(row.tenantId()))
                .anySatisfy(row -> assertThat(row.details())
                        .contains(runId)
                        .contains(assistantId)
                        .contains("\"attachmentCount\": 0"));
    }

    /** 同一 clientMessageId 重试返回 409，不产生第二次运行或 Worker 调用。 */
    @Test
    @DisplayName("clientMessageId 幂等，重复提交返回 409")
    void duplicateClientMessageId_isRejectedWithoutSecondRun() {
        Fixture fixture = fixture();
        String clientMessageId = "client-" + UUID.randomUUID();

        assertThat(send(fixture, "第一次", clientMessageId, Map.of()).status()).isEqualTo(200);
        ApiTestClient.ApiResponse duplicate = send(fixture, "第二次", clientMessageId, Map.of());

        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(client.json(duplicate).path("code").asText()).isEqualTo("RICCON-CONFLICT-409");
        assertThat(WORKER.chatRequestCount()).isEqualTo(1);
        Long userMessages = jdbcClient
                .sql("""
                        SELECT COUNT(*) FROM message
                         WHERE tenant_id = :tenantId AND conversation_id = :conversationId AND role = 'USER'
                        """)
                .param("tenantId", fixture.tenant())
                .param("conversationId", BinaryUuids.toBytes(UUID.fromString(fixture.conversationId())))
                .query(Long.class)
                .single();
        assertThat(userMessages).isEqualTo(1L);
        Long runs = jdbcClient
                .sql("""
                        SELECT COUNT(*) FROM agent_run
                         WHERE tenant_id = :tenantId AND conversation_id = :conversationId
                        """)
                .param("tenantId", fixture.tenant())
                .param("conversationId", BinaryUuids.toBytes(UUID.fromString(fixture.conversationId())))
                .query(Long.class)
                .single();
        assertThat(runs).isEqualTo(1L);
    }

    /** 首字前失败返回 ProblemDetail，即使客户端 Accept 为 SSE。 */
    @Test
    @DisplayName("首字前失败返回 ProblemDetail 并落库 FAILED")
    void preFirstFailure_returnsProblemDetail() {
        Fixture fixture = fixture();
        WORKER.reset(Scenario.PRE_FIRST_FAILURE);

        ApiTestClient.ApiResponse response =
                send(fixture, "会失败吗", "client-" + UUID.randomUUID(), Map.of("Accept", "text/event-stream"));

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.header("content-type")).startsWith("application/problem+json");
        JsonNode problem = client.json(response);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-CHAT-500");
        assertThat(problem.path("traceId").asText()).isNotBlank();

        MessageState assistant = messageState(fixture.tenant(), fixture.conversationId(), "ASSISTANT");
        assertThat(assistant.status()).isEqualTo("FAILED");
        assertThat(assistant.content()).isEmpty();
        String runStatus = jdbcClient
                .sql("""
                        SELECT status FROM agent_run
                         WHERE tenant_id = :tenantId AND conversation_id = :conversationId
                        """)
                .param("tenantId", fixture.tenant())
                .param("conversationId", BinaryUuids.toBytes(UUID.fromString(fixture.conversationId())))
                .query(String.class)
                .single();
        assertThat(runStatus).isEqualTo("FAILED");
        assertThat(auditRows("message.sent"))
                .filteredOn(row -> fixture.tenant().equals(row.tenantId()))
                .hasSize(1);
    }

    /** 首字后失败：透传 failed，保留已生成内容。 */
    @Test
    @DisplayName("首字后失败透传 failed 并保留内容")
    void midStreamFailure_forwardsFailedAndKeepsContent() throws Exception {
        Fixture fixture = fixture();
        WORKER.reset(Scenario.MID_FAILURE);

        ApiTestClient.ApiResponse response = send(fixture, "中途失败", "client-" + UUID.randomUUID(), Map.of());

        assertThat(response.status()).isEqualTo(200);
        List<TestSseReader.Frame> frames = readFrames(response.body());
        assertThat(frames)
                .extracting(TestSseReader.Frame::event)
                .containsExactly("riccon.chat.started.v1", "riccon.chat.delta.v1", "riccon.chat.failed.v1");
        JsonNode failed = MAPPER.readTree(frames.get(2).data());
        assertThat(failed.path("data").path("code").asText()).isEqualTo("RICCON-MODEL-500");
        assertThat(failed.path("data").path("retryable").asBoolean()).isTrue();

        MessageState assistant = messageState(fixture.tenant(), fixture.conversationId(), "ASSISTANT");
        assertThat(assistant.content()).isEqualTo("部分内容");
        assertThat(assistant.status()).isEqualTo("FAILED");
        assertThat(assistant.errorCode()).isEqualTo("RICCON-MODEL-500");
        String runStatus = jdbcClient
                .sql("""
                        SELECT status FROM agent_run
                         WHERE tenant_id = :tenantId AND conversation_id = :conversationId
                        """)
                .param("tenantId", fixture.tenant())
                .param("conversationId", BinaryUuids.toBytes(UUID.fromString(fixture.conversationId())))
                .query(String.class)
                .single();
        assertThat(runStatus).isEqualTo("FAILED");
    }

    /** 停止生成：202、stopped 事件、内容保留与审计。 */
    @Test
    @DisplayName("停止生成保留内容并写审计")
    void stopGeneration_preservesContentAndAudits() throws Exception {
        Fixture fixture = fixture();
        WORKER.reset(Scenario.STOP);
        String clientMessageId = "client-" + UUID.randomUUID();
        ApiTestClient.StreamResponse stream = client.postStream(
                "/api/v1/conversations/" + fixture.conversationId() + "/messages",
                client.jsonBody("content", "请生成一段回答", "clientMessageId", clientMessageId),
                fixture.session().sessionCookie(),
                fixture.session().csrfToken(),
                Map.of("Accept", "text/event-stream", "Idempotency-Key", "idem-" + UUID.randomUUID()));
        assertThat(stream.status()).isEqualTo(200);
        try (TestSseReader reader = new TestSseReader(stream.body())) {
            TestSseReader.Frame started = reader.next();
            assertThat(started).isNotNull();
            assertThat(started.event()).isEqualTo("riccon.chat.started.v1");
            String runId = MAPPER.readTree(started.data()).path("runId").asText();
            TestSseReader.Frame delta = reader.next();
            assertThat(delta).isNotNull();
            assertThat(delta.event()).isEqualTo("riccon.chat.delta.v1");

            ApiTestClient.ApiResponse stop = client.post(
                    "/api/v1/conversations/" + fixture.conversationId() + "/stop",
                    client.jsonBody("runId", runId),
                    fixture.session().sessionCookie(),
                    fixture.session().csrfToken(),
                    Map.of("Idempotency-Key", "idem-" + UUID.randomUUID()));
            assertThat(stop.status()).isEqualTo(202);
            assertThat(WORKER.lastCancelRequest().path("reason").asText()).isEqualTo("USER_CANCELLED");

            TestSseReader.Frame stopped = reader.next();
            assertThat(stopped).isNotNull();
            assertThat(stopped.event()).isEqualTo("riccon.chat.stopped.v1");
            JsonNode stoppedData = MAPPER.readTree(stopped.data());
            assertThat(stoppedData.path("data").path("reason").asText()).isEqualTo("USER_CANCELLED");
            assertThat(stoppedData.path("messageId").asText())
                    .isEqualTo(messageId(fixture.tenant(), fixture.conversationId(), "ASSISTANT"));
            assertThat(reader.next()).isNull();

            MessageState assistant = messageState(fixture.tenant(), fixture.conversationId(), "ASSISTANT");
            assertThat(assistant.status()).isEqualTo("STOPPED");
            assertThat(assistant.content()).isEqualTo("部分内容");
            String runStatus = jdbcClient
                    .sql("SELECT status FROM agent_run WHERE tenant_id = :tenantId AND id = :runId")
                    .param("tenantId", fixture.tenant())
                    .param("runId", BinaryUuids.toBytes(UUID.fromString(runId)))
                    .query(String.class)
                    .single();
            assertThat(runStatus).isEqualTo("CANCELLED");
            assertThat(auditRows("message.stopped"))
                    .filteredOn(row -> fixture.tenant().equals(row.tenantId()))
                    .anySatisfy(row -> assertThat(row.details()).contains(runId).contains("USER_CANCELLED"));
        }
    }

    /** 停止已结束运行返回 409；未知运行返回 404。 */
    @Test
    @DisplayName("停止终态运行返回 409，未知运行返回 404")
    void stopTerminalOrUnknownRun_returnsConflictOrNotFound() throws Exception {
        Fixture fixture = fixture();
        ApiTestClient.ApiResponse response = send(fixture, "很快完成", "client-" + UUID.randomUUID(), Map.of());
        String runId = runIdOf(response.body());

        ApiTestClient.ApiResponse stopped = client.post(
                "/api/v1/conversations/" + fixture.conversationId() + "/stop",
                client.jsonBody("runId", runId),
                fixture.session().sessionCookie(),
                fixture.session().csrfToken(),
                Map.of("Idempotency-Key", "idem-" + UUID.randomUUID()));
        assertThat(stopped.status()).isEqualTo(409);
        assertThat(client.json(stopped).path("code").asText()).isEqualTo("RICCON-CONFLICT-409");
        assertThat(WORKER.cancelRequestCount()).isZero();

        ApiTestClient.ApiResponse unknown = client.post(
                "/api/v1/conversations/" + fixture.conversationId() + "/stop",
                client.jsonBody("runId", UUID.randomUUID()),
                fixture.session().sessionCookie(),
                fixture.session().csrfToken(),
                Map.of("Idempotency-Key", "idem-" + UUID.randomUUID()));
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(client.json(unknown).path("code").asText()).isEqualTo("RICCON-TENANT-404");
    }

    /** 空闲超时：API 合成 failed，保留内容并取消运行。 */
    @Test
    @DisplayName("空闲超时发送 failed 并保留内容")
    void idleTimeout_sendsFailedAndKeepsContent() throws Exception {
        Fixture fixture = fixture();
        WORKER.reset(Scenario.HANG);
        long startedAt = System.nanoTime();

        ApiTestClient.ApiResponse response = send(fixture, "会卡住吗", "client-" + UUID.randomUUID(), Map.of());
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertThat(response.status()).isEqualTo(200);
        // 空闲上限 5 秒；允许看门狗检查周期与调度误差，但必须明显小于 mock 的 10 秒挂起。
        assertThat(elapsedMs).isBetween(4_000L, 9_000L);
        List<TestSseReader.Frame> frames = readFrames(response.body());
        assertThat(frames)
                .extracting(TestSseReader.Frame::event)
                .containsExactly("riccon.chat.started.v1", "riccon.chat.delta.v1", "riccon.chat.failed.v1");
        JsonNode failed = MAPPER.readTree(frames.get(2).data());
        assertThat(failed.path("data").path("code").asText()).isEqualTo("RICCON-CHAT-504");

        MessageState assistant = messageState(fixture.tenant(), fixture.conversationId(), "ASSISTANT");
        assertThat(assistant.content()).isEqualTo("部分内容");
        assertThat(assistant.status()).isEqualTo("FAILED");
        assertThat(assistant.errorCode()).isEqualTo("RICCON-CHAT-504");
    }

    /** 跨租户发送与停止统一 404，且不调用 Worker。 */
    @Test
    @DisplayName("跨租户发送与停止返回 404")
    void crossTenantSendAndStop_returnNotFound() {
        Fixture tenantA = fixture();
        Fixture tenantB = fixture();

        ApiTestClient.ApiResponse send = client.post(
                "/api/v1/conversations/" + tenantA.conversationId() + "/messages",
                client.jsonBody("content", "越权", "clientMessageId", "client-" + UUID.randomUUID()),
                tenantB.session().sessionCookie(),
                tenantB.session().csrfToken(),
                Map.of("Idempotency-Key", "idem-" + UUID.randomUUID()));
        assertThat(send.status()).isEqualTo(404);
        assertThat(client.json(send).path("code").asText()).isEqualTo("RICCON-TENANT-404");
        assertThat(WORKER.chatRequestCount()).isZero();

        ApiTestClient.ApiResponse stop = client.post(
                "/api/v1/conversations/" + tenantA.conversationId() + "/stop",
                client.jsonBody("runId", UUID.randomUUID()),
                tenantB.session().sessionCookie(),
                tenantB.session().csrfToken(),
                Map.of("Idempotency-Key", "idem-" + UUID.randomUUID()));
        assertThat(stop.status()).isEqualTo(404);
        assertThat(WORKER.cancelRequestCount()).isZero();
    }

    /** 发送参数校验；非法请求不落库也不调用 Worker。 */
    @Test
    @DisplayName("发送参数与附件校验")
    void sendValidation_rejectsInvalidPayload() {
        Fixture fixture = fixture();
        String path = "/api/v1/conversations/" + fixture.conversationId() + "/messages";

        assertThat(postMessage(fixture, path, client.jsonBody("content", " ", "clientMessageId", "client-12345678"))
                        .status())
                .isEqualTo(400);
        assertThat(postMessage(fixture, path, client.jsonBody("content", "x", "clientMessageId", "short"))
                        .status())
                .isEqualTo(400);
        assertThat(postMessage(
                                fixture,
                                path,
                                client.jsonBody(
                                        "content",
                                        "x",
                                        "clientMessageId",
                                        "client-12345678",
                                        "fileIds",
                                        List.of(UUID.randomUUID())))
                        .status())
                .isEqualTo(400);
        ApiTestClient.ApiResponse tooMany = client.post(
                path,
                client.jsonBody(
                        "content",
                        "x",
                        "clientMessageId",
                        "client-12345678",
                        "fileIds",
                        List.of(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID())),
                fixture.session().sessionCookie(),
                fixture.session().csrfToken(),
                Map.of("Idempotency-Key", "idem-" + UUID.randomUUID()));
        assertThat(tooMany.status()).isEqualTo(400);
        ApiTestClient.ApiResponse badIdempotency = client.post(
                path,
                client.jsonBody("content", "x", "clientMessageId", "client-12345678"),
                fixture.session().sessionCookie(),
                fixture.session().csrfToken(),
                Map.of("Idempotency-Key", "short"));
        assertThat(badIdempotency.status()).isEqualTo(400);

        assertThat(WORKER.chatRequestCount()).isZero();
        Long messages = jdbcClient
                .sql("SELECT COUNT(*) FROM message WHERE tenant_id = :tenantId AND conversation_id = :conversationId")
                .param("tenantId", fixture.tenant())
                .param("conversationId", BinaryUuids.toBytes(UUID.fromString(fixture.conversationId())))
                .query(Long.class)
                .single();
        assertThat(messages).isZero();
    }

    /** 消息列表 beforeSeq 翻页、引用与详情返回。 */
    @Test
    @DisplayName("消息列表按 seq 翻页并携带引用")
    void messageListPagesByBeforeSeq() throws Exception {
        Fixture fixture = fixture();
        assertThat(send(fixture, "第一问", "client-" + UUID.randomUUID(), Map.of()).status())
                .isEqualTo(200);
        assertThat(send(fixture, "第二问", "client-" + UUID.randomUUID(), Map.of()).status())
                .isEqualTo(200);

        JsonNode latest = listMessages(fixture, "?size=2");
        assertThat(latest.path("total").asLong()).isEqualTo(4);
        assertThat(latest.path("items")).hasSize(2);
        assertThat(latest.path("items").get(0).path("seq").asLong()).isEqualTo(3);
        assertThat(latest.path("items").get(1).path("seq").asLong()).isEqualTo(4);

        JsonNode before = listMessages(fixture, "?beforeSeq=4&size=2");
        assertThat(before.path("items")).hasSize(2);
        assertThat(before.path("items").get(0).path("seq").asLong()).isEqualTo(2);
        assertThat(before.path("items").get(0).path("role").asText()).isEqualTo("ASSISTANT");
        assertThat(before.path("items").get(0).path("citations")).hasSize(1);
        assertThat(before.path("items").get(1).path("seq").asLong()).isEqualTo(3);

        ApiTestClient.ApiResponse invalid = client.get(
                "/api/v1/conversations/" + fixture.conversationId() + "/messages?beforeSeq=0",
                fixture.session().sessionCookie(),
                Map.of());
        assertThat(invalid.status()).isEqualTo(400);
    }

    /** 长内容自动生成标题且后续消息不覆盖。 */
    @Test
    @DisplayName("首条消息自动生成标题")
    void firstMessageGeneratesTitle() {
        Fixture fixture = fixture();
        String content = "这是一个超过二十个字符的很长的问题内容用于标题";
        assertThat(send(fixture, content, "client-" + UUID.randomUUID(), Map.of())
                        .status())
                .isEqualTo(200);
        assertThat(send(fixture, "第二个问题", "client-" + UUID.randomUUID(), Map.of())
                        .status())
                .isEqualTo(200);

        JsonNode conversation = getConversation(fixture);
        assertThat(conversation.path("title").asText()).isEqualTo(content.substring(0, 20) + "…");
    }

    /**
     * 为 mock Worker 请求插入模型调用记录，模拟 Worker 在首字前写 model_invocation。
     *
     * @param request mock Worker 收到的请求
     */
    private void prepareModelInvocation(JsonNode request) {
        ensureModelCatalog();
        jdbcClient
                .sql("""
                        INSERT INTO model_invocation (
                            tenant_id, id, run_id, conversation_id, message_id, model_id,
                            model_key_snapshot, purpose, status, trace_id, started_at
                        ) VALUES (
                            :tenantId, :id, :runId, :conversationId, NULL, :modelId,
                            'fake-model', 'CHAT', 'STARTED', 'mock-trace', NOW(6)
                        )
                        """)
                .param("tenantId", request.path("tenantId").asText())
                .param("id", BinaryUuids.toBytes(UUID.randomUUID()))
                .param(
                        "runId",
                        BinaryUuids.toBytes(
                                UUID.fromString(request.path("runId").asText())))
                .param(
                        "conversationId",
                        BinaryUuids.toBytes(
                                UUID.fromString(request.path("conversationId").asText())))
                .param("modelId", BinaryUuids.toBytes(MockWorkerServer.MODEL_ID))
                .update();
    }

    /** 确保模型目录存在。 */
    private void ensureModelCatalog() {
        jdbcClient.sql("""
                        INSERT IGNORE INTO model_provider (id, code, name, protocol, base_url, status)
                        VALUES (:id, 'mock-openai', 'Mock', 'OPENAI_COMPATIBLE', 'http://127.0.0.1:1', 'ACTIVE')
                        """).param("id", BinaryUuids.toBytes(PROVIDER_ID)).update();
        jdbcClient
                .sql("""
                        INSERT IGNORE INTO model
                            (id, provider_id, model_key, display_name, context_window, max_output_tokens, status)
                        VALUES (:id, :providerId, 'fake-model', 'Fake Model', 8192, 1024, 'ACTIVE')
                        """)
                .param("id", BinaryUuids.toBytes(MockWorkerServer.MODEL_ID))
                .param("providerId", BinaryUuids.toBytes(PROVIDER_ID))
                .update();
    }

    /**
     * 创建租户、成员、个人空间与会话。
     *
     * @return 测试夹具
     */
    private Fixture fixture() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("chat");
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
        String conversationId = client.json(created).path("id").asText();
        String userId = member.body().path("user").path("id").asText();
        return new Fixture(tenant, member, workspaceId, conversationId, userId);
    }

    /**
     * 读取个人空间。
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
     * 发送消息。
     *
     * @param fixture 测试夹具
     * @param content 内容
     * @param clientMessageId 幂等键
     * @param extraHeaders 额外请求头
     * @return 响应
     */
    private ApiTestClient.ApiResponse send(
            Fixture fixture, String content, String clientMessageId, Map<String, String> extraHeaders) {
        Map<String, String> headers = new LinkedHashMap<>(extraHeaders);
        headers.putIfAbsent("Idempotency-Key", "idem-" + UUID.randomUUID());
        return client.post(
                "/api/v1/conversations/" + fixture.conversationId() + "/messages",
                client.jsonBody("content", content, "clientMessageId", clientMessageId),
                fixture.session().sessionCookie(),
                fixture.session().csrfToken(),
                headers);
    }

    /**
     * 用预构造的请求体发送消息。
     *
     * @param fixture 夹具
     * @param path 路径
     * @param body JSON 体
     * @return 响应
     */
    private ApiTestClient.ApiResponse postMessage(Fixture fixture, String path, String body) {
        return client.post(
                path,
                body,
                fixture.session().sessionCookie(),
                fixture.session().csrfToken(),
                Map.of("Idempotency-Key", "idem-" + UUID.randomUUID()));
    }

    /**
     * 读取会话详情。
     *
     * @param fixture 夹具
     * @return 详情 JSON
     */
    private JsonNode getConversation(Fixture fixture) {
        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/conversations/" + fixture.conversationId(),
                fixture.session().sessionCookie(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 读取消息分页。
     *
     * @param fixture 夹具
     * @param query 查询串
     * @return JSON
     */
    private JsonNode listMessages(Fixture fixture, String query) {
        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/conversations/" + fixture.conversationId() + "/messages" + query,
                fixture.session().sessionCookie(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 解析 SSE 响应体。
     *
     * @param body 原始 SSE
     * @return 帧列表
     * @throws IOException 解析失败
     */
    private static List<TestSseReader.Frame> readFrames(String body) throws IOException {
        List<TestSseReader.Frame> frames = new ArrayList<>();
        try (TestSseReader reader =
                new TestSseReader(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))) {
            TestSseReader.Frame frame;
            while ((frame = reader.next()) != null) {
                frames.add(frame);
            }
        }
        return frames;
    }

    /**
     * 从 SSE 响应体读取 runId。
     *
     * @param body SSE
     * @return runId
     * @throws IOException 解析失败
     */
    private static String runIdOf(String body) throws IOException {
        return MAPPER.readTree(readFrames(body).get(0).data()).path("runId").asText();
    }

    /**
     * 查询消息 ID。
     *
     * @param tenant 租户
     * @param conversationId 会话
     * @param role 角色
     * @return 消息 ID
     */
    private String messageId(String tenant, String conversationId, String role) {
        byte[] id = jdbcClient
                .sql("""
                        SELECT id FROM message
                         WHERE tenant_id = :tenantId AND conversation_id = :conversationId AND role = :role
                         ORDER BY seq DESC LIMIT 1
                        """)
                .param("tenantId", tenant)
                .param("conversationId", BinaryUuids.toBytes(UUID.fromString(conversationId)))
                .param("role", role)
                .query(byte[].class)
                .single();
        return BinaryUuids.fromBytes(id).toString();
    }

    /**
     * 查询消息状态与内容。
     *
     * @param tenant 租户
     * @param conversationId 会话
     * @param role 角色
     * @return 消息状态
     */
    private MessageState messageState(String tenant, String conversationId, String role) {
        return jdbcClient
                .sql("""
                        SELECT status, content, run_id, model_id, error_code FROM message
                         WHERE tenant_id = :tenantId AND conversation_id = :conversationId AND role = :role
                         ORDER BY seq DESC LIMIT 1
                        """)
                .param("tenantId", tenant)
                .param("conversationId", BinaryUuids.toBytes(UUID.fromString(conversationId)))
                .param("role", role)
                .query((rs, rowNum) -> new MessageState(
                        rs.getString("status"),
                        rs.getString("content"),
                        rs.getBytes("run_id"),
                        rs.getBytes("model_id"),
                        rs.getString("error_code")))
                .single();
    }

    /**
     * 测试夹具。
     *
     * @param tenant 租户
     * @param session 成员会话
     * @param workspaceId 个人空间
     * @param conversationId 会话
     * @param userId 成员 ID
     */
    private record Fixture(
            String tenant, LoginSession session, String workspaceId, String conversationId, String userId) {}

    /**
     * 消息状态快照。
     *
     * @param status 状态
     * @param content 内容
     * @param runId 运行 ID 字节
     * @param modelId 模型 ID 字节
     * @param errorCode 错误码
     */
    private record MessageState(String status, String content, byte[] runId, byte[] modelId, String errorCode) {}
}
