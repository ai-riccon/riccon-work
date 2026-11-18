package com.riccon.worker.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.agentscope.McpMockServer;
import com.riccon.worker.mcp.domain.McpException;
import com.riccon.worker.mcp.service.McpGateway;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.run.service.OutboxPoller;
import com.riccon.worker.support.TestCredentials;
import com.riccon.worker.support.TestDataFactory;
import com.riccon.worker.support.TestMcpCommands;
import com.riccon.worker.support.TestModelStub;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** HITL：工具审批挂起、批准恢复、拒绝与取消。 */
class HitlIntegrationTest extends WorkerIntegrationTestBase {

    /** Outbox 轮询器。 */
    @Autowired
    private OutboxPoller poller;

    /** JSON 解析。 */
    @Autowired
    private ObjectMapper objectMapper;

    /** MCP 网关。 */
    @Autowired
    private McpGateway mcpGateway;

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

    /** 首次运行挂起审批；批准后执行 MCP 工具并完成生成阶段。 */
    @Test
    void requestedThenApproved_executesToolAndCompletes() {
        Fixture fixture = fixture("tenant-a");

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(runStatus(fixture.runId())).isEqualTo("WAITING_APPROVAL");
        assertThat(outboxStatus(fixture.eventId())).isEqualTo("DONE");
        assertThat(stageStatus(fixture.runId(), "RETRIEVAL")).isEqualTo("SUCCEEDED");
        assertThat(stageStatus(fixture.runId(), "TOOL")).isEqualTo("RUNNING");
        assertThat(stageStatus(fixture.runId(), "APPROVAL")).isEqualTo("RUNNING");
        assertThat(approvalCount(fixture.runId(), "PENDING")).isEqualTo(1);
        assertThat(stub.requestCount()).isEqualTo(1);
        String rawPreview = approvalPreview(fixture.runId());
        assertThat(rawPreview).as("approval preview").contains("market-data");
        assertThat(objectMapper.readValue(rawPreview, java.util.Map.class))
                .as("parsed preview")
                .containsEntry("text", "market-data");
        JsonNode waitingTool = objectMapper.readTree(stageDetails(fixture.runId(), "TOOL"));
        assertThat(waitingTool.path("paramsPreview").isObject()).isTrue();
        assertThat(waitingTool.path("paramsPreview").path("text").asText())
                .as("stage details: %s", waitingTool)
                .isEqualTo("market-data");
        assertThat(waitingTool.path("approvalId").asText()).isNotBlank();
        JsonNode waitingApproval = objectMapper.readTree(stageDetails(fixture.runId(), "APPROVAL"));
        assertThat(waitingApproval.path("paramsPreview").path("text").asText()).isEqualTo("market-data");
        UUID waitingApprovalId = approvalId(fixture.runId());
        assertThat(objectMapper
                        .readTree(approvalPreview(fixture.runId()))
                        .path("text")
                        .asText())
                .isEqualTo("market-data");
        assertThat(approvalCiphertext(fixture.runId())).hasSizeGreaterThan(29);
        assertThat(approvalKeyId(fixture.runId())).startsWith("ap-");
        assertThat(waitingApprovalId).isNotNull();
        assertThat(stub.lastRequest()).contains("\"echo\"");
        assertThat(stub.lastRequest()).doesNotContain("system prompt");

        UUID approvalId = approvalId(fixture.runId());
        approve(fixture.userId(), approvalId);
        UUID resumeEvent = data.publishResearchResume("tenant-a", fixture.runId(), fixture.taskId(), approvalId);

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(runStatus(fixture.runId())).isEqualTo("SUCCEEDED");
        assertThat(outboxStatus(resumeEvent)).isEqualTo("DONE");
        assertThat(stageStatus(fixture.runId(), "TOOL")).isEqualTo("SUCCEEDED");
        assertThat(stageStatus(fixture.runId(), "APPROVAL")).isEqualTo("SUCCEEDED");
        assertThat(stageStatus(fixture.runId(), "GENERATION")).isEqualTo("SUCCEEDED");
        assertThat(mcpStatus(fixture.runId())).isEqualTo("SUCCEEDED");
        assertThat(mcpResultSha(fixture.runId())).hasSize(64);
        assertThat(auditCount("mcp.tool.invoked")).isEqualTo(1);
        JsonNode toolDetails = objectMapper.readTree(stageDetails(fixture.runId(), "TOOL"));
        assertThat(toolDetails.path("paramsPreview").path("text").asText()).isEqualTo("market-data");
        assertThat(toolDetails.path("resultSummary").asText()).startsWith("sha256=");
        assertThat(toolDetails.path("invocationId").asText()).isEqualTo(mcpInvocationId(fixture.runId()));
        assertThat(toolDetails.path("approvalId").asText()).isEqualTo(approvalId.toString());
        JsonNode approvalDetails = objectMapper.readTree(stageDetails(fixture.runId(), "APPROVAL"));
        assertThat(approvalDetails.path("paramsPreview").path("text").asText()).isEqualTo("market-data");
        assertThat(approvalDetails.path("approvalId").asText()).isEqualTo(approvalId.toString());
        assertThat(auditCount("model.invocation.completed")).isEqualTo(2);
        assertThat(stub.requestCount()).isEqualTo(2);
    }

