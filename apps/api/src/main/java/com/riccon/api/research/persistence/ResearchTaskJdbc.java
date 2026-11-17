package com.riccon.api.research.persistence;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.api.research.domain.ResearchTaskSnapshot;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.research.ResearchTaskStatus;
import com.riccon.platform.domain.research.RunStage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 报告任务 SQL。
 *
 * <p>Worker 只推进 {@code agent_run}，不修改 {@code research_task}（worker 的 {@code ResearchTaskJdbc} 是只读的）。
 * 因此 API 在读取前用一条批量 UPDATE 把当前运行状态投影回任务行：状态、阶段、失败原因与起止时间都以运行表
 * 为准，避免列表过滤看到过期状态。投影刻意保留非 FAILED 的失败原因，使审批拒绝的终止原因不会被后续读取抹掉。
 *
 * <p>所有 SQL 都经 {@link TenantJdbc} 强制租户条件；跨租户读取与普通“不存在”返回同一结果。
 */
@Component
public class ResearchTaskJdbc {

    /** 任务公开列；不包含 idempotency_key（只用于创建幂等查询）。 */
    private static final String COLUMNS = """
            id, workspace_id, created_by, topic, status, current_stage, mcp_tool_id,
            current_run_id, failure_code, failure_message, created_at, updated_at,
            started_at, finished_at
            """;

    /**
     * 把当前运行状态投影回任务行。
     *
     * <p>派生表只取运行状态列，避免与任务表同名；外层 {@code tenant_id} / {@code id} 因此没有歧义。
     * RETRY_WAIT 对用户表现为 RUNNING：重试等待是 Worker 细节，任务仍处于运行中（ADR-0008 第 3 节）。
     */
    private static final String SYNC_FROM_RUN = """
            UPDATE research_task
              JOIN (
                    SELECT id AS run_id,
                           tenant_id AS run_tenant_id,
                           status AS run_status,
                           current_stage AS run_stage,
                           error_code AS run_error_code,
                           error_message AS run_error_message,
                           started_at AS run_started_at,
                           finished_at AS run_finished_at
                      FROM agent_run
                     WHERE tenant_id = :tenantId
                   ) run_view
                ON run_view.run_tenant_id = research_task.tenant_id
               AND run_view.run_id = research_task.current_run_id
               SET research_task.status = CASE
                     WHEN run_view.run_status = 'RETRY_WAIT' THEN 'RUNNING'
                     ELSE run_view.run_status
                   END,
                   research_task.current_stage = run_view.run_stage,
                   research_task.failure_code = CASE
                     WHEN run_view.run_status = 'FAILED' THEN run_view.run_error_code
                     ELSE research_task.failure_code
                   END,
                   research_task.failure_message = CASE
                     WHEN run_view.run_status = 'FAILED' THEN run_view.run_error_message
                     ELSE research_task.failure_message
                   END,
                   research_task.started_at = run_view.run_started_at,
                   research_task.finished_at = CASE
                     WHEN run_view.run_status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                       THEN run_view.run_finished_at
                     ELSE research_task.finished_at
                   END
             WHERE tenant_id = :tenantId
               AND workspace_id = :workspaceId
            """;

    /** 只同步单个任务；供详情与审批决定使用。 */
    private static final String SYNC_ONE_FROM_RUN = SYNC_FROM_RUN + "   AND id = :taskId\n";

    /** 插入任务；created_at 与 updated_at 使用同一时刻。 */
    private static final String INSERT = """
            INSERT INTO research_task (
                tenant_id, id, workspace_id, created_by, topic, status, current_stage,
                mcp_tool_id, current_run_id, idempotency_key, created_at, updated_at
            ) VALUES (
                :tenantId, :id, :workspaceId, :createdBy, :topic, 'PENDING', NULL,
                :mcpToolId, :currentRunId, :idempotencyKey, :createdAt, :createdAt
            )
            """;

    /** 按主键读取。 */
    private static final String FIND_BY_ID =
            "SELECT " + COLUMNS + " FROM research_task WHERE tenant_id = :tenantId AND id = :taskId";

    /** 幂等查询使用当前读，绕过 REPEATABLE READ 快照，保证并发重复提交也能看到已提交任务。 */
    private static final String FIND_BY_IDEMPOTENCY = "SELECT " + COLUMNS
            + " FROM research_task"
            + " WHERE tenant_id = :tenantId AND created_by = :createdBy AND idempotency_key = :idempotencyKey"
            + " FOR UPDATE";

