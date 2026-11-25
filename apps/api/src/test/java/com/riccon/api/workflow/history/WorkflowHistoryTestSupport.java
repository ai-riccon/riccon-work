package com.riccon.api.workflow.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.workflow.approval.WorkflowApprovalTestSupport;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 运行历史/详情集成测试基座。
 *
 * <p>复用真实 MySQL（Testcontainers）、Flyway V15、真实会话/CSRF/权限链与 HTTP；流程/版本/运行通过
 * 真实接口创建，运行结果、节点摘要、引用与到期时间使用直接 SQL 构造目标状态。历史与详情端点、审计与
 * 可见性断言全部走真实服务，不复用其他任务的测试作为本任务证据。
 */
public abstract class WorkflowHistoryTestSupport extends WorkflowApprovalTestSupport {

    /** 默认流程名。 */
    protected static final String WORKFLOW_NAME = "历史运行流程";

    /**
     * 发布一个 TENANT 范围、默认审批图（WORKFLOW_EDITOR 角色审批）的流程。
     *
     * @param fixture 测试夹具
     * @param name 流程名
     * @return 流程 ID
     */
    protected String publishHistoryWorkflow(Fixture fixture, String name) {
        return publishHistoryWorkflow(fixture.owner(), fixture, name);
    }

    /**
     * 以指定主体创建并发布流程（用于构造不同所有者的流程）。
     *
     * @param session 创建/编辑主体
     * @param fixture 测试夹具
     * @param name 流程名
     * @return 流程 ID
     */
    protected String publishHistoryWorkflow(LoginSession session, Fixture fixture, String name) {
        String workflowId = createDraft(session, name).path("id").asText();
        saveDraft(session, workflowId, 1, defaultGraph(fixture));
        publishOk(fixture.tenantAdmin(), workflowId, "TENANT", null, "publish-" + UUID.randomUUID());
        return workflowId;
    }

    /**
     * 调用运行历史端点。
     *
     * @param session 会话
     * @param query 查询串；可空
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse listRuns(LoginSession session, String query) {
        String suffix = query == null || query.isBlank() ? "" : "?" + query;
        return client.get("/api/v1/workflow-runs" + suffix, session.sessionCookie(), Map.of());
    }

    /**
     * 调用运行历史端点并断言 200。
     *
     * @param session 会话
     * @param query 查询串；可空
     * @return 响应 JSON
     */
    protected JsonNode listRunsOk(LoginSession session, String query) {
        ApiTestClient.ApiResponse response = listRuns(session, query);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 调用运行详情端点。
     *
     * @param session 会话
     * @param runId 运行 ID
     * @return 原始响应
     */
    protected ApiTestClient.ApiResponse getRun(LoginSession session, UUID runId) {
        return client.get("/api/v1/workflow-runs/" + runId, session.sessionCookie(), Map.of());
    }

    /**
     * 调用运行详情端点并断言 200。
     *
     * @param session 会话
     * @param runId 运行 ID
     * @return 详情 JSON
     */
    protected JsonNode getRunOk(LoginSession session, UUID runId) {
        ApiTestClient.ApiResponse response = getRun(session, runId);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 设置运行保留到期时间。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param expiresAt 到期时间
     */
    protected void setRetention(String tenant, UUID runId, Instant expiresAt) {
        int updated = jdbcClient
                .sql("UPDATE workflow_run SET retention_expires_at = :expiresAt WHERE tenant_id = :tenant AND id = :id")
                .param("expiresAt", SqlTimestamps.toDatabase(expiresAt))
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(runId))
                .update();
        assertThat(updated).isEqualTo(1);
    }

    /**
     * 设置运行创建时间，用于稳定断言倒序与时间过滤。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param createdAt 创建时间
     */
    protected void setCreatedAt(String tenant, UUID runId, Instant createdAt) {
        int updated = jdbcClient
                .sql("UPDATE workflow_run SET created_at = :createdAt WHERE tenant_id = :tenant AND id = :id")
                .param("createdAt", SqlTimestamps.toDatabase(createdAt))
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(runId))
                .update();
        assertThat(updated).isEqualTo(1);
    }

