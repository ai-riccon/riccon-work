package com.riccon.worker.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.riccon.platform.domain.contract.HttpHeaders;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.support.TestCredentials;
import com.riccon.worker.support.TestModelStub;
import com.riccon.worker.support.TestServiceToken;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 内部对话流：SSE 字段、租户绑定、失败语义与取消。 */
class ChatStreamIntegrationTest extends WorkerIntegrationTestBase {

    /** 等待 SSE 异步终态的上限；超时按测试失败处理，避免调度异常时永久挂起。 */
    private static final long ASYNC_RESULT_TIMEOUT_MILLIS = 30_000;

    /** MockMvc。 */
    @Autowired
    private MockMvc mockMvc;

    /** JSON 解析。 */
    @Autowired
    private ObjectMapper objectMapper;

    /** 模型桩。 */
    private TestModelStub stub;

    /** 启动模型桩；恢复 M04 全局模型基线（旧模型停用、路由链/开关复位）。 */
    @BeforeEach
    void setUp() {
        resetModelGatewayState();
        // cancel_waitingApprovalRun 会创建白名单 server code 的 MCP 目录，避免跨测试唯一键冲突。
        resetMcpCatalog();
        stub = new TestModelStub();
    }

    /** 关闭模型桩。 */
    @AfterEach
    void tearDown() {
        stub.close();
    }

