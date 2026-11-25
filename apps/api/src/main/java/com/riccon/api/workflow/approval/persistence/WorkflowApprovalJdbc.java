package com.riccon.api.workflow.approval.persistence;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.platform.domain.workflow.WorkflowRunScope;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
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
 * {@code workflow_approval}/{@code workflow_run}/{@code workflow_node_run} 的审批端 SQL。
 *
 * <p>所有语句经 {@link TenantJdbc} 强制 {@code tenant_id} 条件；本类不读取参数密文、凭据、提示词、节点
 * 输出全文或文档正文，只投影审批展示所需字段与脱敏参数摘要（ADR-0024 第 5.3/8.2 节）。
 *
 * <p>决定状态机与 Worker T07a 使用同一 SQL 语义：审批 {@code PENDING -> APPROVED/REJECTED} 的条件更新是
 * 重复/并发决定的唯一守卫；审批节点 {@code WAITING_APPROVAL -> SUCCEEDED/FAILED}；批准恢复运行
 * {@code WAITING_APPROVAL -> PENDING} 并累计审批等待；拒绝运行 {@code -> FAILED}、下游由服务层写
 * {@code SKIPPED}。API 与 Worker 不得各写一套不同语义（REV-TASK-0226-001 F-003）。
 */
@Component
public class WorkflowApprovalJdbc {

    /** 错误信息列宽。 */
    private static final int ERROR_MAX_LENGTH = 500;

    /** 摘要列宽。 */
    private static final int SUMMARY_MAX_LENGTH = 2000;

    /** 审批行投影；JOIN 运行、流程定义与绑定版本，供响应与范围摘要使用。 */
    private static final String APPROVAL_COLUMNS = """
            a.id, a.workflow_run_id, a.workflow_id, a.node_key, a.attempt, a.status,
            a.assignee_type, a.assignee_role_code, a.action_kind, a.target_node_key,
            a.target_tool_name, a.params_preview, a.plan_sha256, a.requested_by,
            a.decided_by, a.decided_at, a.decision_comment, a.created_at,
            d.name AS workflow_name,
            COALESCE(nr.node_name, a.node_key) AS node_name,
            v.run_scope, v.department_id
            """;

    /** 审批行 FROM 与运行/定义/版本/节点快照 JOIN。 */
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

    /** 待办过滤：只含分配给当前用户、非本人发起、仍为 PENDING 的审批（AC-05）。 */
    private static final String INBOX_FILTER = """
             WHERE a.tenant_id = :tenantId
               AND a.status = 'PENDING'
               AND a.requested_by <> :userId
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
            """;

    /** 用户是否被指派。 */
    private static final String ASSIGNED_SQL = """
            SELECT 1
              FROM workflow_approval_assignee
             WHERE tenant_id = :tenantId AND approval_id = :approvalId AND user_id = :userId
             LIMIT 1
            """;

    /** 账号是否在本租户启用。 */
    private static final String ACTIVE_USER_SQL = """
            SELECT 1
              FROM app_user
             WHERE tenant_id = :tenantId AND id = :userId AND status = 'ACTIVE'
            """;

    /** 账号是否拥有指定角色。 */
    private static final String USER_ROLE_SQL = """
            SELECT 1
              FROM app_user u
              JOIN user_role ur
                ON ur.tenant_id = u.tenant_id AND ur.user_id = u.id
              JOIN role r
                ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
             WHERE u.tenant_id = :tenantId
               AND u.id = :userId
               AND u.status = 'ACTIVE'
               AND r.code = :roleCode
            """;

    /** 运行当前状态。 */
    private static final String RUN_STATE_SQL = """
            SELECT status
              FROM workflow_run
             WHERE tenant_id = :tenantId AND id = :runId
            """;

    /** 运行绑定版本的发布图。 */
    private static final String RUN_GRAPH_SQL = """
            SELECT v.graph_json
              FROM workflow_run r
              JOIN workflow_version v
                ON v.tenant_id = r.tenant_id
               AND v.id = r.workflow_version_id
               AND v.workflow_id = r.workflow_id
             WHERE r.tenant_id = :tenantId AND r.id = :runId
            """;

    /** 审批已执行上游节点的引用快照。 */
    private static final String SUCCEEDED_CITATIONS_SQL = """
            SELECT citations_json
              FROM workflow_node_run
             WHERE tenant_id = :tenantId
               AND workflow_run_id = :runId
               AND status = 'SUCCEEDED'
               AND citations_json IS NOT NULL
               AND finished_at IS NOT NULL
               AND finished_at <= :createdAt
            """;

