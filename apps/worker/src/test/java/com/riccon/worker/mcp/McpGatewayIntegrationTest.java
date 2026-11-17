package com.riccon.worker.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.mcp.domain.McpException;
import com.riccon.worker.mcp.service.McpGateway;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.support.TestMcpCommands;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** MCP 授权、白名单、审批与拒绝审计。 */
class McpGatewayIntegrationTest extends WorkerIntegrationTestBase {

    /** 网关。 */
    @Autowired
    private McpGateway gateway;

    /** 未授权工具拒绝且不写调用记录（工具外键存在但调用前置拒绝不插入）。 */
    @Test
    void invoke_unauthorized_deniedAndAudited() {
        Fixture fixture = fixture("riccon-builtin", true);
        jdbcClient
                .sql("DELETE FROM mcp_tool_grant WHERE tenant_id = :tenantId")
                .param("tenantId", "tenant-a")
                .update();

        assertThatThrownBy(() -> invoke(fixture, null))
                .isInstanceOf(McpException.class)
                .satisfies(
                        error -> assertThat(((McpException) error).errorCode()).isEqualTo(McpException.DENIED));
        assertThat(invocationCount(fixture.runId())).isZero();
        assertThat(auditCount("mcp.tool.invoked")).isEqualTo(1);
    }

    /** server 不在平台白名单时拒绝并写 DENIED 调用记录。 */
    @Test
    void invoke_serverOutsideAllowlist_denied() {
        Fixture fixture = fixture("tenant-other", true);

        assertThatThrownBy(() -> invoke(fixture, null))
                .isInstanceOf(McpException.class)
                .satisfies(
                        error -> assertThat(((McpException) error).errorCode()).isEqualTo(McpException.DENIED));
        assertThat(invocationStatus(fixture.runId())).isEqualTo("DENIED");
        assertThat(auditCount("mcp.tool.invoked")).isEqualTo(1);
    }

    /** 缺少审批 ID 时拒绝，防止绕过 HITL。 */
    @Test
    void invoke_missingApproval_denied() {
        Fixture fixture = fixture("riccon-builtin", true);

        assertThatThrownBy(() -> invoke(fixture, null))
                .isInstanceOf(McpException.class)
                .satisfies(
                        error -> assertThat(((McpException) error).errorCode()).isEqualTo(McpException.DENIED));
        assertThat(invocationStatus(fixture.runId())).isEqualTo("DENIED");
    }

    /** WRITE 工具在 M02 一律拒绝。 */
    @Test
    void invoke_writeRisk_denied() {
        Fixture fixture = fixture("riccon-builtin", true);
        jdbcClient
                .sql("UPDATE mcp_tool SET risk_level = 'WRITE' WHERE id = :id")
                .param("id", UuidBinary.toBytes(fixture.toolId()))
                .update();

        assertThatThrownBy(() -> invoke(fixture, null))
                .isInstanceOf(McpException.class)
                .satisfies(
                        error -> assertThat(((McpException) error).errorCode()).isEqualTo(McpException.DENIED));
        assertThat(invocationStatus(fixture.runId())).isEqualTo("DENIED");
    }

    /** 构造夹具。 */
    private Fixture fixture(String serverCode, boolean grant) {
        String tenant = "tenant-a";
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        UUID workspace = data.createWorkspace(tenant, user);
        UUID conversation = data.createConversation(tenant, workspace, user);
        data.createMessage(tenant, conversation, 1, "USER", "hi");
        UUID runId = data.createChatRun(tenant, conversation, user);
        UUID serverId = data.createMcpServer(serverCode, TestMcpCommands.mockServer());
        UUID toolId = data.createMcpTool(serverId, "echo", "{\"type\":\"object\"}");
        if (grant) {
            data.grantMcpTool(tenant, toolId);
        }
        return new Fixture(tenant, user, runId, toolId);
    }

    /** 调用网关。 */
    private void invoke(Fixture fixture, UUID approvalId) {
        TenantContext.runWithTenant(
                TenantId.of(fixture.tenant()),
                () -> gateway.invoke(new McpGateway.McpInvocationCommand(
                        TenantId.of(fixture.tenant()),
                        fixture.runId(),
                        null,
                        approvalId,
                        fixture.toolId(),
                        Map.of("text", "hello"),
                        "trace-mcp",
                        "request-mcp")));
    }

    /** 调用数量。 */
    private int invocationCount(UUID runId) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM mcp_invocation WHERE run_id = :runId")
                .param("runId", UuidBinary.toBytes(runId))
                .query(Integer.class)
                .single();
    }

    /** 调用状态。 */
    private String invocationStatus(UUID runId) {
        return jdbcClient
                .sql("SELECT status FROM mcp_invocation WHERE run_id = :runId")
                .param("runId", UuidBinary.toBytes(runId))
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

    /**
     * 夹具。
     *
     * @param tenant 租户
     * @param user 用户
     * @param runId 运行
     * @param toolId 工具
     */
    private record Fixture(String tenant, UUID user, UUID runId, UUID toolId) {}
}