    /** 正常对话输出 started/citation/delta/completed，并推进运行与调用记录。 */
    @Test
    void stream_happyPath_emitsContractEvents() throws Exception {
        ChatFixture fixture = chatFixture("tenant-a", "市场问题");

        MvcResult result = performStream(fixture, fixture.token());
        String body = normalize(result.getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(body).contains("event: riccon.chat.started.v1");
        assertThat(body).contains("event: riccon.chat.citation.v1");
        assertThat(body).contains("event: riccon.chat.delta.v1");
        assertThat(body).contains("event: riccon.chat.completed.v1");
        assertThat(body).doesNotContain("你是 Riccon").doesNotContain("UNTRUSTED_DATA");
        JsonNode started = firstData(body, "riccon.chat.started.v1");
        assertThat(started.path("tenantId").asText()).isEqualTo(fixture.tenantId());
        assertThat(started.path("runId").asText()).isEqualTo(fixture.runId().toString());
        assertThat(started.path("messageId").asText()).isNotBlank();
        assertThat(started.path("data").path("modelKey").asText()).isEqualTo("fake-model");
        JsonNode completed = firstData(body, "riccon.chat.completed.v1");
        assertThat(completed.path("data").path("finishReason").asText()).isEqualTo("stop");
        assertThat(completed.path("data").path("firstTokenMs").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(runStatus(fixture.runId())).isEqualTo("SUCCEEDED");
        assertThat(invocationStatus(fixture.runId())).isEqualTo("SUCCEEDED");
        assertThat(auditCount("model.invocation.started", fixture.tenantId())).isEqualTo(1);
        assertThat(auditCount("model.invocation.completed", fixture.tenantId())).isEqualTo(1);
    }

    /**
     * F-003：聊天路径从 AgentResultEvent 取 provider usage，按固定价格写入 PRICED 成本快照。
     *
     * <p>固定样例：prompt=12、completion=8、输入价 3.000000、输出价 6.000000 →
     * (12×3 + 8×6) / 1_000_000 = 0.000084；首字延迟必须保留。
     */
    @Test
    void stream_happyPath_persistsPricedInvocationFromUsage() throws Exception {
        ChatFixture fixture = chatFixture("tenant-a", "市场问题");
        setModelPrices();

        MvcResult result = performStream(fixture, fixture.token());
        assertThat(normalize(result.getResponse().getContentAsString(StandardCharsets.UTF_8)))
                .contains("event: riccon.chat.completed.v1");

        Map<String, Object> row = invocationRow(fixture.runId());
        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
        assertThat(row.get("costStatus")).isEqualTo("PRICED");
        assertThat(row.get("promptTokens")).isEqualTo(12);
        assertThat(row.get("completionTokens")).isEqualTo(8);
        assertThat(row.get("inputPrice")).isEqualTo(new BigDecimal("3.000000"));
        assertThat(row.get("outputPrice")).isEqualTo(new BigDecimal("6.000000"));
        assertThat(row.get("costAmount")).isEqualTo(new BigDecimal("0.000084"));
        assertThat(row.get("priceCurrency")).isEqualTo("CNY");
        assertThat(row.get("pricingVersion")).isEqualTo("v1");
        assertThat((Integer) row.get("firstTokenMs")).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(row.get("costDay")).isEqualTo(expectedDay((Instant) row.get("startedAt")));
    }

    /** 缺少服务令牌返回 ProblemDetail 401。 */
    @Test
    void stream_missingToken_returnsProblem401() throws Exception {
        ChatFixture fixture = chatFixture("tenant-a", "问题");

        MvcResult result = mockMvc.perform(post("/internal/v1/chat/streams")
                        .header(HttpHeaders.TRACE_ID, "trace-x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(fixture)))
                .andExpect(status().isUnauthorized())
                .andReturn();

        JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-AUTH-401");
        assertThat(problem.path("traceId").asText()).isEqualTo("trace-x");
    }

    /** 令牌租户与请求体不一致返回 400。 */
    @Test
    void stream_tenantMismatch_returns400() throws Exception {
        ChatFixture fixture = chatFixture("tenant-a", "问题");
        Map<String, Object> body = requestBody(fixture);
        body.put("tenantId", "tenant-b");

        MvcResult result = mockMvc.perform(post("/internal/v1/chat/streams")
                        .header(HttpHeaders.SERVICE_TOKEN, fixture.token())
                        .header(HttpHeaders.TRACE_ID, "trace-x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(objectMapper
                        .readTree(result.getResponse().getContentAsString())
                        .path("code")
                        .asText())
                .isEqualTo("RICCON-CHAT-400");
    }

    /** 跨租户运行统一返回 404 并写 denied 审计。 */
    @Test
    void stream_runFromOtherTenant_returns404AndAudits() throws Exception {
        ChatFixture fixture = chatFixture("tenant-a", "问题");
        UUID foreignRun = foreignChatRun();

        Map<String, Object> body = requestBody(fixture);
        body.put("runId", foreignRun.toString());
        String foreignToken = TestServiceToken.mint(fixture.tenantId(), fixture.userId(), foreignRun);

        MvcResult result = mockMvc.perform(post("/internal/v1/chat/streams")
                        .header(HttpHeaders.SERVICE_TOKEN, foreignToken)
                        .header(HttpHeaders.TRACE_ID, "trace-x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(objectMapper
                        .readTree(result.getResponse().getContentAsString())
                        .path("code")
                        .asText())
                .isEqualTo("RICCON-TENANT-404");
        assertThat(auditCount("tenant.access.denied", fixture.tenantId())).isEqualTo(1);
    }

    /** 首字前失败返回 ProblemDetail，运行与调用进入 FAILED。 */
    @Test
    void stream_modelFailsBeforeFirstToken_returnsProblem() throws Exception {
        stub.forceStatus(500);
        ChatFixture fixture = chatFixture("tenant-a", "问题");

        MvcResult result = mockMvc.perform(post("/internal/v1/chat/streams")
                        .header(HttpHeaders.SERVICE_TOKEN, fixture.token())
                        .header(HttpHeaders.TRACE_ID, "trace-x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(fixture)))
                .andExpect(status().isInternalServerError())
                .andReturn();

        JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-MODEL-503");
        assertThat(runStatus(fixture.runId())).isEqualTo("FAILED");
        assertThat(invocationStatus(fixture.runId())).isEqualTo("FAILED");
        assertThat(auditCount("model.invocation.failed", fixture.tenantId())).isEqualTo(1);
    }

    /** 首字后失败发送 failed 事件并保留已生成内容。 */
    @Test
    void stream_modelFailsAfterFirstToken_emitsFailedEvent() throws Exception {
        // 桩会把两个首字分片和错误帧一次性写出；低核机器上 Reactor 的缓冲可能先投递错误帧，
        // 导致 Worker 在首字前就判定失败（CI shard 2 的实际失败形态）。先让桩在首个内容帧后
        // 暂停，等首段请求返回（open() 已消费首字并启动异步）再由 performStream 放开闸门，
        // 使“首字后失败”在任意调度顺序下都确定发生，不依赖 sleep 或重试。
        stub.errorAfterChunk();
        stub.holdAfterFirstContent();
        ChatFixture fixture = chatFixture("tenant-a", "问题");

        MvcResult result = performStream(fixture, fixture.token());
        String body = normalize(result.getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(body).contains("event: riccon.chat.delta.v1");
        assertThat(body).contains("event: riccon.chat.failed.v1");
        JsonNode failed = firstData(body, "riccon.chat.failed.v1");
        assertThat(failed.path("data").path("code").asText()).isEqualTo("RICCON-MODEL-502");
        assertThat(failed.path("data").path("retryable").asBoolean()).isTrue();
        assertThat(failed.path("data").path("message").asText())
                .isEqualTo(com.riccon.worker.model.domain.ModelException.STREAM_INTERRUPTED_MESSAGE);
        assertThat(runStatus(fixture.runId())).isEqualTo("FAILED");
        // 首字后失败：调用记录保留首字延迟；失败审计带 emitted=true；不产生第二次模型调用。
        assertThat(jdbcClient
                        .sql(
                                "SELECT first_token_ms FROM model_invocation WHERE tenant_id = :tenantId AND run_id = :runId")
                        .param("tenantId", fixture.tenantId())
                        .param("runId", UuidBinary.toBytes(fixture.runId()))
                        .query(Long.class)
                        .single())
                .isNotNull();
        assertThat(jdbcClient
                        .sql("""
                                SELECT COUNT(*) FROM model_invocation_attempt
                                 WHERE tenant_id = :tenantId AND invocation_id =
                                       (SELECT id FROM model_invocation WHERE tenant_id = :tenantId AND run_id = :runId)
                                """)
                        .param("tenantId", fixture.tenantId())
                        .param("runId", UuidBinary.toBytes(fixture.runId()))
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbcClient
                        .sql("""
                                SELECT details FROM audit_event
                                 WHERE action = 'model.invocation.failed' AND tenant_id = :tenantId
                                """)
                        .param("tenantId", fixture.tenantId())
                        .query(String.class)
                        .single())
                .contains("\"emitted\": true");
        assertThat(stub.requestCount()).isEqualTo(1);
    }

    /** 取消等待审批的报告运行：202、运行取消、审批取消。 */
    @Test
    void cancel_waitingApprovalRun_cancelsRunAndApproval() throws Exception {
        String tenant = uniqueTenant("tenant-a");
        UUID user = seedTenant(tenant);
        UUID workspace = data.createWorkspace(tenant, user);
        UUID toolId = createTool();
        UUID taskId = data.createResearchTask(tenant, workspace, user, "主题", toolId);
        UUID runId = data.createResearchRun(tenant, taskId, user);
        UUID approvalId = createPendingApproval(tenant, runId, taskId, user, toolId);
        jdbcClient
                .sql("UPDATE agent_run SET status = 'WAITING_APPROVAL' WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(runId))
                .update();
        String token = TestServiceToken.mint(tenant, user, runId);

        mockMvc.perform(post("/internal/v1/runs/" + runId + "/cancel")
                        .header(HttpHeaders.SERVICE_TOKEN, token)
                        .header(HttpHeaders.TRACE_ID, "trace-cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"USER_CANCELLED\"}"))
                .andExpect(status().isAccepted());

        assertThat(runStatus(runId)).isEqualTo("CANCELLED");
        assertThat(approvalStatus(approvalId)).isEqualTo("CANCELLED");
    }

    /**
     * 执行 SSE 请求并完成异步分发。
     *
     * <p>不能只断言 {@code request().asyncStarted()}：它只反映断言瞬间的异步状态，不等待
     * SseEmitter 结束，也无法区分“异步未启动”和“控制器已同步返回 ProblemDetail”。
     * {@link MvcResult#getAsyncResult(long)} 会阻塞到 DeferredResult 完成并触发 ASYNC dispatch，
     * 再执行 {@code asyncDispatch}，SSE 终态在任何线程调度顺序下都确定可达，且失败会被明确报告。
     *
     * @param fixture 对话夹具
     * @param token 服务令牌
     * @return 完成异步分发后的结果
     * @throws Exception 请求或等待失败
     */
    private MvcResult performStream(ChatFixture fixture, String token) throws Exception {
        MvcResult pending = mockMvc.perform(post("/internal/v1/chat/streams")
                        .header(HttpHeaders.SERVICE_TOKEN, token)
                        .header(HttpHeaders.TRACE_ID, "trace-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(fixture)))
                .andReturn();
        // 首段请求返回时 open() 已消费首字并启动异步；未使用暂停闸门时 countDown 是空操作。
        stub.releaseContent();
        pending.getAsyncResult(ASYNC_RESULT_TIMEOUT_MILLIS);
        return mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andReturn();
    }

    /** 已成功运行取消返回 409。 */
    @Test
    void cancel_terminalRun_returns409() throws Exception {
        ChatFixture fixture = chatFixture("tenant-a", "问题");
        jdbcClient
                .sql("UPDATE agent_run SET status = 'SUCCEEDED' WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", fixture.tenantId())
                .param("id", UuidBinary.toBytes(fixture.runId()))
                .update();

        mockMvc.perform(post("/internal/v1/runs/" + fixture.runId() + "/cancel")
                        .header(HttpHeaders.SERVICE_TOKEN, fixture.token())
                        .header(HttpHeaders.TRACE_ID, "trace-cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
    }

    /** 跨租户取消统一 404 并写 denied 审计。 */
    @Test
    void cancel_unknownRun_returns404AndAudits() throws Exception {
        ChatFixture fixture = chatFixture("tenant-a", "问题");
        UUID unknown = UUID.randomUUID();
        String token = TestServiceToken.mint(fixture.tenantId(), fixture.userId(), unknown);

        mockMvc.perform(post("/internal/v1/runs/" + unknown + "/cancel")
                        .header(HttpHeaders.SERVICE_TOKEN, token)
                        .header(HttpHeaders.TRACE_ID, "trace-cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
        assertThat(auditCount("tenant.access.denied", fixture.tenantId())).isEqualTo(1);
    }

    /** 非法取消原因返回 400。 */
    @Test
    void cancel_invalidReason_returns400() throws Exception {
        ChatFixture fixture = chatFixture("tenant-a", "问题");

        mockMvc.perform(post("/internal/v1/runs/" + fixture.runId() + "/cancel")
                        .header(HttpHeaders.SERVICE_TOKEN, fixture.token())
                        .header(HttpHeaders.TRACE_ID, "trace-cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"OTHER\"}"))
                .andExpect(status().isBadRequest());
    }

    /** 非 CHAT 运行拒绝流式请求。 */
    @Test
    void stream_nonChatRun_returns403() throws Exception {
        String tenant = uniqueTenant("tenant-a");
        UUID user = seedTenant(tenant);
        UUID workspace = data.createWorkspace(tenant, user);
        UUID taskId = data.createResearchTask(tenant, workspace, user, "主题", null);
        UUID runId = data.createResearchRun(tenant, taskId, user);
        String token = TestServiceToken.mint(tenant, user, runId);
        Map<String, Object> body = Map.of(
                "runId", runId.toString(),
                "tenantId", tenant,
                "userId", user.toString(),
                "conversationId", UUID.randomUUID().toString(),
                "userMessageId", UUID.randomUUID().toString());

        mockMvc.perform(post("/internal/v1/chat/streams")
                        .header(HttpHeaders.SERVICE_TOKEN, token)
                        .header(HttpHeaders.TRACE_ID, "trace-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    /** 终态运行拒绝重复流式请求。 */
    @Test
    void stream_terminalRun_returns409() throws Exception {
        ChatFixture fixture = chatFixture("tenant-a", "问题");
        jdbcClient
                .sql("UPDATE agent_run SET status = 'CANCELLED' WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", fixture.tenantId())
                .param("id", UuidBinary.toBytes(fixture.runId()))
                .update();

        mockMvc.perform(post("/internal/v1/chat/streams")
                        .header(HttpHeaders.SERVICE_TOKEN, fixture.token())
                        .header(HttpHeaders.TRACE_ID, "trace-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(fixture)))
                .andExpect(status().isConflict());
    }

    /** 用户消息不存在返回 404。 */
    @Test
    void stream_missingUserMessage_returns404() throws Exception {
        ChatFixture fixture = chatFixture("tenant-a", "问题");
        Map<String, Object> body = requestBody(fixture);
        body.put("userMessageId", UUID.randomUUID().toString());

        mockMvc.perform(post("/internal/v1/chat/streams")
                        .header(HttpHeaders.SERVICE_TOKEN, fixture.token())
                        .header(HttpHeaders.TRACE_ID, "trace-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    /** 文件数超过上限返回 400。 */
    @Test
    void stream_tooManyFiles_returns400() throws Exception {
        ChatFixture fixture = chatFixture("tenant-a", "问题");
        Map<String, Object> body = requestBody(fixture);
        body.put(
                "fileIds",
                java.util.stream.IntStream.range(0, 6)
                        .mapToObj(index -> UUID.randomUUID().toString())
                        .toList());

        mockMvc.perform(post("/internal/v1/chat/streams")
                        .header(HttpHeaders.SERVICE_TOKEN, fixture.token())
                        .header(HttpHeaders.TRACE_ID, "trace-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    /** 没有可用模型返回 500 且运行失败。 */
    @Test
    void stream_noModel_returns500() throws Exception {
        ChatFixture fixture = chatFixture("tenant-a", "问题", false);

        mockMvc.perform(post("/internal/v1/chat/streams")
                        .header(HttpHeaders.SERVICE_TOKEN, fixture.token())
                        .header(HttpHeaders.TRACE_ID, "trace-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(fixture)))
                .andExpect(status().isInternalServerError());

        assertThat(runStatus(fixture.runId())).isEqualTo("FAILED");
    }

    /** 模型无可见回答时按首字前失败处理。 */
    @Test
    void stream_emptyAnswer_returns500() throws Exception {
        stub.emptyAnswer();
        ChatFixture fixture = chatFixture("tenant-a", "问题");

        MvcResult result = mockMvc.perform(post("/internal/v1/chat/streams")
                        .header(HttpHeaders.SERVICE_TOKEN, fixture.token())
                        .header(HttpHeaders.TRACE_ID, "trace-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(fixture)))
                .andExpect(status().isInternalServerError())
                .andReturn();

        assertThat(objectMapper
                        .readTree(result.getResponse().getContentAsString())
                        .path("code")
                        .asText())
                .isEqualTo("RICCON-MODEL-502");
        assertThat(runStatus(fixture.runId())).isEqualTo("FAILED");
    }

    /** 运行发起人已禁用时拒绝流式执行并写审计。 */
    @Test
    void stream_disabledUser_returns403() throws Exception {
        ChatFixture fixture = chatFixture("tenant-a", "问题");
        jdbcClient
                .sql("UPDATE app_user SET status = 'DISABLED' WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", fixture.tenantId())
                .param("id", UuidBinary.toBytes(fixture.userId()))
                .update();

        mockMvc.perform(post("/internal/v1/chat/streams")
                        .header(HttpHeaders.SERVICE_TOKEN, fixture.token())
                        .header(HttpHeaders.TRACE_ID, "trace-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(fixture)))
                .andExpect(status().isForbidden());

        assertThat(auditCount("user.access.denied", fixture.tenantId())).isEqualTo(1);
    }

    /** 构造对话夹具；每个测试生成唯一租户，避免业务数据跨测试残留。 */
    private ChatFixture chatFixture(String tenantPrefix, String question) {
        return chatFixture(tenantPrefix, question, true);
    }

    /** 构造对话夹具，可选是否创建模型目录。 */
    private ChatFixture chatFixture(String tenantPrefix, String question, boolean withModel) {
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
        if (withModel) {
            data.createModelCatalog(stub.url(), TestCredentials.API_KEY);
        }
        String token = TestServiceToken.mint(tenant, user, runId);
        return new ChatFixture(tenant, user, conversation, userMessage, runId, token);
    }

    /** 创建另一租户的运行。 */
    private UUID foreignChatRun() {
        String tenant = uniqueTenant("tenant-b");
        UUID user = seedTenant(tenant);
        UUID workspace = data.createWorkspace(tenant, user);
        UUID conversation = data.createConversation(tenant, workspace, user);
        data.createMessage(tenant, conversation, 1, "USER", "foreign");
        return data.createChatRun(tenant, conversation, user);
    }

    /** 创建工具。 */
    private UUID createTool() {
        UUID server = data.createMcpServer("riccon-builtin", "unused");
        return data.createMcpTool(server, "echo", "{\"type\":\"object\"}");
    }

    /** 创建待审批。 */
    private UUID createPendingApproval(String tenant, UUID runId, UUID taskId, UUID user, UUID toolId) {
        UUID approval = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO approval
                            (tenant_id, id, research_task_id, run_id, stage, attempt_no, status,
                             action_type, mcp_tool_id, tool_name, params_preview, params_sha256, requested_by)
                        VALUES
                            (:tenantId, :id, :taskId, :runId, 'APPROVAL', 1, 'PENDING',
                             'MCP_TOOL_CALL', :toolId, 'echo', '{}', :sha, :userId)
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(approval))
                .param("taskId", UuidBinary.toBytes(taskId))
                .param("runId", UuidBinary.toBytes(runId))
                .param("toolId", UuidBinary.toBytes(toolId))
                .param("sha", sha256("{}"))
                .param("userId", UuidBinary.toBytes(user))
                .update();
        return approval;
    }

    /** 请求体。 */
    private Map<String, Object> requestBody(ChatFixture fixture) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", fixture.runId().toString());
        body.put("tenantId", fixture.tenantId());
        body.put("userId", fixture.userId().toString());
        body.put("conversationId", fixture.conversationId().toString());
        body.put("userMessageId", fixture.userMessageId().toString());
        return body;
    }

    /** 请求 JSON。 */
    private String requestJson(ChatFixture fixture) throws Exception {
        return objectMapper.writeValueAsString(requestBody(fixture));
    }

    /** SseEmitter 输出 `field:value`，测试统一规范化为带空格的 SSE 帧便于断言。 */
    private static String normalize(String body) {
        return body.replace("id:", "id: ").replace("event:", "event: ").replace("data:", "data: ");
    }

    /** 提取指定类型第一个 data JSON。 */
    private JsonNode firstData(String body, String eventType) {
        String marker = "event: " + eventType + "\n";
        int index = body.indexOf(marker);
        assertThat(index).isGreaterThanOrEqualTo(0);
        int dataIndex = body.indexOf("data: ", index);
        int end = body.indexOf('\n', dataIndex);
        return objectMapper.readTree(body.substring(dataIndex + "data: ".length(), end));
    }

    /** 为当前测试的 ACTIVE 模型设置固定价格，用于断言固定样例金额。 */
    private void setModelPrices() {
        jdbcClient.sql("""
                        UPDATE model
                           SET input_price_per_1m = 3.000000, output_price_per_1m = 6.000000
                         WHERE status = 'ACTIVE'
                        """).update();
    }

    /**
     * 读取运行最新一次模型调用的成本快照列。
     *
     * @param runId 运行 ID
     * @return 列名到值的映射；允许 null 值
     */
    private Map<String, Object> invocationRow(UUID runId) {
        return jdbcClient
                .sql("""
                        SELECT status, prompt_tokens, completion_tokens, input_price_per_1m,
                               output_price_per_1m, price_currency, pricing_version, cost_amount,
                               cost_status, first_token_ms, cost_day, started_at
                          FROM model_invocation
                         WHERE run_id = :runId
                         ORDER BY started_at DESC, id DESC
                         LIMIT 1
                        """)
                .param("runId", UuidBinary.toBytes(runId))
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("status", rs.getString("status"));
                    row.put("promptTokens", rs.getObject("prompt_tokens", Integer.class));
                    row.put("completionTokens", rs.getObject("completion_tokens", Integer.class));
                    row.put("inputPrice", rs.getBigDecimal("input_price_per_1m"));
                    row.put("outputPrice", rs.getBigDecimal("output_price_per_1m"));
                    row.put("priceCurrency", rs.getString("price_currency"));
                    row.put("pricingVersion", rs.getString("pricing_version"));
                    row.put("costAmount", rs.getBigDecimal("cost_amount"));
                    row.put("costStatus", rs.getString("cost_status"));
                    row.put("firstTokenMs", rs.getObject("first_token_ms", Integer.class));
                    row.put("costDay", rs.getObject("cost_day", java.time.LocalDate.class));
                    row.put(
                            "startedAt",
                            rs.getObject("started_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
                    return row;
                })
                .single();
    }

    /**
     * 独立计算期望日桶，避免测试复用被测转换逻辑。
     *
     * @param instant 调用开始瞬时
     * @return 北京时间自然日
     */
    private static java.time.LocalDate expectedDay(Instant instant) {
        return instant.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate();
    }

    /** 运行状态。 */
    private String runStatus(UUID runId) {
        return jdbcClient
                .sql("SELECT status FROM agent_run WHERE id = :id")
                .param("id", UuidBinary.toBytes(runId))
                .query(String.class)
                .single();
    }

    /** 调用状态。 */
    private String invocationStatus(UUID runId) {
        return jdbcClient
                .sql("SELECT status FROM model_invocation WHERE run_id = :id ORDER BY started_at DESC LIMIT 1")
                .param("id", UuidBinary.toBytes(runId))
                .query(String.class)
                .single();
    }

    /** 审批状态。 */
    private String approvalStatus(UUID approvalId) {
        return jdbcClient
                .sql("SELECT status FROM approval WHERE id = :id")
                .param("id", UuidBinary.toBytes(approvalId))
                .query(String.class)
                .single();
    }

    /** 审计数量；按本测试唯一租户过滤，audit_event 只追加不删除。 */
    private int auditCount(String action, String tenant) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM audit_event WHERE action = :action AND tenant_id = :tenantId")
                .param("action", action)
                .param("tenantId", tenant)
                .query(Integer.class)
                .single();
    }

    /** 创建租户与用户。 */
    private UUID seedTenant(String tenant) {
        data.createTenant(tenant);
        return data.createUser(tenant);
    }

    /** SHA-256。 */
    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
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
    private record ChatFixture(
            String tenantId, UUID userId, UUID conversationId, UUID userMessageId, UUID runId, String token) {}
}