    /** 指定用户审批人列表。 */
    private static final String ASSIGNEE_USERS_SQL = """
            SELECT user_id
              FROM workflow_approval_assignee
             WHERE tenant_id = :tenantId AND approval_id = :approvalId
             ORDER BY created_at ASC, user_id ASC
            """;

    /** 节点尝试是否已存在。 */
    private static final String NODE_ATTEMPT_EXISTS_SQL = """
            SELECT 1
              FROM workflow_node_run
             WHERE tenant_id = :tenantId
               AND workflow_run_id = :runId
               AND node_key = :nodeKey
             LIMIT 1
            """;

    /** 批准：PENDING -> APPROVED，条件更新是并发/重复决定的唯一守卫。 */
    private static final String APPROVE_SQL = """
            UPDATE workflow_approval
               SET status = 'APPROVED',
                   decided_by = :decidedBy,
                   decided_at = :now,
                   decision_comment = :comment,
                   updated_at = :now
             WHERE tenant_id = :tenantId AND id = :approvalId AND status = 'PENDING'
            """;

    /** 拒绝：PENDING -> REJECTED；理由必填由服务层与数据库 CHECK 双重保证。 */
    private static final String REJECT_SQL = """
            UPDATE workflow_approval
               SET status = 'REJECTED',
                   decided_by = :decidedBy,
                   decided_at = :now,
                   decision_comment = :comment,
                   updated_at = :now
             WHERE tenant_id = :tenantId AND id = :approvalId AND status = 'PENDING'
            """;

    /** 审批节点终态：WAITING_APPROVAL -> SUCCEEDED/FAILED。 */
    private static final String FINISH_APPROVAL_NODE_SQL = """
            UPDATE workflow_node_run
               SET status = :status,
                   output_json = :outputJson,
                   output_summary = :outputSummary,
                   error_code = :errorCode,
                   error_message = :errorMessage,
                   finished_at = :now,
                   updated_at = :now,
                   duration_ms = CASE
                         WHEN started_at IS NULL THEN 0
                         ELSE GREATEST(0, TIMESTAMPDIFF(MICROSECOND, started_at, :now) DIV 1000)
                       END
             WHERE tenant_id = :tenantId
               AND workflow_run_id = :runId
               AND node_key = :nodeKey
               AND attempt = :attempt
               AND status = 'WAITING_APPROVAL'
            """;

    /** 批准后恢复运行：WAITING_APPROVAL -> PENDING，从目标节点继续。 */
    private static final String RESUME_RUN_SQL = """
            UPDATE workflow_run
               SET status = 'PENDING',
                   current_node_key = :targetNodeKey,
                   error_code = NULL,
                   error_message = NULL,
                   lease_owner = NULL,
                   lease_until = NULL,
                   approval_wait_ms = approval_wait_ms + :waitMs,
                   updated_at = :now
             WHERE tenant_id = :tenantId AND id = :runId AND status = 'WAITING_APPROVAL'
            """;

    /** 拒绝后运行失败：保留审批节点，下游由服务层写 SKIPPED。 */
    private static final String FAIL_RUN_REJECTED_SQL = """
            UPDATE workflow_run
               SET status = 'FAILED',
                   error_code = 'WF_APPROVAL_REJECTED',
                   error_message = :errorMessage,
                   current_node_key = :nodeKey,
                   result_status = 'NONE',
                   result_size_bytes = 0,
                   approval_wait_ms = approval_wait_ms + :waitMs,
                   lease_owner = NULL,
                   lease_until = NULL,
                   finished_at = :now,
                   updated_at = :now
             WHERE tenant_id = :tenantId AND id = :runId AND status = 'WAITING_APPROVAL'
            """;

    /** 把已存在且 PENDING 的下游节点尝试置为 SKIPPED。 */
    private static final String SKIP_PENDING_NODE_SQL = """
            UPDATE workflow_node_run
               SET status = 'SKIPPED',
                   output_summary = :summary,
                   finished_at = :now,
                   updated_at = :now,
                   duration_ms = CASE
                         WHEN started_at IS NULL THEN 0
                         ELSE GREATEST(0, TIMESTAMPDIFF(MICROSECOND, started_at, :now) DIV 1000)
                       END
             WHERE tenant_id = :tenantId
               AND workflow_run_id = :runId
               AND node_key = :nodeKey
               AND status = 'PENDING'
            """;