    /** 同一审批最多执行一次工具调用；重复调用被网关拒绝。 */
    @Test
    void gateway_rejectsReplayOfSameApproval() {
        Fixture fixture = fixture("tenant-a");
        assertThat(poller.pollOnce()).isEqualTo(1);
        UUID approvalId = approvalId(fixture.runId());
        UUID toolId = jdbcClient
                .sql("SELECT mcp_tool_id FROM approval WHERE id = :id")
                .param("id", UuidBinary.toBytes(approvalId))
                .query(byte[].class)
                .optional()
                .map(UuidBinary::fromBytes)
                .orElseThrow();
        approve(fixture.userId(), approvalId);
        data.publishResearchResume("tenant-a", fixture.runId(), fixture.taskId(), approvalId);
        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(mcpInvocationCount(fixture.runId())).isEqualTo(1);

        assertThatThrownBy(() -> TenantContext.callWithTenant(
                        TenantId.of("tenant-a"),
                        () -> mcpGateway.invoke(new McpGateway.McpInvocationCommand(
                                TenantId.of("tenant-a"),
                                fixture.runId(),
                                fixture.taskId(),
                                approvalId,
                                toolId,
                                Map.of("text", "market-data"),
                                "trace-replay",
                                approvalId.toString()))))
                .isInstanceOf(McpException.class)
                .satisfies(error ->
                        assertThat(((McpException) error).errorCode()).isEqualTo(McpException.ALREADY_INVOKED));
        assertThat(mcpInvocationCount(fixture.runId())).isEqualTo(1);
    }

    /** 历史审批没有密文时不可重放，必须失败而不是退回脱敏预览。 */
    @Test
    void approvedWithoutCiphertext_failsClosed() {
        Fixture fixture = fixture("tenant-a");
        assertThat(poller.pollOnce()).isEqualTo(1);
        UUID approvalId = approvalId(fixture.runId());
        jdbcClient
                .sql("UPDATE approval SET params_ciphertext = NULL, params_key_id = NULL WHERE id = :id")
                .param("id", UuidBinary.toBytes(approvalId))
                .update();
        approve(fixture.userId(), approvalId);
        UUID resumeEvent = data.publishResearchResume("tenant-a", fixture.runId(), fixture.taskId(), approvalId);

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(runStatus(fixture.runId())).isEqualTo("FAILED");
        assertThat(outboxStatus(resumeEvent)).isEqualTo("DEAD");
        assertThat(stageStatus(fixture.runId(), "TOOL")).isEqualTo("FAILED");
        assertThat(stageErrorCode(fixture.runId(), "TOOL")).isEqualTo("RICCON-CRYPTO-500");
        assertThat(mcpInvocationCount(fixture.runId())).isZero();
    }

    /** 密文被篡改时恢复必须认证失败，且不执行工具。 */
    @Test
    void approvedWithTamperedCiphertext_failsClosed() {
        Fixture fixture = fixture("tenant-a");
        assertThat(poller.pollOnce()).isEqualTo(1);
        UUID approvalId = approvalId(fixture.runId());
        byte[] ciphertext = jdbcClient
                .sql("SELECT params_ciphertext FROM approval WHERE id = :id")
                .param("id", UuidBinary.toBytes(approvalId))
                .query(byte[].class)
                .single();
        ciphertext[ciphertext.length - 1] ^= 0x01;
        jdbcClient
                .sql("UPDATE approval SET params_ciphertext = :ciphertext WHERE id = :id")
                .param("ciphertext", ciphertext)
                .param("id", UuidBinary.toBytes(approvalId))
                .update();
        approve(fixture.userId(), approvalId);
        UUID resumeEvent = data.publishResearchResume("tenant-a", fixture.runId(), fixture.taskId(), approvalId);

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(runStatus(fixture.runId())).isEqualTo("FAILED");
        assertThat(outboxStatus(resumeEvent)).isEqualTo("DEAD");
        assertThat(stageErrorCode(fixture.runId(), "TOOL")).isEqualTo("RICCON-CRYPTO-500");
        assertThat(mcpInvocationCount(fixture.runId())).isZero();
    }