    /** 列表过滤条件；状态过滤作用于投影后的任务状态。 */
    private static final String LIST_FILTER = """
             FROM research_task
            WHERE tenant_id = :tenantId
              AND workspace_id = :workspaceId
            """;

    /** 统计工作空间内任务数。 */
    private static final String COUNT = "SELECT COUNT(*) " + LIST_FILTER;

    /** 带状态过滤的统计。 */
    private static final String COUNT_BY_STATUS = "SELECT COUNT(*) " + LIST_FILTER + "   AND status = :status\n";

    /** 分页列表；最新创建在前。 */
    private static final String LIST =
            "SELECT " + COLUMNS + LIST_FILTER + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset";

    /** 带状态过滤的分页列表。 */
    private static final String LIST_BY_STATUS = "SELECT " + COLUMNS + LIST_FILTER
            + "   AND status = :status\n"
            + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset";

    /** 审批通过后把任务推进为 RUNNING；只允许非终态任务。 */
    private static final String MARK_RUNNING = """
            UPDATE research_task
               SET status = 'RUNNING',
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND id = :taskId
               AND current_run_id = :runId
               AND status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL')
            """;

    /** 审批拒绝或取消后终止任务，并保留稳定失败原因供 UI 展示。 */
    private static final String MARK_TERMINATED = """
            UPDATE research_task
               SET status = 'CANCELLED',
                   failure_code = :failureCode,
                   failure_message = :failureMessage,
                   finished_at = :now,
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND id = :taskId
               AND current_run_id = :runId
               AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
            """;

