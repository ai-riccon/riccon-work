package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.ResearchApiTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 报告任务 API 集成测试。
 *
 * <p>覆盖创建（任务 + 运行 + outbox + 审计）、幂等与冲突、状态投影、时间线、审批预览、MCP 调用、产物引用与
 * 跨租户 404。Worker 行为用 SQL 模拟，测试不访问真实 Worker。
 */
class ResearchTaskApiTest extends ResearchApiTestSupport {

    /** 创建任务时同时写入任务、运行、outbox 与审计，payload 与 schema 逐字段一致。 */
    @Test
    @DisplayName("创建任务写入任务、运行与 outbox")
    void create_persistsTaskRunOutboxAndAudit() {
        Fixture fixture = fixture();
        UUID toolId = createMcpTool(fixture.tenant());
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workspaces/" + fixture.memberWorkspace() + "/research-tasks",
                client.jsonBody("topic", "2026 年国内 CRM 市场规模", "mcpToolId", toolId.toString()),
                fixture.member().sessionCookie(),
                fixture.member().csrfToken(),
                Map.of("Idempotency-Key", "create-basic-0001"));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        JsonNode task = client.json(response);
        String taskId = task.path("id").asText();
        String runId = task.path("currentRunId").asText();
        assertThat(task.path("status").asText()).isEqualTo("PENDING");
        assertThat(task.path("workspaceId").asText()).isEqualTo(fixture.memberWorkspace());
        assertThat(task.path("mcpToolId").asText()).isEqualTo(toolId.toString());
        assertThat(task.path("failureCode").isNull()).isTrue();

