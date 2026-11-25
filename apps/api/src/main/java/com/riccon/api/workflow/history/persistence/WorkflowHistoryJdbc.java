package com.riccon.api.workflow.history.persistence;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.api.workflow.history.domain.WorkflowApprovalCounts;
import com.riccon.api.workflow.history.domain.WorkflowHistoryActor;
import com.riccon.api.workflow.history.domain.WorkflowHistoryFilter;
import com.riccon.api.workflow.history.domain.WorkflowRunApprovalSnapshot;
import com.riccon.api.workflow.history.domain.WorkflowRunHistorySnapshot;
import com.riccon.api.workflow.history.domain.WorkflowRunNodeSnapshot;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.platform.domain.workflow.WorkflowResultStatus;
import com.riccon.platform.domain.workflow.WorkflowRunScope;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 运行历史/详情读取 SQL。
 *
 * <p>所有语句经 {@link TenantJdbc} 强制 {@code tenant_id} 条件，并在 SQL 中固定追加
 * {@code retention_expires_at > :now}：到期运行在列表与详情统一不可见（AC-14）。
 *
 * <p>可见性口径（ADR-0024 §6.2）：租户管理员看全部；其他主体只看自己发起的运行与自己是流程所有者的
 * 运行元数据。列表 SQL 不选 {@code inputs_json}/{@code result_json}，详情只选 {@code result_json}，从不
 * 读取 {@code output_json}、参数密文或提示词模板；审批/节点计数用批量查询避免 N+1。
 */
@Component
public class WorkflowHistoryJdbc {

    /** 运行摘要列；列表与详情共用，详情额外追加 result_json。 */
    private static final String RUN_COLUMNS = """
            r.id, r.workflow_id, r.workflow_version_id, r.version_no, r.requester_user_id, r.status,
            r.current_node_key, r.error_code, r.error_message, r.result_status, r.result_size_bytes,
            r.execution_ms, r.created_at, r.updated_at, r.started_at, r.finished_at,
            r.retention_expires_at, d.name AS workflow_name
            """;

    /** 运行 FROM 与流程定义 JOIN。 */
    private static final String RUN_FROM = """
             FROM workflow_run r
             JOIN workflow_definition d
               ON d.tenant_id = r.tenant_id AND d.id = r.workflow_id
            """;

    /** 节点时间线列；不选 output_json，避免把内部完整输出带进 API。 */
    private static final String NODE_COLUMNS = """
            id, node_key, node_type, node_name, attempt, status, input_summary, output_summary,
            citations_json, approval_id, tool_invocation_id, error_code, error_message,
            started_at, finished_at, duration_ms
            """;

    /** 审批记录列；只选展示字段与脱敏摘要，不选参数密文/key/参数哈希。 */
    private static final String APPROVAL_COLUMNS = """
            a.id, a.workflow_run_id, a.workflow_id, a.node_key, a.status, a.assignee_type,
            a.assignee_role_code, a.action_kind, a.target_tool_name, a.params_preview,
            a.plan_sha256, a.requested_by, a.decided_by, a.decided_at, a.decision_comment,
            a.created_at, COALESCE(nr.node_name, a.node_key) AS node_name,
            v.run_scope, v.department_id
            """;

