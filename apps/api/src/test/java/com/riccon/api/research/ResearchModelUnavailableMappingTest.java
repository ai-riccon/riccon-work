package com.riccon.api.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.ResearchApiTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * J2 报告全链不可用失败映射集成测试。
 *
 * <p>API 读取任务前会把当前运行状态投影回任务行；Worker 在 outbox 终止时写入的运行失败说明是内部摘要，
 * 会覆盖任务行上的员工文案。这里模拟该投影，校验 {@code RICCON-MODEL-503} 统一映射为 AC-05 固定文案，
 * 其他错误码保留 Worker 已脱敏说明。
 */
class ResearchModelUnavailableMappingTest extends ResearchApiTestSupport {

    /** AC-05 全链不可用文案。 */
    private static final String MODEL_UNAVAILABLE_MESSAGE = "模型服务暂不可用，请稍后重试。";

    /** 详情响应把运行内部摘要替换为 AC-05 固定文案。 */
    @Test
    @DisplayName("J2 全链不可用详情返回稳定错误码与固定文案")
    void detail_modelUnavailable_mapsToStableMessage() {
        Fixture fixture = fixture();
        UUID toolId = createMcpTool(fixture.tenant());
        JsonNode task = createTask(fixture.member(), fixture.memberWorkspace(), "全链不可用主题", toolId, "model-503-0001");
        // 模拟 Worker outbox 终止：agent_run 记录内部摘要，任务行上的员工文案会被读取前投影覆盖。
        markRunFailed(fixture.tenant(), runId(task), "RICCON-MODEL-503", "research tool stage failed");

        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/research-tasks/" + task.path("id").asText(),
                fixture.member().sessionCookie(),
                Map.of());

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode detail = client.json(response);
        assertThat(detail.path("status").asText()).isEqualTo("FAILED");
        assertThat(detail.path("failureCode").asText()).isEqualTo("RICCON-MODEL-503");
        assertThat(detail.path("failureMessage").asText()).isEqualTo(MODEL_UNAVAILABLE_MESSAGE);
        assertThat(response.body())
                .doesNotContain("research tool stage failed")
                .doesNotContain("provider=")
                .doesNotContain("baseUrl")
                .doesNotContain("apiKey");
    }

    /** 列表响应与详情使用同一映射。 */
    @Test
    @DisplayName("J2 全链不可用列表返回稳定文案")
    void list_modelUnavailable_mapsToStableMessage() {
        Fixture fixture = fixture();
        UUID toolId = createMcpTool(fixture.tenant());
        JsonNode task = createTask(fixture.member(), fixture.memberWorkspace(), "全链不可用列表主题", toolId, "model-503-0002");
        String taskId = task.path("id").asText();
        markRunFailed(fixture.tenant(), runId(task), "RICCON-MODEL-503", "research tool stage failed");

        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/workspaces/" + fixture.memberWorkspace() + "/research-tasks?status=FAILED&page=1&size=20",
                fixture.member().sessionCookie(),
                Map.of());

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode item = client.json(response).path("items").get(0);
        assertThat(item.path("id").asText()).isEqualTo(taskId);
        assertThat(item.path("failureCode").asText()).isEqualTo("RICCON-MODEL-503");
        assertThat(item.path("failureMessage").asText()).isEqualTo(MODEL_UNAVAILABLE_MESSAGE);
    }

    /** 非全链不可用的错误码保持 Worker 已脱敏说明，不扩散映射范围。 */
    @Test
    @DisplayName("J2 其他失败码保持既有说明")
    void detail_otherFailure_keepsStoredMessage() {
        Fixture fixture = fixture();
        UUID toolId = createMcpTool(fixture.tenant());
        JsonNode task = createTask(fixture.member(), fixture.memberWorkspace(), "工具超时主题", toolId, "mcp-504-0001");
        markRunFailed(fixture.tenant(), runId(task), "RICCON-MCP-504", "mcp tool timed out");

        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/research-tasks/" + task.path("id").asText(),
                fixture.member().sessionCookie(),
                Map.of());

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode detail = client.json(response);
        assertThat(detail.path("failureCode").asText()).isEqualTo("RICCON-MCP-504");
        assertThat(detail.path("failureMessage").asText()).isEqualTo("mcp tool timed out");
    }
}