    /** 审批拒绝后恢复事件终止运行，不再执行工具。 */
    @Test
    void requestedThenRejected_cancelsWithoutToolCall() {
        Fixture fixture = fixture("tenant-a");
        assertThat(poller.pollOnce()).isEqualTo(1);
        UUID approvalId = approvalId(fixture.runId());
        reject(fixture.userId(), approvalId);
        UUID resumeEvent = data.publishResearchResume("tenant-a", fixture.runId(), fixture.taskId(), approvalId);

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(runStatus(fixture.runId())).isEqualTo("CANCELLED");
        assertThat(outboxStatus(resumeEvent)).isEqualTo("DONE");
        assertThat(stageStatus(fixture.runId(), "APPROVAL")).isEqualTo("SKIPPED");
        assertThat(mcpInvocationCount(fixture.runId())).isZero();
    }

    /** 取消事件取消等待审批的运行与 PENDING 审批。 */
    @Test
    void requestedThenCancel_cancelsRunAndApproval() {
        Fixture fixture = fixture("tenant-a");
        assertThat(poller.pollOnce()).isEqualTo(1);
        UUID cancelEvent = data.publishResearchCancel("tenant-a", fixture.runId(), "USER_CANCELLED");

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(runStatus(fixture.runId())).isEqualTo("CANCELLED");
        assertThat(outboxStatus(cancelEvent)).isEqualTo("DONE");
        assertThat(approvalCount(fixture.runId(), "CANCELLED")).isEqualTo(1);
        assertThat(mcpInvocationCount(fixture.runId())).isZero();
    }

    /**
     * API 先置终态再投递取消事件时，未完成阶段必须补齐为 SKIPPED。
     *
     * <p>真实拒绝路径（apps/api ApprovalService.reject）会先把 run/task 置 CANCELLED，
     * 再写 {@code agent.run.cancel.v1}。轮询器若按“终态运行直接完成”处理，TOOL/APPROVAL
     * 会永久停在 RUNNING（TASK-0024 F-005）。
     */
    @Test
    void apiRejectedThenCancelEvent_skipsRunningStages() {
        Fixture fixture = fixture("tenant-a");
        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(stageStatus(fixture.runId(), "TOOL")).isEqualTo("RUNNING");
        assertThat(stageStatus(fixture.runId(), "APPROVAL")).isEqualTo("RUNNING");

        cancelInApiFirst(fixture);
        UUID cancelEvent = data.publishResearchCancel("tenant-a", fixture.runId(), "USER_CANCELLED");

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(outboxStatus(cancelEvent)).isEqualTo("DONE");
        assertThat(runStatus(fixture.runId())).isEqualTo("CANCELLED");
        assertThat(stageStatus(fixture.runId(), "TOOL")).isEqualTo("SKIPPED");
        assertThat(stageStatus(fixture.runId(), "APPROVAL")).isEqualTo("SKIPPED");
        assertThat(stageFinishedAt(fixture.runId(), "TOOL")).isNotNull();
        assertThat(stageFinishedAt(fixture.runId(), "APPROVAL")).isNotNull();
        assertThat(approvalCount(fixture.runId(), "CANCELLED")).isEqualTo(1);
        assertThat(mcpInvocationCount(fixture.runId())).isZero();
        assertThat(artifactCount(fixture.taskId())).isZero();
    }

    /** 未授权工具导致不可重试失败，运行 FAILED。 */
    @Test
    void requestedWithoutGrant_failsRun() {
        String tenant = "tenant-a";
        Fixture fixture = fixture(tenant);
        jdbcClient
                .sql("DELETE FROM mcp_tool_grant WHERE tenant_id = :tenantId")
                .param("tenantId", tenant)
                .update();

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(runStatus(fixture.runId())).isEqualTo("FAILED");
        assertThat(stageStatus(fixture.runId(), "TOOL")).isEqualTo("FAILED");
        assertThat(outboxStatus(fixture.eventId())).isEqualTo("DEAD");
    }