    /** 为从未开始的节点直接写 SKIPPED 终态。 */
    private static final String INSERT_SKIPPED_NODE_SQL = """
            INSERT INTO workflow_node_run (
                tenant_id, id, workflow_run_id, node_key, node_type, node_name, attempt, status,
                input_summary, output_summary, error_code, error_message,
                started_at, finished_at, duration_ms, created_at, updated_at
            ) VALUES (
                :tenantId, :id, :runId, :nodeKey, :nodeType, :nodeName, 1, 'SKIPPED',
                NULL, :summary, NULL, NULL,
                :now, :now, 0, :now, :now
            )
            """;

    /** 租户数据访问。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建审批数据访问。
     *
     * @param tenantJdbc 租户数据访问
     */
    public WorkflowApprovalJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 统计当前用户的待办数。
     *
     * @param tenantId 租户
     * @param userId 当前用户
     * @return 待办数
     */
    public long countInbox(TenantId tenantId, UUID userId) {
        return tenantJdbc.count(
                tenantId,
                "SELECT COUNT(*) FROM workflow_approval a " + INBOX_FILTER,
                Map.of("userId", BinaryUuids.toBytes(userId)));
    }

    /**
     * 分页读取当前用户的待办，按创建时间升序。
     *
     * @param tenantId 租户
     * @param userId 当前用户
     * @param limit 每页大小
     * @param offset 偏移
     * @return 审批行
     */
    public List<WorkflowApprovalRow> listInbox(TenantId tenantId, UUID userId, int limit, int offset) {
        return tenantJdbc.query(
                tenantId,
                "SELECT " + APPROVAL_COLUMNS + APPROVAL_FROM + INBOX_FILTER
                        + " ORDER BY a.created_at ASC, a.id ASC LIMIT :limit OFFSET :offset",
                Map.of("userId", BinaryUuids.toBytes(userId), "limit", limit, "offset", offset),
                WorkflowApprovalJdbc::mapApproval);
    }

    /**
     * 按 ID 读取审批。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @return 审批行；不存在为空
     */
    public Optional<WorkflowApprovalRow> findById(TenantId tenantId, UUID approvalId) {
        return tenantJdbc.queryOne(
                tenantId,
                "SELECT " + APPROVAL_COLUMNS + APPROVAL_FROM + " WHERE a.tenant_id = :tenantId AND a.id = :approvalId",
                Map.of("approvalId", BinaryUuids.toBytes(approvalId)),
                WorkflowApprovalJdbc::mapApproval);
    }

    /**
     * 判断用户是否被指派该审批。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @param userId 用户 ID
     * @return 是否指派
     */
    public boolean isAssignedUser(TenantId tenantId, UUID approvalId, UUID userId) {
        return tenantJdbc
                .queryOne(
                        tenantId,
                        ASSIGNED_SQL,
                        Map.of("approvalId", BinaryUuids.toBytes(approvalId), "userId", BinaryUuids.toBytes(userId)),
                        (rs, rowNum) -> Boolean.TRUE)
                .isPresent();
    }

    /**
     * 判断账号是否在本租户启用。
     *
     * @param tenantId 租户
     * @param userId 用户 ID
     * @return 是否启用
     */
    public boolean isActiveUser(TenantId tenantId, UUID userId) {
        return tenantJdbc
                .queryOne(
                        tenantId,
                        ACTIVE_USER_SQL,
                        Map.of("userId", BinaryUuids.toBytes(userId)),
                        (rs, rowNum) -> Boolean.TRUE)
                .isPresent();
    }

    /**
     * 判断账号是否拥有指定角色（且账号启用）。
     *
     * @param tenantId 租户
     * @param userId 用户 ID
     * @param roleCode 角色码
     * @return 是否拥有角色
     */
    public boolean hasRole(TenantId tenantId, UUID userId, String roleCode) {
        return tenantJdbc
                .queryOne(
                        tenantId,
                        USER_ROLE_SQL,
                        Map.of("userId", BinaryUuids.toBytes(userId), "roleCode", roleCode),
                        (rs, rowNum) -> Boolean.TRUE)
                .isPresent();
    }

    /**
     * 读取运行当前状态。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @return 状态；运行不存在为空
     */
    public Optional<WorkflowRunStatus> findRunStatus(TenantId tenantId, UUID runId) {
        return tenantJdbc.queryOne(
                tenantId,
                RUN_STATE_SQL,
                Map.of("runId", BinaryUuids.toBytes(runId)),
                (rs, rowNum) -> WorkflowRunStatus.valueOf(rs.getString("status")));
    }

