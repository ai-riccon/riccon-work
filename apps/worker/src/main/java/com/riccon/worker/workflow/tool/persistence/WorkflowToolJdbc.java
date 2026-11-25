package com.riccon.worker.workflow.tool.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.workflow.tool.domain.WorkflowToolApprovalSnapshot;
import com.riccon.worker.workflow.tool.domain.WorkflowToolInvocationInsert;
import com.riccon.worker.workflow.tool.domain.WorkflowToolInvocationStatus;
import com.riccon.worker.workflow.tool.domain.WorkflowToolRunBinding;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 工作流工具调用与审批快照的租户数据访问（ADR-0024 §4.7）。
 *
 * <p>所有语句经 {@link TenantJdbc} 强制 {@code tenant_id}。本类只做行读写，不决定策略：
 * 审批状态/plan/参数校验在 service，唯一键 {@code (tenant_id, workflow_approval_id)} 与
 * {@code STARTED -> 终态} 条件更新是“一个审批最多执行一次”的最终守卫。
 *
 * <p>参数密文只从 {@code workflow_approval} 读取，用于调用前解密；本类不写日志、不回显密文。
 */
@Component
public class WorkflowToolJdbc {

    /** 最新工具审批快照（按目标节点匹配；同一节点多次审批取最新）。 */
    private static final String APPROVAL_SQL = """
            SELECT id, node_key, attempt, status, assignee_type, assignee_role_code,
                   action_kind, target_node_key, target_tool_id, target_tool_name,
                   params_preview, params_ciphertext, params_key_id, params_sha256,
                   plan_sha256, requested_by, decided_by, created_at
              FROM workflow_approval
             WHERE tenant_id = :tenantId
               AND workflow_run_id = :runId
               AND target_node_key = :nodeKey
               AND action_kind = 'MCP_TOOL'
             ORDER BY created_at DESC, attempt DESC
             LIMIT 1
            """;

    /** 运行绑定的流程与发布版本。 */
    private static final String RUN_BINDING_SQL = """
            SELECT workflow_id, workflow_version_id
              FROM workflow_run
             WHERE tenant_id = :tenantId AND id = :runId
            """;

    /**
     * 当前工具节点的历史尝试开始时间。
     *
     * <p>{@code HAVING} 过滤空聚合行：没有历史尝试时返回空，而不是 null 行。
     */
    private static final String PREVIOUS_ATTEMPT_SQL = """
            SELECT MAX(COALESCE(started_at, created_at)) AS previous_started_at
              FROM workflow_node_run
             WHERE tenant_id = :tenantId
               AND workflow_run_id = :runId
               AND node_key = :nodeKey
               AND attempt < :attempt
            HAVING previous_started_at IS NOT NULL
            """;

    /** 当前节点尝试号（按节点尝试 ID）。 */
    private static final String ATTEMPT_SQL = """
            SELECT attempt
              FROM workflow_node_run
             WHERE tenant_id = :tenantId AND id = :nodeRunId AND status = 'RUNNING'
            """;

    /** 审批是否已有调用记录。 */
    private static final String INVOCATION_STATUS_SQL = """
            SELECT status
              FROM workflow_tool_invocation
             WHERE tenant_id = :tenantId AND workflow_approval_id = :approvalId
            """;

    /** 插入 STARTED 调用；唯一键冲突即视为并发/重复执行。 */
    private static final String INSERT_INVOCATION_SQL = """
            INSERT INTO workflow_tool_invocation (
                tenant_id, id, workflow_run_id, workflow_approval_id, node_key, attempt,
                mcp_tool_id, tool_name, status, params_sha256, params_preview,
                started_at, created_at
            ) VALUES (
                :tenantId, :id, :runId, :approvalId, :nodeKey, :attempt,
                :toolId, :toolName, 'STARTED', :paramsSha256, :paramsPreview,
                :now, :now
            )
            """;

    /** 成功终态；只允许从 STARTED 迁移。 */
    private static final String MARK_SUCCEEDED_SQL = """
            UPDATE workflow_tool_invocation
               SET status = 'SUCCEEDED',
                   result_sha256 = :resultSha256,
                   result_size_bytes = :resultSizeBytes,
                   result_preview = :resultPreview,
                   duration_ms = :durationMs,
                   finished_at = :now,
                   error_code = NULL
             WHERE tenant_id = :tenantId AND id = :id AND status = 'STARTED'
            """;