    /** 发起人被禁用时拒绝执行并写审计。 */
    @Test
    void requestedWithDisabledUser_failsRun() {
        Fixture fixture = fixture("tenant-a");
        jdbcClient
                .sql("UPDATE app_user SET status = 'DISABLED' WHERE tenant_id = 'tenant-a' AND id = :id")
                .param("id", UuidBinary.toBytes(fixture.userId()))
                .update();

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(runStatus(fixture.runId())).isEqualTo("FAILED");
        assertThat(outboxStatus(fixture.eventId())).isEqualTo("DEAD");
        assertThat(auditCount("user.access.denied")).isEqualTo(1);
    }

    /** 构造租户、目录、模型与报告任务。 */
    private Fixture fixture(String tenant) {
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        UUID workspace = data.createWorkspace(tenant, user);
        UUID document = data.createKnowledgeDocument(tenant, "市场资料", null, user);
        data.createKnowledgeChunk(tenant, document, 1, "市场保持增长。", 1);
        jdbcClient
                .sql("UPDATE knowledge_document SET status = 'INDEXED' WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(document))
                .update();
        UUID server = data.createMcpServer("riccon-builtin", TestMcpCommands.mockServer());
        UUID tool = data.createMcpTool(
                server,
                McpMockServer.TOOL_NAME,
                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}");
        data.grantMcpTool(tenant, tool);
        data.createModelCatalog(stub.url(), TestCredentials.API_KEY);
        TestDataFactory.ResearchPublished published =
                data.publishResearchRequested(tenant, workspace, user, "市场调研", tool);
        return new Fixture(published.runId(), published.taskId(), published.eventId(), user);
    }

    /** 批准审批。 */
    private void approve(UUID user, UUID approvalId) {
        jdbcClient
                .sql("""
                        UPDATE approval
                           SET status = 'APPROVED', decided_by = :userId, decided_at = NOW(6)
                         WHERE id = :id
                        """)
                .param("userId", UuidBinary.toBytes(user))
                .param("id", UuidBinary.toBytes(approvalId))
                .update();
    }

    /** 拒绝审批。 */
    private void reject(UUID user, UUID approvalId) {
        jdbcClient
                .sql("""
                        UPDATE approval
                           SET status = 'REJECTED', decided_by = :userId, decided_at = NOW(6)
                         WHERE id = :id
                        """)
                .param("userId", UuidBinary.toBytes(user))
                .param("id", UuidBinary.toBytes(approvalId))
                .update();
    }

    /** 读取审批 ID。 */
    private UUID approvalId(UUID runId) {
        return jdbcClient
                .sql("SELECT id FROM approval WHERE run_id = :runId ORDER BY created_at DESC LIMIT 1")
                .param("runId", UuidBinary.toBytes(runId))
                .query(byte[].class)
                .optional()
                .map(UuidBinary::fromBytes)
                .orElseThrow();
    }

    /** 运行状态。 */
    private String runStatus(UUID runId) {
        return jdbcClient
                .sql("SELECT status FROM agent_run WHERE id = :id")
                .param("id", UuidBinary.toBytes(runId))
                .query(String.class)
                .single();
    }

    /** 阶段状态。 */
    private String stageStatus(UUID runId, String stage) {
        return jdbcClient
                .sql("SELECT status FROM agent_run_stage WHERE run_id = :runId AND stage = :stage")
                .param("runId", UuidBinary.toBytes(runId))
                .param("stage", stage)
                .query(String.class)
                .single();
    }

    /** 阶段错误码。 */
    private String stageErrorCode(UUID runId, String stage) {
        return jdbcClient
                .sql("SELECT error_code FROM agent_run_stage WHERE run_id = :runId AND stage = :stage")
                .param("runId", UuidBinary.toBytes(runId))
                .param("stage", stage)
                .query(String.class)
                .single();
    }

    /** 阶段结束时间。 */
    private java.sql.Timestamp stageFinishedAt(UUID runId, String stage) {
        return jdbcClient
                .sql("SELECT finished_at FROM agent_run_stage WHERE run_id = :runId AND stage = :stage")
                .param("runId", UuidBinary.toBytes(runId))
                .param("stage", stage)
                .query(java.sql.Timestamp.class)
                .single();
    }

    /** 产物数量。 */
    private int artifactCount(UUID taskId) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM artifact WHERE research_task_id = :taskId")
                .param("taskId", UuidBinary.toBytes(taskId))
                .query(Integer.class)
                .single();
    }