    /**
     * 读取运行绑定版本的发布图 JSON。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @return 图 JSON；不存在为空
     */
    public Optional<String> findRunGraphJson(TenantId tenantId, UUID runId) {
        return tenantJdbc.queryOne(
                tenantId,
                RUN_GRAPH_SQL,
                Map.of("runId", BinaryUuids.toBytes(runId)),
                (rs, rowNum) -> rs.getString("graph_json"));
    }

    /**
     * 读取审批创建前已执行上游节点的引用快照 JSON。
     *
     * <p>{@code finished_at <= approval.createdAt} 限定“审批节点上游”：串行图中审批创建后执行的节点
     * 一定晚于该时间；未走分支的节点是 {@code SKIPPED} 不参与（ADR-0024 第 5.3 节）。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param createdAt 审批创建时间
     * @return 引用 JSON 列表
     */
    public List<String> findSucceededCitations(TenantId tenantId, UUID runId, Instant createdAt) {
        return tenantJdbc.query(
                tenantId,
                SUCCEEDED_CITATIONS_SQL,
                Map.of("runId", BinaryUuids.toBytes(runId), "createdAt", SqlTimestamps.toDatabase(createdAt)),
                (rs, rowNum) -> rs.getString("citations_json"));
    }

    /**
     * 读取指定用户审批人列表。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @return 用户 ID 列表
     */
    public List<UUID> findAssigneeUserIds(TenantId tenantId, UUID approvalId) {
        return tenantJdbc.query(
                tenantId,
                ASSIGNEE_USERS_SQL,
                Map.of("approvalId", BinaryUuids.toBytes(approvalId)),
                (rs, rowNum) -> BinaryUuids.fromBytes(rs.getBytes("user_id")));
    }

    /**
     * 判断节点是否已有尝试行。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @return 是否存在
     */
    public boolean hasNodeAttempt(TenantId tenantId, UUID runId, String nodeKey) {
        return tenantJdbc
                .queryOne(
                        tenantId,
                        NODE_ATTEMPT_EXISTS_SQL,
                        Map.of("runId", BinaryUuids.toBytes(runId), "nodeKey", nodeKey),
                        (rs, rowNum) -> Boolean.TRUE)
                .isPresent();
    }

