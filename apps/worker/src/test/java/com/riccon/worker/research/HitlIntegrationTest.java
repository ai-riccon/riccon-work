package com.riccon.worker.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.agentscope.McpMockServer;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.run.service.OutboxPoller;
import com.riccon.worker.support.TestCredentials;
import com.riccon.worker.support.TestDataFactory;
import com.riccon.worker.support.TestMcpCommands;
import com.riccon.worker.support.TestModelStub;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** HITL：工具审批挂起、批准恢复、拒绝与取消。 */
class HitlIntegrationTest extends WorkerIntegrationTestBase {

    /** Outbox 轮询器。 */
    @Autowired
    private OutboxPoller poller;

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
        assertThat(auditCount("model.invocation.completed")).isEqualTo(2);
        assertThat(stub.requestCount()).isEqualTo(2);
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