        Map<String, Object> run = jdbcClient
                .sql("""
                        SELECT kind, status, research_task_id, user_id, trace_id, attempt, max_attempts
                          FROM agent_run WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("tenantId", fixture.tenant())
                .param("runId", com.riccon.api.persistence.BinaryUuids.toBytes(UUID.fromString(runId)))
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "kind", rs.getString("kind"),
                        "status", rs.getString("status"),
                        "taskId",
                                com.riccon.api.persistence.BinaryUuids.fromBytes(rs.getBytes("research_task_id"))
                                        .toString(),
                        "traceId", rs.getString("trace_id"),
                        "attempt", rs.getInt("attempt"),
                        "maxAttempts", rs.getInt("max_attempts")))
                .single();
        assertThat(run)
                .containsEntry("kind", "RESEARCH_REPORT")
                .containsEntry("status", "PENDING")
                .containsEntry("taskId", taskId)
                .containsEntry("traceId", response.header("X-Trace-Id"))
                .containsEntry("attempt", 1)
                .containsEntry("maxAttempts", 3);

        List<String> payloads = outboxPayloads(fixture.tenant(), runId);
        assertThat(payloads).hasSize(1);
        Map<String, Object> payload = payload(payloads.get(0));
        assertThat(payload.keySet()).containsExactlyInAnyOrder("runId", "researchTaskId", "stage", "attempt");
        assertThat(payload).containsEntry("runId", runId).containsEntry("researchTaskId", taskId);
        assertThat(payload).containsEntry("stage", "RETRIEVAL").containsEntry("attempt", 1);
        assertThat(outboxEventTypes(fixture.tenant(), runId)).containsExactly("riccon.agent.run.requested.v1");

        assertThat(auditRows("research.task.created"))
                .filteredOn(row -> fixture.tenant().equals(row.tenantId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.targetId()).isEqualTo(taskId);
                    assertThat(row.details()).contains(taskId).contains(runId);
                });
    }

    /** 同一幂等键返回既有任务；内容不同或缺少幂等键都失败。 */
    @Test
    @DisplayName("创建幂等与冲突")
    void create_idempotentAndConflicts() {
        Fixture fixture = fixture();
        UUID toolId = createMcpTool(fixture.tenant());
        String topic = "2026 年国内 CRM 市场规模";
        JsonNode first = createTask(fixture.member(), fixture.memberWorkspace(), topic, toolId, "idem-create-0002");
        markRunWaitingApproval(fixture.tenant(), runId(first));
        JsonNode second = createTask(fixture.member(), fixture.memberWorkspace(), topic, toolId, "idem-create-0002");
        assertThat(second.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(second.path("status").asText()).isEqualTo("WAITING_APPROVAL");

        Long taskCount = jdbcClient
                .sql("""
                        SELECT COUNT(*) FROM research_task
                         WHERE tenant_id = :tenantId AND idempotency_key = :key
                        """)
                .param("tenantId", fixture.tenant())
                .param("key", "idem-create-0002")
                .query(Long.class)
                .single();
        assertThat(taskCount).isEqualTo(1L);
        assertThat(outboxCount(fixture.tenant(), runId(first), "riccon.agent.run.requested.v1"))
                .isEqualTo(1L);

        ApiTestClient.ApiResponse conflict = client.post(
                "/api/v1/workspaces/" + fixture.memberWorkspace() + "/research-tasks",
                client.jsonBody("topic", "完全不同的主题", "mcpToolId", toolId.toString()),
                fixture.member().sessionCookie(),
                fixture.member().csrfToken(),
                Map.of("Idempotency-Key", "idem-create-0002"));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(client.json(conflict).path("code").asText()).isEqualTo("RICCON-CONFLICT-409");

        ApiTestClient.ApiResponse missing = client.post(
                "/api/v1/workspaces/" + fixture.memberWorkspace() + "/research-tasks",
                client.jsonBody("topic", topic, "mcpToolId", toolId.toString()),
                fixture.member().sessionCookie(),
                fixture.member().csrfToken(),
                Map.of());
        assertThat(missing.status()).isEqualTo(400);

        ApiTestClient.ApiResponse shortKey = client.post(
                "/api/v1/workspaces/" + fixture.memberWorkspace() + "/research-tasks",
                client.jsonBody("topic", topic, "mcpToolId", toolId.toString()),
                fixture.member().sessionCookie(),
                fixture.member().csrfToken(),
                Map.of("Idempotency-Key", "short"));
        assertThat(shortKey.status()).isEqualTo(400);

        ApiTestClient.ApiResponse unauthorizedTool = client.post(
                "/api/v1/workspaces/" + fixture.memberWorkspace() + "/research-tasks",
                client.jsonBody("topic", topic, "mcpToolId", UUID.randomUUID().toString()),
                fixture.member().sessionCookie(),
                fixture.member().csrfToken(),
                Map.of("Idempotency-Key", "idem-create-0003"));
        assertThat(unauthorizedTool.status()).isEqualTo(400);
    }

    /** 列表状态由运行投影，并支持状态过滤与分页。 */
    @Test
    @DisplayName("列表派生运行状态并分页")
    void list_derivesRunStatusAndPaginates() {
        Fixture fixture = fixture();
        UUID toolId = createMcpTool(fixture.tenant());
        JsonNode waiting = createTask(fixture.admin(), fixture.adminWorkspace(), "等待审批的主题", toolId, "list-0001");
        createTask(fixture.admin(), fixture.adminWorkspace(), "仍在排队的主题", toolId, "list-0002");
        markRunWaitingApproval(fixture.tenant(), runId(waiting));

        JsonNode all = listTasks(fixture.admin(), fixture.adminWorkspace(), "", 1, 20);
        assertThat(all.path("total").asLong()).isEqualTo(2);
        JsonNode waitingItem = item(all.path("items"), waiting.path("id").asText());
        assertThat(waitingItem.path("status").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(waitingItem.path("currentStage").asText()).isEqualTo("APPROVAL");

        JsonNode filtered = listTasks(fixture.admin(), fixture.adminWorkspace(), "status=WAITING_APPROVAL", 1, 20);
        assertThat(filtered.path("total").asLong()).isEqualTo(1);
        assertThat(filtered.path("items").get(0).path("id").asText())
                .isEqualTo(waiting.path("id").asText());

        JsonNode page = listTasks(fixture.admin(), fixture.adminWorkspace(), "", 2, 1);
        assertThat(page.path("total").asLong()).isEqualTo(2);
        assertThat(page.path("totalPages").asInt()).isEqualTo(2);
        assertThat(page.path("items")).hasSize(1);

        JsonNode none = listTasks(fixture.admin(), fixture.adminWorkspace(), "status=FAILED", 1, 20);
        assertThat(none.path("total").asLong()).isZero();
    }

    /** 详情返回时间线、审批预览、MCP 摘要、引用与产物，且不泄露审批密文。 */
    @Test
    @DisplayName("详情返回时间线与引用")
    void detail_returnsTimelineSourcesAndArtifact() {
        Fixture fixture = fixture();
        UUID toolId = createMcpTool(fixture.tenant());
        JsonNode task = createTask(fixture.admin(), fixture.adminWorkspace(), "市场调研详情主题", toolId, "detail-0001");
        String taskId = task.path("id").asText();
        String runId = runId(task);
        markRunWaitingApproval(fixture.tenant(), runId);

        UUID approvalId = seedApproval(
                fixture.tenant(),
                taskId,
                runId,
                toolId,
                userId(fixture.admin()),
                "search_market",
                "PENDING",
                "{\"region\":\"CN\",\"year\":2026}");
        UUID invocationId =
                seedInvocation(fixture.tenant(), taskId, runId, approvalId, toolId, "search_market", "SUCCEEDED");
        seedStage(fixture.tenant(), runId, "RETRIEVAL", "SUCCEEDED", """
                {"query":"市场调研详情主题","querySha256":"%s","hitCount":3}
                """.formatted(TEST_SHA256));
        seedStage(fixture.tenant(), runId, "TOOL", "SUCCEEDED", """
                {"invocationId":"%s","toolId":"%s","paramsPreview":{"region":"CN"},"resultSummary":"2 rows"}
                """.formatted(invocationId, toolId));
        seedStage(fixture.tenant(), runId, "APPROVAL", "RUNNING", """
                {"approvalId":"%s"}
                """.formatted(approvalId));

        UUID artifactId = seedArtifact(
                fixture.tenant(), fixture.adminWorkspace(), taskId, userId(fixture.admin()), "市场调研报告", "SAVED", 1);
        UUID versionId = seedArtifactVersion(fixture.tenant(), artifactId, 1, userId(fixture.admin()), "报告摘要");
        seedCitation(fixture.tenant(), versionId, 1, invocationId, "Mock 市场数据", "市场规模 120 亿元");

        ApiTestClient.ApiResponse response =
                client.get("/api/v1/research-tasks/" + taskId, fixture.admin().sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode detail = client.json(response);
        assertThat(detail.path("status").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(detail.path("startedAt").isNull()).isFalse();
        assertThat(detail.path("stages")).hasSize(3);
        assertThat(detail.path("stages").get(0).path("stage").asText()).isEqualTo("RETRIEVAL");
        assertThat(detail.path("stages").get(0).path("details").path("hitCount").asInt())
                .isEqualTo(3);
        assertThat(detail.path("stages")
                        .get(1)
                        .path("details")
                        .path("invocationId")
                        .asText())
                .isEqualTo(invocationId.toString());
        assertThat(detail.path("stages")
                        .get(1)
                        .path("details")
                        .path("resultSummary")
                        .asText())
                .isEqualTo("2 rows");
        assertThat(detail.path("stages")
                        .get(1)
                        .path("details")
                        .path("paramsPreview")
                        .path("region")
                        .asText())
                .isEqualTo("CN");
        assertThat(detail.path("approvals")).hasSize(1);
        assertThat(detail.path("approvals").get(0).path("id").asText()).isEqualTo(approvalId.toString());
        assertThat(detail.path("approvals")
                        .get(0)
                        .path("paramsPreview")
                        .path("year")
                        .asInt())
                .isEqualTo(2026);
        assertThat(detail.path("mcpInvocations")).hasSize(1);
        assertThat(detail.path("mcpInvocations").get(0).path("toolName").asText())
                .isEqualTo("search_market");
        assertThat(detail.path("sources")).hasSize(1);
        assertThat(detail.path("sources").get(0).path("sourceType").asText()).isEqualTo("MCP");
        assertThat(detail.path("sources").get(0).path("mcpInvocationId").asText())
                .isEqualTo(invocationId.toString());
        assertThat(detail.path("artifact").path("currentVersion").asInt()).isEqualTo(1);
        assertThat(detail.path("artifact")
                        .path("currentVersionDetail")
                        .path("version")
                        .asInt())
                .isEqualTo(1);
        assertThat(detail.path("artifact")
                        .path("currentVersionDetail")
                        .path("citations")
                        .get(0)
                        .path("ordinal")
                        .asInt())
                .isEqualTo(1);
        assertThat(response.body()).doesNotContain("ciphertext").doesNotContain("paramsKeyId");
    }

    /** 失败运行在任务详情中显示稳定错误码与脱敏原因。 */
    @Test
    @DisplayName("失败运行映射为任务失败状态")
    void detail_failedRunShowsFailureReason() {
        Fixture fixture = fixture();
        UUID toolId = createMcpTool(fixture.tenant());
        JsonNode task = createTask(fixture.member(), fixture.memberWorkspace(), "失败场景主题", toolId, "fail-0001");
        markRunFailed(fixture.tenant(), runId(task), "RICCON-MCP-504", "mcp tool timed out");

        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/research-tasks/" + task.path("id").asText(),
                fixture.member().sessionCookie(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode detail = client.json(response);
        assertThat(detail.path("status").asText()).isEqualTo("FAILED");
        assertThat(detail.path("failureCode").asText()).isEqualTo("RICCON-MCP-504");
        assertThat(detail.path("failureMessage").asText()).isEqualTo("mcp tool timed out");
        assertThat(detail.path("finishedAt").isNull()).isFalse();
    }

    /** 跨租户、他人个人空间与不存在对象统一 404。 */
    @Test
    @DisplayName("跨租户与不可见工作空间返回 404")
    void detail_crossTenantAndInvisibleWorkspace_returns404() {
        Fixture first = fixture();
        Fixture second = fixture();
        UUID toolId = createMcpTool(first.tenant());
        JsonNode task = createTask(first.admin(), first.adminWorkspace(), "跨租户探测主题", toolId, "tenant-0001");

        ApiTestClient.ApiResponse crossTenant = client.get(
                "/api/v1/research-tasks/" + task.path("id").asText(),
                second.member().sessionCookie(),
                Map.of());
        assertThat(crossTenant.status()).isEqualTo(404);
        assertThat(client.json(crossTenant).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        ApiTestClient.ApiResponse invisibleWorkspace = client.get(
                "/api/v1/research-tasks/" + task.path("id").asText(),
                first.member().sessionCookie(),
                Map.of());
        assertThat(invisibleWorkspace.status()).isEqualTo(404);

        ApiTestClient.ApiResponse missing = client.get(
                "/api/v1/research-tasks/" + UUID.randomUUID(), first.admin().sessionCookie(), Map.of());
        assertThat(missing.status()).isEqualTo(404);

        ApiTestClient.ApiResponse crossTenantList = client.get(
                "/api/v1/workspaces/" + first.adminWorkspace() + "/research-tasks",
                second.member().sessionCookie(),
                Map.of());
        assertThat(crossTenantList.status()).isEqualTo(404);
    }

    /**
     * 在分页 items 中按 ID 查找任务，避免同一微秒创建时排序不确定。
     *
     * @param items 分页 items
     * @param id 任务 ID
     * @return 任务节点
     */
    private JsonNode item(JsonNode items, String id) {
        for (JsonNode item : items) {
            if (id.equals(item.path("id").asText())) {
                return item;
            }
        }
        throw new AssertionError("task not found in page: " + id);
    }

    /**
     * 列出工作空间任务。
     *
     * @param session 登录会话
     * @param workspaceId 工作空间 ID
     * @param extraQuery 额外查询串（不带前导 {@code &}）
     * @param page 页码
     * @param size 每页大小
     * @return 分页 JSON
     */
    private JsonNode listTasks(LoginSession session, String workspaceId, String extraQuery, int page, int size) {
        String query = "?page=" + page + "&size=" + size + (extraQuery.isBlank() ? "" : "&" + extraQuery);
        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/workspaces/" + workspaceId + "/research-tasks" + query, session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }
}