    /**
     * 把运行置为成功并写入最终结果与执行耗时。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param resultJson 结果 JSON
     * @param executionMs 执行耗时
     * @param finishedAt 完成时间
     */
    protected void markRunSucceeded(
            String tenant, UUID runId, String resultJson, long executionMs, Instant finishedAt) {
        int updated = jdbcClient
                .sql("""
                        UPDATE workflow_run
                           SET status = 'SUCCEEDED',
                               result_json = :resultJson,
                               result_status = 'SAVED',
                               result_size_bytes = 128,
                               execution_ms = :executionMs,
                               started_at = :startedAt,
                               finished_at = :finishedAt,
                               updated_at = :finishedAt
                         WHERE tenant_id = :tenant AND id = :id
                        """)
                .param("resultJson", resultJson)
                .param("executionMs", executionMs)
                .param("startedAt", SqlTimestamps.toDatabase(finishedAt.minusSeconds(5)))
                .param("finishedAt", SqlTimestamps.toDatabase(finishedAt))
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(runId))
                .update();
        assertThat(updated).isEqualTo(1);
    }

    /**
     * 把运行置为失败并写稳定错误码。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param failedNode 失败节点 key
     * @param errorCode 稳定错误码
     * @param errorMessage 脱敏原因
     * @param finishedAt 完成时间
     */
    protected void markRunFailed(
            String tenant, UUID runId, String failedNode, String errorCode, String errorMessage, Instant finishedAt) {
        int updated = jdbcClient
                .sql("""
                        UPDATE workflow_run
                           SET status = 'FAILED',
                               error_code = :errorCode,
                               error_message = :errorMessage,
                               current_node_key = :failedNode,
                               started_at = :startedAt,
                               finished_at = :finishedAt,
                               updated_at = :finishedAt
                         WHERE tenant_id = :tenant AND id = :id
                        """)
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .param("failedNode", failedNode)
                .param("startedAt", SqlTimestamps.toDatabase(finishedAt.minusSeconds(5)))
                .param("finishedAt", SqlTimestamps.toDatabase(finishedAt))
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(runId))
                .update();
        assertThat(updated).isEqualTo(1);
    }

    /**
     * 插入完整节点尝试（摘要、引用、审批/工具引用与时间线）。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param nodeType 节点类型
     * @param nodeName 节点名
     * @param attempt 尝试号
     * @param status 节点状态
     * @param inputSummary 输入摘要；可空
     * @param outputSummary 输出摘要；可空
     * @param citationsJson 引用 JSON；可空
     * @param errorCode 错误码；可空
     * @param startedAt 开始时间；可空
     * @param finishedAt 完成时间；可空
     * @param approvalId 审批 ID；可空
     * @param invocationId 工具调用 ID；可空
     * @return 节点运行 ID
     */
    protected UUID insertRunNode(
            String tenant,
            UUID runId,
            String nodeKey,
            String nodeType,
            String nodeName,
            int attempt,
            String status,
            String inputSummary,
            String outputSummary,
            String citationsJson,
            String errorCode,
            Instant startedAt,
            Instant finishedAt,
            UUID approvalId,
            UUID invocationId) {
        UUID nodeRunId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO workflow_node_run (
                            tenant_id, id, workflow_run_id, node_key, node_type, node_name, attempt, status,
                            input_summary, output_summary, output_json, citations_json, approval_id,
                            tool_invocation_id, error_code, error_message, started_at, finished_at, duration_ms,
                            created_at, updated_at
                        ) VALUES (
                            :tenant, :id, :runId, :nodeKey, :nodeType, :nodeName, :attempt, :status,
                            :inputSummary, :outputSummary, :outputJson, :citations, :approvalId,
                            :invocationId, :errorCode, NULL, :startedAt, :finishedAt, :durationMs,
                            :createdAt, :updatedAt
                        )
                        """)
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(nodeRunId))
                .param("runId", BinaryUuids.toBytes(runId))
                .param("nodeKey", nodeKey)
                .param("nodeType", nodeType)
                .param("nodeName", nodeName)
                .param("attempt", attempt)
                .param("status", status)
                .param("inputSummary", inputSummary)
                .param("outputSummary", outputSummary)
                .param("outputJson", outputSummary == null ? null : "{\"internal\":\"SECRET-OUTPUT-MARKER\"}")
                .param("citations", citationsJson)
                .param("approvalId", approvalId == null ? null : BinaryUuids.toBytes(approvalId))
                .param("invocationId", invocationId == null ? null : BinaryUuids.toBytes(invocationId))
                .param("errorCode", errorCode)
                .param("startedAt", SqlTimestamps.toDatabase(startedAt))
                .param("finishedAt", SqlTimestamps.toDatabase(finishedAt))
                .param("durationMs", durationMs(startedAt, finishedAt))
                .param("createdAt", SqlTimestamps.toDatabase(startedAt == null ? Instant.now() : startedAt))
                .param("updatedAt", SqlTimestamps.toDatabase(finishedAt == null ? Instant.now() : finishedAt))
                .update();
        return nodeRunId;
    }

    /**
     * 插入一条租户可见（TENANT）知识库文档。
     *
     * @param tenant 租户
     * @param title 标题
     * @param checksum checksum
     * @param deleted 是否软删除
     * @return 文档 ID
     */
    protected UUID seedKnowledgeDocument(String tenant, String title, String checksum, boolean deleted) {
        UUID documentId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_document (
                            tenant_id, id, title, source_type, file_id, checksum, mime_type, size_bytes,
                            status, created_by, created_at, updated_at, visibility, department_id, deleted_at
                        ) VALUES (
                            :tenant, :id, :title, 'SEED', NULL, :checksum, 'text/plain', 4,
                            'INDEXED', NULL, NOW(6), NOW(6), 'TENANT', NULL, :deletedAt
                        )
                        """)
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(documentId))
                .param("title", title)
                .param("checksum", checksum)
                .param("deletedAt", deleted ? SqlTimestamps.toDatabase(Instant.now()) : null)
                .update();
        return documentId;
    }

    /**
     * 读取节点的最新尝试号。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @return 最新尝试号；不存在为 0
     */
    protected int latestAttempt(String tenant, UUID runId, String nodeKey) {
        return jdbcClient
                .sql("""
                        SELECT COALESCE(MAX(attempt), 0) FROM workflow_node_run
                         WHERE tenant_id = :tenant AND workflow_run_id = :runId AND node_key = :nodeKey
                        """)
                .param("tenant", tenant)
                .param("runId", BinaryUuids.toBytes(runId))
                .param("nodeKey", nodeKey)
                .query(Integer.class)
                .single();
    }

    /**
     * 读取运行状态。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @return 状态
     */
    protected String runStatus(String tenant, UUID runId) {
        return jdbcClient
                .sql("SELECT status FROM workflow_run WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(runId))
                .query(String.class)
                .single();
    }

    /**
     * 读取运行最终结果 JSON。
     *
     * @param tenant 租户
     * @param runId 运行 ID
     * @return 结果 JSON 文本；无结果为 {@code null}
     */
    protected String runResultJson(String tenant, UUID runId) {
        return jdbcClient
                .sql("SELECT result_json FROM workflow_run WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(runId))
                .query((rs, rowNum) -> rs.getString("result_json"))
                .optional()
                .orElse(null);
    }

    /**
     * 计算节点耗时。
     *
     * @param startedAt 开始时间；可空
     * @param finishedAt 完成时间；可空
     * @return 毫秒或 {@code null}
     */
    private static Long durationMs(Instant startedAt, Instant finishedAt) {
        if (startedAt == null || finishedAt == null) {
            return null;
        }
        return Math.max(0L, finishedAt.toEpochMilli() - startedAt.toEpochMilli());
    }

    /**
     * 把数据库时间转换为 UTC。
     *
     * @param value 数据库时间
     * @return UTC 时间
     */
    protected static Instant instant(LocalDateTime value) {
        return SqlTimestamps.toInstant(value);
    }
}
