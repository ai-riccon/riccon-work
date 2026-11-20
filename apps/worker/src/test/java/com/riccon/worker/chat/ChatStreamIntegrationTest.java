package com.riccon.worker.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.riccon.platform.domain.contract.HttpHeaders;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.support.TestCredentials;
import com.riccon.worker.support.TestModelStub;
import com.riccon.worker.support.TestServiceToken;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

    /** MockMvc。 */
    @Autowired
    private MockMvc mockMvc;

    /** JSON 解析。 */
    @Autowired
    private ObjectMapper objectMapper;

    /** 模型桩。 */
    private TestModelStub stub;

    /** 启动模型桩。 */
    @BeforeEach
    void setUp() {
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
        assertThat(started.path("tenantId").asText()).isEqualTo("tenant-a");
        assertThat(started.path("runId").asText()).isEqualTo(fixture.runId().toString());
        assertThat(started.path("messageId").asText()).isNotBlank();
        assertThat(started.path("data").path("modelKey").asText()).isEqualTo("fake-model");
        JsonNode completed = firstData(body, "riccon.chat.completed.v1");
        assertThat(completed.path("data").path("finishReason").asText()).isEqualTo("stop");
        assertThat(completed.path("data").path("firstTokenMs").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(runStatus(fixture.runId())).isEqualTo("SUCCEEDED");
        assertThat(invocationStatus(fixture.runId())).isEqualTo("SUCCEEDED");
        assertThat(auditCount("model.invocation.started")).isEqualTo(1);
        assertThat(auditCount("model.invocation.completed")).isEqualTo(1);
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
        String foreignToken = TestServiceToken.mint("tenant-a", fixture.userId(), foreignRun);

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
        assertThat(auditCount("tenant.access.denied")).isEqualTo(1);
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
        assertThat(auditCount("model.invocation.failed")).isEqualTo(1);
    }

    /** 首字后失败发送 failed 事件并保留已生成内容。 */
    @Test
    void stream_modelFailsAfterFirstToken_emitsFailedEvent() throws Exception {
        stub.errorAfterChunk();
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
        String tenant = "tenant-a";
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

    /** 执行 SSE 请求并完成异步分发。 */
    private MvcResult performStream(ChatFixture fixture, String token) throws Exception {
        MvcResult pending = mockMvc.perform(post("/internal/v1/chat/streams")
                        .header(HttpHeaders.SERVICE_TOKEN, token)
                        .header(HttpHeaders.TRACE_ID, "trace-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(fixture)))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andReturn();
    }

    /** 已成功运行取消返回 409。 */
    @Test
    void cancel_terminalRun_returns409() throws Exception {
        ChatFixture fixture = chatFixture("tenant-a", "问题");
        jdbcClient
                .sql("UPDATE agent_run SET status = 'SUCCEEDED' WHERE tenant_id = 'tenant-a' AND id = :id")
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
        String token = TestServiceToken.mint("tenant-a", fixture.userId(), unknown);

        mockMvc.perform(post("/internal/v1/runs/" + unknown + "/cancel")
                        .header(HttpHeaders.SERVICE_TOKEN, token)
                        .header(HttpHeaders.TRACE_ID, "trace-cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
        assertThat(auditCount("tenant.access.denied")).isEqualTo(1);
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
        String tenant = "tenant-a";
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
                .sql("UPDATE agent_run SET status = 'CANCELLED' WHERE tenant_id = 'tenant-a' AND id = :id")
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
                .sql("UPDATE app_user SET status = 'DISABLED' WHERE tenant_id = 'tenant-a' AND id = :id")
                .param("id", UuidBinary.toBytes(fixture.userId()))
                .update();

        mockMvc.perform(post("/internal/v1/chat/streams")
                        .header(HttpHeaders.SERVICE_TOKEN, fixture.token())
                        .header(HttpHeaders.TRACE_ID, "trace-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(fixture)))
                .andExpect(status().isForbidden());

        assertThat(auditCount("user.access.denied")).isEqualTo(1);
    }

    /** 构造对话夹具。 */
    private ChatFixture chatFixture(String tenant, String question) {
        return chatFixture(tenant, question, true);
    }

    /** 构造对话夹具，可选是否创建模型目录。 */
    private ChatFixture chatFixture(String tenant, String question, boolean withModel) {
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
        UUID user = seedTenant("tenant-b");
        UUID workspace = data.createWorkspace("tenant-b", user);
        UUID conversation = data.createConversation("tenant-b", workspace, user);
        data.createMessage("tenant-b", conversation, 1, "USER", "foreign");
        return data.createChatRun("tenant-b", conversation, user);
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

    /** 审计数量。 */
    private int auditCount(String action) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM audit_event WHERE action = :action")
                .param("action", action)
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
