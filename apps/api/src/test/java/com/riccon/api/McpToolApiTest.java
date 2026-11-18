package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.ResearchApiTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 租户 MCP 工具目录接口集成测试。
 *
 * <p>覆盖 M02-AC-06 的真实入口：J2 页面先列出租户授权工具，再用返回的工具 ID 创建报告任务。
 * 同时验证平台白名单过滤（授权、工具、server 三重可用）、跨租户不可见与权限控制。
 */
class McpToolApiTest extends ResearchApiTestSupport {

    /** 普通成员能看到本租户授权工具，并可直接用于创建任务。 */
    @Test
    @DisplayName("授权工具列表可用于 J2 创建任务")
    void list_returnsGrantedToolsUsableForTaskCreation() {
        Fixture fixture = fixture();
        UUID toolId = createMcpTool(fixture.tenant());

        ApiTestClient.ApiResponse response =
                client.get("/api/v1/mcp-tools?page=1&size=100", fixture.member().sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = client.json(response);
        assertThat(body.path("total").asLong()).isEqualTo(1);
        assertThat(body.path("items")).hasSize(1);
        JsonNode tool = body.path("items").get(0);
        assertThat(tool.path("id").asText()).isEqualTo(toolId.toString());
        assertThat(tool.path("name").asText()).isNotBlank();
        assertThat(tool.path("serverId").asText()).isNotBlank();
        assertThat(tool.path("riskLevel").asText()).isEqualTo("READ");
        assertThat(tool.path("requiresApproval").asBoolean()).isTrue();
        assertThat(tool.path("status").asText()).isEqualTo("ACTIVE");

        JsonNode task = createTask(
                fixture.member(),
                fixture.memberWorkspace(),
                "J2 从工具列表创建调研任务",
                UUID.fromString(tool.path("id").asText()),
                "mcp-ui-flow-0001");
        assertThat(task.path("mcpToolId").asText()).isEqualTo(toolId.toString());
        assertThat(task.path("status").asText()).isEqualTo("PENDING");
    }

    /** 授权停用、工具停用或 server 停用都不再出现在列表中。 */
    @Test
    @DisplayName("停用的授权或工具不返回")
    void list_excludesDisabledGrantToolAndServer() {
        Fixture fixture = fixture();
        UUID grantedDisabled = createMcpTool(fixture.tenant());
        UUID toolDisabled = createMcpTool(fixture.tenant());
        UUID serverDisabled = createMcpTool(fixture.tenant());
        UUID active = createMcpTool(fixture.tenant());

        jdbcClient
                .sql("""
                        UPDATE mcp_tool_grant SET enabled = FALSE
                         WHERE tenant_id = :tenantId AND mcp_tool_id = :toolId
                        """)
                .param("tenantId", fixture.tenant())
                .param("toolId", BinaryUuids.toBytes(grantedDisabled))
                .update();
        jdbcClient
                .sql("UPDATE mcp_tool SET status = 'DISABLED' WHERE id = :toolId")
                .param("toolId", BinaryUuids.toBytes(toolDisabled))
                .update();
        jdbcClient.sql("""
                        UPDATE mcp_server SET status = 'DISABLED'
                         WHERE id = (SELECT server_id FROM mcp_tool WHERE id = :toolId)
                        """).param("toolId", BinaryUuids.toBytes(serverDisabled)).update();

        ApiTestClient.ApiResponse response =
                client.get("/api/v1/mcp-tools?page=1&size=100", fixture.member().sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = client.json(response);
        assertThat(body.path("total").asLong()).isEqualTo(1);
        assertThat(body.path("items").get(0).path("id").asText()).isEqualTo(active.toString());
        assertThat(body.toString()).doesNotContain(grantedDisabled.toString());
    }

    /** 跨租户列表为空，且不泄露其他租户的工具 ID。 */
    @Test
    @DisplayName("跨租户工具不可见")
    void list_crossTenant_isEmpty() {
        Fixture first = fixture();
        Fixture second = fixture();
        UUID toolId = createMcpTool(first.tenant());

        ApiTestClient.ApiResponse response =
                client.get("/api/v1/mcp-tools?page=1&size=100", second.member().sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = client.json(response);
        assertThat(body.path("total").asLong()).isZero();
        assertThat(body.path("items")).isEmpty();
        assertThat(body.toString()).doesNotContain(toolId.toString());
    }

    /** 平台管理员没有 mcp.read，看不到租户业务目录；未认证请求返回 401。 */
    @Test
    @DisplayName("平台管理员 403 且未认证 401")
    void list_requiresMcpReadPermission() {
        Fixture fixture = fixture();
        createMcpTool(fixture.tenant());

        ApiTestClient.ApiResponse platform =
                client.get("/api/v1/mcp-tools", fixture.platform().sessionCookie(), Map.of());
        assertThat(platform.status()).isEqualTo(403);
        assertThat(client.json(platform).path("code").asText()).isEqualTo("RICCON-AUTH-403");

        ApiTestClient.ApiResponse anonymous = client.get("/api/v1/mcp-tools");
        assertThat(anonymous.status()).isEqualTo(401);
    }

    /** 分页总数与条目一致，最大每页大小受合同约束。 */
    @Test
    @DisplayName("工具列表分页")
    void list_paginates() {
        Fixture fixture = fixture();
        createMcpTool(fixture.tenant());
        createMcpTool(fixture.tenant());

        JsonNode firstPage = client.json(
                client.get("/api/v1/mcp-tools?page=1&size=1", fixture.member().sessionCookie(), Map.of()));
        assertThat(firstPage.path("total").asLong()).isEqualTo(2);
        assertThat(firstPage.path("totalPages").asInt()).isEqualTo(2);
        assertThat(firstPage.path("items")).hasSize(1);

        ApiTestClient.ApiResponse oversized =
                client.get("/api/v1/mcp-tools?page=1&size=101", fixture.member().sessionCookie(), Map.of());
        assertThat(oversized.status()).isEqualTo(400);
    }
}