    /**
     * 条件批准审批。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @param decidedBy 审批人
     * @param comment 理由；可空
     * @param now 决定时间
     * @return 受影响行数；0 表示已不是 PENDING
     */
    public int approve(TenantId tenantId, UUID approvalId, UUID decidedBy, String comment, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("approvalId", BinaryUuids.toBytes(approvalId));
        params.put("decidedBy", BinaryUuids.toBytes(decidedBy));
        params.put("comment", comment);
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, APPROVE_SQL, params);
    }

    /**
     * 条件拒绝审批。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @param decidedBy 审批人
     * @param comment 拒绝理由（必填）
     * @param now 决定时间
     * @return 受影响行数；0 表示已不是 PENDING
     */
    public int reject(TenantId tenantId, UUID approvalId, UUID decidedBy, String comment, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("approvalId", BinaryUuids.toBytes(approvalId));
        params.put("decidedBy", BinaryUuids.toBytes(decidedBy));
        params.put("comment", comment);
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, REJECT_SQL, params);
    }

    /**
     * 写审批节点终态。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param attempt 尝试号
     * @param status SUCCEEDED 或 FAILED
     * @param outputJson 输出 JSON；批准时写 decision
     * @param outputSummary 摘要
     * @param errorCode 稳定错误码；可空
     * @param errorMessage 脱敏原因；可空
     * @param now 当前时间
     * @return 受影响行数；0 表示节点已不是 WAITING_APPROVAL
     */
    public int finishApprovalNode(
            TenantId tenantId,
            UUID runId,
            String nodeKey,
            int attempt,
            WorkflowNodeStatus status,
            String outputJson,
            String outputSummary,
            WorkflowErrorCode errorCode,
            String errorMessage,
            Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("runId", BinaryUuids.toBytes(runId));
        params.put("nodeKey", nodeKey);
        params.put("attempt", attempt);
        params.put("status", status.name());
        params.put("outputJson", outputJson);
        params.put("outputSummary", truncate(outputSummary, SUMMARY_MAX_LENGTH));
        params.put("errorCode", errorCode == null ? null : errorCode.name());
        params.put("errorMessage", truncate(errorMessage, ERROR_MAX_LENGTH));
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, FINISH_APPROVAL_NODE_SQL, params);
    }

    /**
     * 批准后恢复运行。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param targetNodeKey 恢复节点
     * @param waitMs 本次等待毫秒
     * @param now 当前时间
     * @return 受影响行数；0 表示运行已不等待审批
     */
    public int resumeRunPending(TenantId tenantId, UUID runId, String targetNodeKey, long waitMs, Instant now) {
        return tenantJdbc.update(
                tenantId,
                RESUME_RUN_SQL,
                Map.of(
                        "runId", BinaryUuids.toBytes(runId),
                        "targetNodeKey", targetNodeKey,
                        "waitMs", Math.max(0L, waitMs),
                        "now", SqlTimestamps.toDatabase(now)));
    }

    /**
     * 拒绝后运行失败。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 审批节点 key
     * @param message 脱敏原因
     * @param waitMs 本次等待毫秒
     * @param now 当前时间
     * @return 受影响行数；0 表示运行已不等待审批
     */
    public int failRunRejected(
            TenantId tenantId, UUID runId, String nodeKey, String message, long waitMs, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("runId", BinaryUuids.toBytes(runId));
        params.put("nodeKey", nodeKey);
        params.put("errorMessage", truncate(message, ERROR_MAX_LENGTH));
        params.put("waitMs", Math.max(0L, waitMs));
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, FAIL_RUN_REJECTED_SQL, params);
    }

    /**
     * 把已存在且 PENDING 的节点尝试置为 SKIPPED。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param summary 原因摘要
     * @param now 当前时间
     * @return 受影响行数
     */
    public int skipPendingNode(TenantId tenantId, UUID runId, String nodeKey, String summary, Instant now) {
        return tenantJdbc.update(
                tenantId,
                SKIP_PENDING_NODE_SQL,
                Map.of(
                        "runId", BinaryUuids.toBytes(runId),
                        "nodeKey", nodeKey,
                        "summary", truncate(summary, SUMMARY_MAX_LENGTH),
                        "now", SqlTimestamps.toDatabase(now)));
    }

    /**
     * 为从未开始的节点写 SKIPPED 终态。
     *
     * @param tenantId 租户
     * @param id 节点运行 ID
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param nodeType 节点类型
     * @param nodeName 节点名
     * @param summary 原因摘要
     * @param now 当前时间
     * @return 受影响行数
     */
    public int insertSkippedNode(
            TenantId tenantId,
            UUID id,
            UUID runId,
            String nodeKey,
            WorkflowNodeType nodeType,
            String nodeName,
            String summary,
            Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(id));
        params.put("runId", BinaryUuids.toBytes(runId));
        params.put("nodeKey", nodeKey);
        params.put("nodeType", nodeType.name());
        params.put("nodeName", truncate(nodeName, 200));
        params.put("summary", truncate(summary, SUMMARY_MAX_LENGTH));
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.insert(tenantId, INSERT_SKIPPED_NODE_SQL, params);
    }

    /**
     * 映射审批行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 审批行
     * @throws SQLException 读取失败
     */
    private static WorkflowApprovalRow mapApproval(ResultSet rs, int rowNum) throws SQLException {
        return new WorkflowApprovalRow(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                BinaryUuids.fromBytes(rs.getBytes("workflow_run_id")),
                BinaryUuids.fromBytes(rs.getBytes("workflow_id")),
                rs.getString("workflow_name"),
                rs.getString("node_key"),
                rs.getString("node_name"),
                rs.getInt("attempt"),
                WorkflowApprovalStatus.valueOf(rs.getString("status")),
                WorkflowApprovalAssigneeType.valueOf(rs.getString("assignee_type")),
                rs.getString("assignee_role_code"),
                WorkflowApprovalActionKind.valueOf(rs.getString("action_kind")),
                rs.getString("target_node_key"),
                rs.getString("target_tool_name"),
                rs.getString("params_preview"),
                rs.getString("plan_sha256"),
                BinaryUuids.fromBytes(rs.getBytes("requested_by")),
                rs.getBytes("decided_by") == null ? null : BinaryUuids.fromBytes(rs.getBytes("decided_by")),
                instant(rs.getObject("decided_at", LocalDateTime.class)),
                rs.getString("decision_comment"),
                instant(rs.getObject("created_at", LocalDateTime.class)),
                WorkflowRunScope.valueOf(rs.getString("run_scope")),
                BinaryUuids.fromBytes(rs.getBytes("department_id")));
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