    /** 失败/拒绝终态；拒绝路径不插入记录，因此实际只使用 FAILED。 */
    private static final String MARK_TERMINATED_SQL = """
            UPDATE workflow_tool_invocation
               SET status = :status,
                   error_code = :errorCode,
                   duration_ms = :durationMs,
                   finished_at = :now
             WHERE tenant_id = :tenantId AND id = :id AND status = 'STARTED'
            """;

    /** 把调用 ID 绑定到当前节点尝试，供运行时间线展示；节点已不 RUNNING 时返回 0。 */
    private static final String BIND_NODE_SQL = """
            UPDATE workflow_node_run
               SET tool_invocation_id = :invocationId, updated_at = :now
             WHERE tenant_id = :tenantId
               AND workflow_run_id = :runId
               AND node_key = :nodeKey
               AND attempt = :attempt
               AND status = 'RUNNING'
            """;

    /** 租户数据访问。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建工作流工具数据访问。
     *
     * @param tenantJdbc 租户数据访问
     */
    public WorkflowToolJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 读取运行中目标工具节点最新的审批快照。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 工具节点 key
     * @return 审批快照；没有工具审批为空
     */
    public Optional<WorkflowToolApprovalSnapshot> findLatestToolApproval(
            TenantId tenantId, UUID runId, String nodeKey) {
        return tenantJdbc.queryOne(
                tenantId,
                APPROVAL_SQL,
                Map.of("runId", UuidBinary.toBytes(runId), "nodeKey", nodeKey),
                (rs, rowNum) -> new WorkflowToolApprovalSnapshot(
                        UuidBinary.fromBytes(rs.getBytes("id")),
                        rs.getString("node_key"),
                        rs.getInt("attempt"),
                        WorkflowApprovalStatus.valueOf(rs.getString("status")),
                        WorkflowApprovalAssigneeType.valueOf(rs.getString("assignee_type")),
                        rs.getString("assignee_role_code"),
                        WorkflowApprovalActionKind.valueOf(rs.getString("action_kind")),
                        rs.getString("target_node_key"),
                        rs.getBytes("target_tool_id") == null
                                ? null
                                : UuidBinary.fromBytes(rs.getBytes("target_tool_id")),
                        rs.getString("target_tool_name"),
                        rs.getString("params_preview"),
                        rs.getBytes("params_ciphertext"),
                        rs.getString("params_key_id"),
                        rs.getString("params_sha256"),
                        rs.getString("plan_sha256"),
                        UuidBinary.fromBytes(rs.getBytes("requested_by")),
                        rs.getBytes("decided_by") == null ? null : UuidBinary.fromBytes(rs.getBytes("decided_by")),
                        rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)));
    }

    /**
     * 读取运行绑定流程与发布版本。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @return 绑定；运行不存在为空
     */
    public Optional<WorkflowToolRunBinding> findRunBinding(TenantId tenantId, UUID runId) {
        return tenantJdbc.queryOne(
                tenantId,
                RUN_BINDING_SQL,
                Map.of("runId", UuidBinary.toBytes(runId)),
                (rs, rowNum) -> new WorkflowToolRunBinding(
                        UuidBinary.fromBytes(rs.getBytes("workflow_id")),
                        UuidBinary.fromBytes(rs.getBytes("workflow_version_id"))));
    }

    /**
     * 读取当前工具节点更早尝试的最晚开始时间。
     *
     * <p>写工具重试必须重新审批：旧审批的 {@code created_at} 不晚于该时间时不可复用。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 工具节点 key
     * @param attempt 当前尝试号
     * @return 历史尝试开始时间；没有历史尝试为空
     */
    public Optional<Instant> findPreviousAttemptStartedAt(TenantId tenantId, UUID runId, String nodeKey, int attempt) {
        return tenantJdbc.queryOne(
                tenantId,
                PREVIOUS_ATTEMPT_SQL,
                Map.of("runId", UuidBinary.toBytes(runId), "nodeKey", nodeKey, "attempt", attempt),
                (rs, rowNum) ->
                        rs.getObject("previous_started_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    /**
     * 读取当前 RUNNING 节点尝试号。
     *
     * @param tenantId 租户
     * @param nodeRunId 节点尝试 ID
     * @return 尝试号；节点已不 RUNNING 为空
     */
    public Optional<Integer> findAttempt(TenantId tenantId, UUID nodeRunId) {
        return tenantJdbc.queryOne(
                tenantId,
                ATTEMPT_SQL,
                Map.of("nodeRunId", UuidBinary.toBytes(nodeRunId)),
                (rs, rowNum) -> rs.getInt("attempt"));
    }

    /**
     * 读取审批已写入的调用状态。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @return 调用状态；从未执行时为空
     */
    public Optional<WorkflowToolInvocationStatus> findInvocationStatus(TenantId tenantId, UUID approvalId) {
        return tenantJdbc.queryOne(
                tenantId,
                INVOCATION_STATUS_SQL,
                Map.of("approvalId", UuidBinary.toBytes(approvalId)),
                (rs, rowNum) -> WorkflowToolInvocationStatus.valueOf(rs.getString("status")));
    }

    /**
     * 写入 STARTED 调用记录；唯一键冲突由上层按并发重复执行失败关闭。
     *
     * @param tenantId 租户
     * @param insert 插入参数
     * @return 插入行数
     */
    public int insertStarted(TenantId tenantId, WorkflowToolInvocationInsert insert) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(insert.id()));
        params.put("runId", UuidBinary.toBytes(insert.workflowRunId()));
        params.put("approvalId", UuidBinary.toBytes(insert.approvalId()));
        params.put("nodeKey", insert.nodeKey());
        params.put("attempt", insert.attempt());
        params.put("toolId", UuidBinary.toBytes(insert.toolId()));
        params.put("toolName", insert.toolName());
        params.put("paramsSha256", insert.paramsSha256());
        params.put("paramsPreview", insert.paramsPreview());
        params.put("now", databaseTime(insert.startedAt()));
        return tenantJdbc.insert(tenantId, INSERT_INVOCATION_SQL, params);
    }

    /**
     * 标记调用成功。
     *
     * @param tenantId 租户
     * @param invocationId 调用 ID
     * @param resultSha256 结果摘要
     * @param resultSizeBytes 结果字节数
     * @param resultPreview 脱敏结果摘要
     * @param durationMs 耗时
     * @param now 完成时间
     * @return 受影响行数；0 表示已不是 STARTED
     */
    public int markSucceeded(
            TenantId tenantId,
            UUID invocationId,
            String resultSha256,
            long resultSizeBytes,
            String resultPreview,
            long durationMs,
            Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(invocationId));
        params.put("resultSha256", resultSha256);
        params.put("resultSizeBytes", resultSizeBytes);
        params.put("resultPreview", truncate(resultPreview, 1000));
        params.put("durationMs", Math.max(0L, durationMs));
        params.put("now", databaseTime(now));
        return tenantJdbc.update(tenantId, MARK_SUCCEEDED_SQL, params);
    }

    /**
     * 标记调用失败。
     *
     * @param tenantId 租户
     * @param invocationId 调用 ID
     * @param status FAILED 或 DENIED
     * @param errorCode 稳定错误码
     * @param durationMs 耗时
     * @param now 完成时间
     * @return 受影响行数；0 表示已不是 STARTED
     */
    public int markTerminated(
            TenantId tenantId,
            UUID invocationId,
            WorkflowToolInvocationStatus status,
            String errorCode,
            long durationMs,
            Instant now) {
        if (status != WorkflowToolInvocationStatus.FAILED && status != WorkflowToolInvocationStatus.DENIED) {
            throw new IllegalArgumentException("terminal status must be FAILED or DENIED");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(invocationId));
        params.put("status", status.name());
        params.put("errorCode", errorCode);
        params.put("durationMs", Math.max(0L, durationMs));
        params.put("now", databaseTime(now));
        return tenantJdbc.update(tenantId, MARK_TERMINATED_SQL, params);
    }

    /**
     * 把调用 ID 绑定到当前 RUNNING 节点尝试。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param attempt 尝试号
     * @param invocationId 调用 ID
     * @param now 当前时间
     * @return 受影响行数；0 表示节点已不在 RUNNING
     */
    public int bindNodeInvocation(
            TenantId tenantId, UUID runId, String nodeKey, int attempt, UUID invocationId, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("invocationId", UuidBinary.toBytes(invocationId));
        params.put("runId", UuidBinary.toBytes(runId));
        params.put("nodeKey", nodeKey);
        params.put("attempt", attempt);
        params.put("now", databaseTime(now));
        return tenantJdbc.update(tenantId, BIND_NODE_SQL, params);
    }

    /**
     * 把 UTC Instant 转为数据库 {@code DATETIME(6)}（列按 UTC 存储）。
     *
     * @param instant 瞬时
     * @return UTC 本地时间
     */
    private static LocalDateTime databaseTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * 截断文本到列宽；null 原样返回。
     *
     * @param value 文本
     * @param maxLength 最大长度
     * @return 截断后的文本
     */
    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