    /** 审批记录 FROM：运行、定义、绑定版本与对应节点快照。 */
    private static final String APPROVAL_FROM = """
             FROM workflow_approval a
             JOIN workflow_run r
               ON r.tenant_id = a.tenant_id AND r.id = a.workflow_run_id
             JOIN workflow_definition d
               ON d.tenant_id = a.tenant_id AND d.id = a.workflow_id
             JOIN workflow_version v
               ON v.tenant_id = r.tenant_id AND v.id = r.workflow_version_id
             LEFT JOIN workflow_node_run nr
               ON nr.tenant_id = a.tenant_id
              AND nr.workflow_run_id = a.workflow_run_id
              AND nr.node_key = a.node_key
              AND nr.attempt = a.attempt
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建运行历史 SQL 组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public WorkflowHistoryJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 统计可见运行数。
     *
     * @param tenantId 当前租户
     * @param actor 读取主体
     * @param filter 过滤条件
     * @param now 当前时间（保留期判定）
     * @return 可见运行数
     */
    public long countRuns(TenantId tenantId, WorkflowHistoryActor actor, WorkflowHistoryFilter filter, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = where(actor, filter, now, params);
        return tenantJdbc.count(tenantId, "SELECT COUNT(*) " + RUN_FROM + " WHERE " + where, params);
    }

    /**
     * 分页读取可见运行，按开始时间倒序。
     *
     * <p>未开始的运行（{@code started_at} 为空）按创建时间排序，保证刚提交的 PENDING 运行仍在顶部；
     * {@code id} 作为稳定次序避免同一时间戳分页漂移。
     *
     * @param tenantId 当前租户
     * @param actor 读取主体
     * @param filter 过滤条件
     * @param now 当前时间
     * @param limit 每页大小
     * @param offset 偏移
     * @return 运行摘要列表
     */
    public List<WorkflowRunHistorySnapshot> findRuns(
            TenantId tenantId,
            WorkflowHistoryActor actor,
            WorkflowHistoryFilter filter,
            Instant now,
            int limit,
            int offset) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = where(actor, filter, now, params);
        params.put("limit", limit);
        params.put("offset", offset);
        return tenantJdbc.query(
                tenantId,
                "SELECT " + RUN_COLUMNS + RUN_FROM + " WHERE " + where
                        + " ORDER BY COALESCE(r.started_at, r.created_at) DESC, r.id DESC"
                        + " LIMIT :limit OFFSET :offset",
                params,
                WorkflowHistoryJdbc::mapSummary);
    }

    /**
     * 按 ID 读取未到期运行详情（含最终结果原文）。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param now 当前时间
     * @return 运行详情行；不存在或已到期为空
     */
    public Optional<WorkflowRunHistorySnapshot> findRunDetail(TenantId tenantId, UUID runId, Instant now) {
        return tenantJdbc.queryOne(
                tenantId,
                "SELECT " + RUN_COLUMNS + ", r.result_json " + RUN_FROM
                        + " WHERE r.tenant_id = :tenantId AND r.id = :runId AND r.retention_expires_at > :now",
                Map.of("runId", BinaryUuids.toBytes(runId), "now", SqlTimestamps.toDatabase(now)),
                WorkflowHistoryJdbc::mapDetail);
    }

    /**
     * 读取运行下全部节点尝试（按开始时间/创建时间升序）。
     *
     * <p>重试会产生新的 {@code attempt} 行；返回全部尝试以构成完整时间线，而不是覆盖历史。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @return 节点尝试列表
     */
    public List<WorkflowRunNodeSnapshot> findNodeRuns(TenantId tenantId, UUID runId) {
        return tenantJdbc.query(
                tenantId,
                "SELECT " + NODE_COLUMNS + " FROM workflow_node_run"
                        + " WHERE tenant_id = :tenantId AND workflow_run_id = :runId"
                        + " ORDER BY COALESCE(started_at, created_at) ASC, attempt ASC, node_key ASC",
                Map.of("runId", BinaryUuids.toBytes(runId)),
                WorkflowHistoryJdbc::mapNode);
    }

    /**
     * 读取运行下审批记录（按创建时间升序）。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @return 审批记录列表
     */
    public List<WorkflowRunApprovalSnapshot> findApprovals(TenantId tenantId, UUID runId) {
        return tenantJdbc.query(
                tenantId,
                "SELECT " + APPROVAL_COLUMNS + APPROVAL_FROM
                        + " WHERE a.tenant_id = :tenantId AND a.workflow_run_id = :runId"
                        + " ORDER BY a.created_at ASC, a.id ASC",
                Map.of("runId", BinaryUuids.toBytes(runId)),
                WorkflowHistoryJdbc::mapApproval);
    }

    /**
     * 批量读取运行下审批状态计数。
     *
     * <p>一次查询覆盖一页运行，避免“列表逐项查审批”的 N+1；{@code CANCELLED} 不计入任何计数，
     * 聚合规则由服务层按计数派生（ADR-0024 §3.3.3、REV-TASK-0229-001 F-003）。
     *
     * @param tenantId 当前租户
     * @param runIds 运行 ID 集合
     * @return 运行 ID 到计数的映射；无审批的运行不在结果中
     */
    public Map<UUID, WorkflowApprovalCounts> findApprovalCounts(TenantId tenantId, Collection<UUID> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return Map.of();
        }
        List<byte[]> ids = runIds.stream().map(BinaryUuids::toBytes).toList();
        Map<UUID, WorkflowApprovalCounts> counts = new LinkedHashMap<>();
        tenantJdbc.query(tenantId, """
                SELECT workflow_run_id,
                       SUM(status = 'PENDING') AS pending_count,
                       SUM(status = 'APPROVED') AS approved_count,
                       SUM(status = 'REJECTED') AS rejected_count
                  FROM workflow_approval
                 WHERE tenant_id = :tenantId AND workflow_run_id IN (:runIds)
                 GROUP BY workflow_run_id
                """, Map.of("runIds", ids), (rs, rowNum) -> {
            counts.put(
                    BinaryUuids.fromBytes(rs.getBytes("workflow_run_id")),
                    new WorkflowApprovalCounts(
                            rs.getInt("pending_count"), rs.getInt("approved_count"), rs.getInt("rejected_count")));
            return Boolean.TRUE;
        });
        return counts;
    }

    /**
     * 判断当前账号是哪些审批的 assignee（用户指派或角色成员）。
     *
     * <p>一次查询覆盖运行详情中的全部审批；只返回审批 ID，不返回参数或文档内容，用于决定是否展示
     * 脱敏参数摘要（ADR-0024 §5.3）。
     *
     * @param tenantId 当前租户
     * @param userId 当前账号
     * @param approvalIds 审批 ID 集合
     * @return 当前账号被指派的审批 ID
     */
    public Set<UUID> findAssignedApprovalIds(TenantId tenantId, UUID userId, Collection<UUID> approvalIds) {
        if (approvalIds == null || approvalIds.isEmpty()) {
            return Set.of();
        }
        List<byte[]> ids = approvalIds.stream().map(BinaryUuids::toBytes).toList();
        Set<UUID> assigned = new LinkedHashSet<>();
        tenantJdbc.query(
                tenantId,
                """
                SELECT a.id
                  FROM workflow_approval a
                 WHERE a.tenant_id = :tenantId
                   AND a.id IN (:approvalIds)
                   AND (
                         (a.assignee_type = 'USER' AND EXISTS (
                             SELECT 1
                               FROM workflow_approval_assignee x
                              WHERE x.tenant_id = a.tenant_id
                                AND x.approval_id = a.id
                                AND x.user_id = :userId
                         ))
                         OR
                         (a.assignee_type = 'ROLE' AND EXISTS (
                             SELECT 1
                               FROM user_role ur
                               JOIN role ro
                                 ON ro.tenant_id = ur.tenant_id AND ro.id = ur.role_id
                              WHERE ur.tenant_id = a.tenant_id
                                AND ur.user_id = :userId
                                AND ro.code = a.assignee_role_code
                         ))
                   )
                """,
                Map.of("userId", BinaryUuids.toBytes(userId), "approvalIds", ids),
                (rs, rowNum) -> assigned.add(BinaryUuids.fromBytes(rs.getBytes("id"))));
        return assigned;
    }

    /**
     * 构造可见性与过滤条件；参数只按固定片段拼装，用户输入全部走绑定参数。
     *
     * @param actor 读取主体
     * @param filter 过滤条件
     * @param now 当前时间
     * @param params 参数映射（原地写入）
     * @return WHERE 片段
     */
    private static String where(
            WorkflowHistoryActor actor, WorkflowHistoryFilter filter, Instant now, Map<String, Object> params) {
        params.put("now", SqlTimestamps.toDatabase(now));
        StringBuilder where = new StringBuilder("r.tenant_id = :tenantId AND r.retention_expires_at > :now");
        if (!actor.tenantAdmin()) {
            // 非管理员：自己发起的运行 + 自己作为流程所有者的运行元数据（ADR-0024 §6.2）。
            where.append(" AND (r.requester_user_id = :actorId OR d.owner_user_id = :actorId)");
            params.put("actorId", BinaryUuids.toBytes(actor.userId()));
        }
        if (filter.workflowId() != null) {
            where.append(" AND r.workflow_id = :workflowId");
            params.put("workflowId", BinaryUuids.toBytes(filter.workflowId()));
        }
        if (filter.versionNo() != null) {
            where.append(" AND r.version_no = :versionNo");
            params.put("versionNo", filter.versionNo());
        }
        if (filter.status() != null) {
            where.append(" AND r.status = :status");
            params.put("status", filter.status().name());
        }
        if (filter.requesterUserId() != null) {
            where.append(" AND r.requester_user_id = :requesterUserId");
            params.put("requesterUserId", BinaryUuids.toBytes(filter.requesterUserId()));
        }
        if (filter.from() != null) {
            where.append(" AND r.created_at >= :createdFrom");
            params.put("createdFrom", SqlTimestamps.toDatabase(filter.from()));
        }
        if (filter.to() != null) {
            where.append(" AND r.created_at <= :createdTo");
            params.put("createdTo", SqlTimestamps.toDatabase(filter.to()));
        }
        return where.toString();
    }

    /**
     * 映射列表行；列表不加载最终结果。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 运行快照
     * @throws SQLException 读取失败
     */
    private static WorkflowRunHistorySnapshot mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return mapRun(rs, null);
    }

    /**
     * 映射详情行；加载最终结果原文。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 运行快照
     * @throws SQLException 读取失败
     */
    private static WorkflowRunHistorySnapshot mapDetail(ResultSet rs, int rowNum) throws SQLException {
        return mapRun(rs, rs.getString("result_json"));
    }

    /**
     * 映射运行行。
     *
     * @param rs 结果集
     * @param resultJson 最终结果原文；列表为空
     * @return 运行快照
     * @throws SQLException 读取失败
     */
    private static WorkflowRunHistorySnapshot mapRun(ResultSet rs, String resultJson) throws SQLException {
        return new WorkflowRunHistorySnapshot(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                rs.getString("workflow_name"),
                BinaryUuids.fromBytes(rs.getBytes("workflow_id")),
                BinaryUuids.fromBytes(rs.getBytes("workflow_version_id")),
                rs.getInt("version_no"),
                BinaryUuids.fromBytes(rs.getBytes("requester_user_id")),
                WorkflowRunStatus.valueOf(rs.getString("status")),
                rs.getString("current_node_key"),
                parseErrorCode(rs.getString("error_code")),
                rs.getString("error_message"),
                WorkflowResultStatus.valueOf(rs.getString("result_status")),
                rs.getLong("result_size_bytes"),
                rs.getLong("execution_ms"),
                instant(rs.getObject("created_at", LocalDateTime.class)),
                instant(rs.getObject("updated_at", LocalDateTime.class)),
                instant(rs.getObject("started_at", LocalDateTime.class)),
                instant(rs.getObject("finished_at", LocalDateTime.class)),
                instant(rs.getObject("retention_expires_at", LocalDateTime.class)),
                resultJson);
    }

    /**
     * 映射节点尝试行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 节点快照
     * @throws SQLException 读取失败
     */
    private static WorkflowRunNodeSnapshot mapNode(ResultSet rs, int rowNum) throws SQLException {
        return new WorkflowRunNodeSnapshot(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                rs.getString("node_key"),
                WorkflowNodeType.valueOf(rs.getString("node_type")),
                rs.getString("node_name"),
                rs.getInt("attempt"),
                WorkflowNodeStatus.valueOf(rs.getString("status")),
                rs.getString("input_summary"),
                rs.getString("output_summary"),
                rs.getString("citations_json"),
                BinaryUuids.fromBytes(rs.getBytes("approval_id")),
                BinaryUuids.fromBytes(rs.getBytes("tool_invocation_id")),
                parseErrorCode(rs.getString("error_code")),
                rs.getString("error_message"),
                instant(rs.getObject("started_at", LocalDateTime.class)),
                instant(rs.getObject("finished_at", LocalDateTime.class)),
                nullableLong(rs.getObject("duration_ms")));
    }

    /**
     * 映射审批记录行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 审批快照
     * @throws SQLException 读取失败
     */
    private static WorkflowRunApprovalSnapshot mapApproval(ResultSet rs, int rowNum) throws SQLException {
        return new WorkflowRunApprovalSnapshot(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                BinaryUuids.fromBytes(rs.getBytes("workflow_run_id")),
                BinaryUuids.fromBytes(rs.getBytes("workflow_id")),
                rs.getString("node_key"),
                rs.getString("node_name"),
                WorkflowApprovalStatus.valueOf(rs.getString("status")),
                WorkflowApprovalAssigneeType.valueOf(rs.getString("assignee_type")),
                rs.getString("assignee_role_code"),
                WorkflowApprovalActionKind.valueOf(rs.getString("action_kind")),
                rs.getString("target_tool_name"),
                rs.getString("params_preview"),
                rs.getString("plan_sha256"),
                BinaryUuids.fromBytes(rs.getBytes("requested_by")),
                BinaryUuids.fromBytes(rs.getBytes("decided_by")),
                instant(rs.getObject("decided_at", LocalDateTime.class)),
                rs.getString("decision_comment"),
                instant(rs.getObject("created_at", LocalDateTime.class)),
                WorkflowRunScope.valueOf(rs.getString("run_scope")),
                BinaryUuids.fromBytes(rs.getBytes("department_id")));
    }

    /**
     * 解析稳定错误码；未知值属于数据损坏，fail-closed。
     *
     * @param value 数据库值；可空
     * @return 枚举或 {@code null}
     */
    private static WorkflowErrorCode parseErrorCode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return WorkflowErrorCode.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("unknown workflow error_code: " + value, exception);
        }
    }

    /**
     * 读取可空 BIGINT。
     *
     * @param value 数据库值
     * @return 长整型或 {@code null}
     */
    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    /**
     * UTC 时间转换。
     *
     * @param value 数据库时间
     * @return UTC 时间
     */
    private static Instant instant(LocalDateTime value) {
        return SqlTimestamps.toInstant(value);
    }
}