    /** 校验 MCP 工具已授权、启用、只读且要求审批；M02 只允许只读工具。 */
    private static final String COUNT_AUTHORIZED_TOOL = """
            SELECT COUNT(*)
              FROM mcp_tool_grant tool_grant
              JOIN mcp_tool tool ON tool.id = tool_grant.mcp_tool_id
             WHERE tenant_id = :tenantId
               AND tool_grant.mcp_tool_id = :toolId
               AND tool_grant.enabled = TRUE
               AND tool.status = 'ACTIVE'
               AND tool.risk_level = 'READ'
               AND tool.requires_approval = TRUE
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建报告任务 SQL 组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public ResearchTaskJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 把指定工作空间的任务状态与当前运行同步。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @return 受影响行数
     */
    public int syncWorkspace(TenantId tenantId, UUID workspaceId) {
        return tenantJdbc.update(tenantId, SYNC_FROM_RUN, Map.of("workspaceId", BinaryUuids.toBytes(workspaceId)));
    }

    /**
     * 把单个任务状态与当前运行同步。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param taskId 任务 ID
     * @return 受影响行数
     */
    public int syncTask(TenantId tenantId, UUID workspaceId, UUID taskId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workspaceId", BinaryUuids.toBytes(workspaceId));
        params.put("taskId", BinaryUuids.toBytes(taskId));
        return tenantJdbc.update(tenantId, SYNC_ONE_FROM_RUN, params);
    }

    /**
     * 插入报告任务。
     *
     * @param tenantId 当前租户
     * @param task 任务快照
     * @param idempotencyKey 创建幂等键
     */
    public void insert(TenantId tenantId, ResearchTaskSnapshot task, String idempotencyKey) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(task.id()));
        params.put("workspaceId", BinaryUuids.toBytes(task.workspaceId()));
        params.put("createdBy", BinaryUuids.toBytes(task.createdBy()));
        params.put("topic", task.topic());
        params.put("mcpToolId", BinaryUuids.toBytes(task.mcpToolId()));
        params.put("currentRunId", BinaryUuids.toBytes(task.currentRunId()));
        params.put("idempotencyKey", idempotencyKey);
        params.put("createdAt", SqlTimestamps.toDatabase(task.createdAt()));
        tenantJdbc.insert(tenantId, INSERT, params);
    }

    /**
     * 按主键读取任务（调用方负责先同步状态）。
     *
     * @param tenantId 当前租户
     * @param taskId 任务 ID
     * @return 任务快照
     */
    public Optional<ResearchTaskSnapshot> findById(TenantId tenantId, UUID taskId) {
        return tenantJdbc.queryOne(
                tenantId, FIND_BY_ID, Map.of("taskId", BinaryUuids.toBytes(taskId)), ResearchTaskJdbc::mapRow);
    }

    /**
     * 按创建人与幂等键读取任务并加锁。
     *
     * @param tenantId 当前租户
     * @param createdBy 创建人
     * @param idempotencyKey 幂等键
     * @return 既有任务
     */
    public Optional<ResearchTaskSnapshot> findByIdempotencyKeyForUpdate(
            TenantId tenantId, UUID createdBy, String idempotencyKey) {
        return tenantJdbc.queryOne(
                tenantId,
                FIND_BY_IDEMPOTENCY,
                Map.of("createdBy", BinaryUuids.toBytes(createdBy), "idempotencyKey", idempotencyKey),
                ResearchTaskJdbc::mapRow);
    }

    /**
     * 统计工作空间内任务数。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param status 可选状态过滤
     * @return 任务数
     */
    public long count(TenantId tenantId, UUID workspaceId, ResearchTaskStatus status) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workspaceId", BinaryUuids.toBytes(workspaceId));
        if (status == null) {
            return tenantJdbc.count(tenantId, COUNT, params);
        }
        params.put("status", status.name());
        return tenantJdbc.count(tenantId, COUNT_BY_STATUS, params);
    }

    /**
     * 分页列出工作空间内任务。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param status 可选状态过滤
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 任务列表
     */
    public List<ResearchTaskSnapshot> list(
            TenantId tenantId, UUID workspaceId, ResearchTaskStatus status, int limit, int offset) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workspaceId", BinaryUuids.toBytes(workspaceId));
        params.put("limit", limit);
        params.put("offset", offset);
        String sql = LIST;
        if (status != null) {
            params.put("status", status.name());
            sql = LIST_BY_STATUS;
        }
        return tenantJdbc.query(tenantId, sql, params, ResearchTaskJdbc::mapRow);
    }

    /**
     * 审批通过后把任务推进为 RUNNING。
     *
     * @param tenantId 当前租户
     * @param taskId 任务 ID
     * @param runId 运行 ID
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markRunning(TenantId tenantId, UUID taskId, UUID runId, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskId", BinaryUuids.toBytes(taskId));
        params.put("runId", BinaryUuids.toBytes(runId));
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, MARK_RUNNING, params);
    }

    /**
     * 审批拒绝后终止任务。
     *
     * @param tenantId 当前租户
     * @param taskId 任务 ID
     * @param runId 运行 ID
     * @param failureCode 稳定失败码
     * @param failureMessage 脱敏失败原因
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markTerminated(
            TenantId tenantId, UUID taskId, UUID runId, String failureCode, String failureMessage, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskId", BinaryUuids.toBytes(taskId));
        params.put("runId", BinaryUuids.toBytes(runId));
        params.put("failureCode", failureCode);
        params.put("failureMessage", failureMessage);
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, MARK_TERMINATED, params);
    }

    /**
     * 判断 MCP 工具是否可被本租户用于报告取数。
     *
     * @param tenantId 当前租户
     * @param toolId 工具 ID
     * @return 可用时返回 {@code true}
     */
    public boolean isToolAuthorized(TenantId tenantId, UUID toolId) {
        return tenantJdbc.count(tenantId, COUNT_AUTHORIZED_TOOL, Map.of("toolId", BinaryUuids.toBytes(toolId))) > 0;
    }

    /**
     * 映射任务行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 任务快照
     * @throws SQLException 列读取失败
     */
    static ResearchTaskSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
        String stage = rs.getString("current_stage");
        return new ResearchTaskSnapshot(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                BinaryUuids.fromBytes(rs.getBytes("workspace_id")),
                BinaryUuids.fromBytes(rs.getBytes("created_by")),
                rs.getString("topic"),
                ResearchTaskStatus.valueOf(rs.getString("status")),
                stage == null ? null : RunStage.valueOf(stage),
                rs.getBytes("mcp_tool_id") == null ? null : BinaryUuids.fromBytes(rs.getBytes("mcp_tool_id")),
                rs.getBytes("current_run_id") == null ? null : BinaryUuids.fromBytes(rs.getBytes("current_run_id")),
                rs.getString("failure_code"),
                rs.getString("failure_message"),
                SqlTimestamps.toInstant(rs.getObject("created_at", LocalDateTime.class)),
                SqlTimestamps.toInstant(rs.getObject("updated_at", LocalDateTime.class)),
                SqlTimestamps.toInstant(rs.getObject("started_at", LocalDateTime.class)),
                SqlTimestamps.toInstant(rs.getObject("finished_at", LocalDateTime.class)));
    }
}