    /**
     * 按 API 拒绝路径先把运行与任务推进到终态，再投递取消事件。
     *
     * @param fixture 夹具
     */
    private void cancelInApiFirst(Fixture fixture) {
        jdbcClient.sql("""
                        UPDATE agent_run
                           SET status = 'CANCELLED', finished_at = NOW(6)
                         WHERE tenant_id = 'tenant-a' AND id = :id
                        """).param("id", UuidBinary.toBytes(fixture.runId())).update();
        jdbcClient.sql("""
                        UPDATE research_task
                           SET status = 'CANCELLED', finished_at = NOW(6)
                         WHERE tenant_id = 'tenant-a' AND id = :id
                        """).param("id", UuidBinary.toBytes(fixture.taskId())).update();
    }

    /** outbox 状态。 */
    private String outboxStatus(UUID eventId) {
        return jdbcClient
                .sql("SELECT status FROM agent_run_outbox WHERE id = :id")
                .param("id", UuidBinary.toBytes(eventId))
                .query(String.class)
                .single();
    }

    /** 审批数量。 */
    private int approvalCount(UUID runId, String status) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM approval WHERE run_id = :runId AND status = :status")
                .param("runId", UuidBinary.toBytes(runId))
                .param("status", status)
                .query(Integer.class)
                .single();
    }

    /** MCP 调用状态。 */
    private String mcpStatus(UUID runId) {
        return jdbcClient
                .sql("SELECT status FROM mcp_invocation WHERE run_id = :runId")
                .param("runId", UuidBinary.toBytes(runId))
                .query(String.class)
                .single();
    }

    /** MCP 结果摘要。 */
    private String mcpResultSha(UUID runId) {
        return jdbcClient
                .sql("SELECT result_sha256 FROM mcp_invocation WHERE run_id = :runId")
                .param("runId", UuidBinary.toBytes(runId))
                .query(String.class)
                .single();
    }

    /** MCP 调用数量。 */
    private int mcpInvocationCount(UUID runId) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM mcp_invocation WHERE run_id = :runId")
                .param("runId", UuidBinary.toBytes(runId))
                .query(Integer.class)
                .single();
    }

    /** 审批脱敏预览 JSON。 */
    private String approvalPreview(UUID runId) {
        return jdbcClient
                .sql("SELECT params_preview FROM approval WHERE run_id = :runId ORDER BY created_at DESC LIMIT 1")
                .param("runId", UuidBinary.toBytes(runId))
                .query(String.class)
                .single();
    }

    /** 审批参数密文。 */
    private byte[] approvalCiphertext(UUID runId) {
        return jdbcClient
                .sql("SELECT params_ciphertext FROM approval WHERE run_id = :runId ORDER BY created_at DESC LIMIT 1")
                .param("runId", UuidBinary.toBytes(runId))
                .query(byte[].class)
                .single();
    }

    /** 审批参数 key id。 */
    private String approvalKeyId(UUID runId) {
        return jdbcClient
                .sql("SELECT params_key_id FROM approval WHERE run_id = :runId ORDER BY created_at DESC LIMIT 1")
                .param("runId", UuidBinary.toBytes(runId))
                .query(String.class)
                .single();
    }

    /** 阶段详情 JSON。 */
    private String stageDetails(UUID runId, String stage) {
        return jdbcClient
                .sql("SELECT details FROM agent_run_stage WHERE run_id = :runId AND stage = :stage")
                .param("runId", UuidBinary.toBytes(runId))
                .param("stage", stage)
                .query(String.class)
                .single();
    }

    /** MCP 调用 ID。 */
    private String mcpInvocationId(UUID runId) {
        return jdbcClient
                .sql("SELECT id FROM mcp_invocation WHERE run_id = :runId")
                .param("runId", UuidBinary.toBytes(runId))
                .query(byte[].class)
                .optional()
                .map(UuidBinary::fromBytes)
                .map(UUID::toString)
                .orElseThrow();
    }

    /** 审计数量。 */
    private int auditCount(String action) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM audit_event WHERE action = :action")
                .param("action", action)
                .query(Integer.class)
                .single();
    }

    /**
     * 夹具。
     *
     * @param runId 运行
     * @param taskId 任务
     * @param eventId requested 事件
     * @param userId 用户
     */
    private record Fixture(UUID runId, UUID taskId, UUID eventId, UUID userId) {}
}
