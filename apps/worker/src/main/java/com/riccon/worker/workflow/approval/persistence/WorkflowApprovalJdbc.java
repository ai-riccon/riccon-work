package com.riccon.worker.workflow.approval.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalInsert;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalParkingSnapshot;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalRow;
import com.riccon.worker.workflow.approval.domain.WorkflowRunApprovalState;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 工作流审批的租户数据访问（ADR-0024 §3.3.5/§5.2/§5.4）。
 *
 * <p>所有语句经 {@link TenantJdbc} 强制 {@code tenant_id}；审批/节点/运行的状态推进全部使用
 * 条件更新，返回 0 表示状态已被并发修改，调用方必须按冲突或租约丢失处理。审批人解析只返回
 * 本租户 {@code ACTIVE} 账号并排除发起人；角色审批不写 {@code workflow_approval_assignee}，
 * 待办由 {@code user_role} 联接解析。
 *
 * <p>密文列只保存信封载荷，key id 与 SHA-256 单独存列；本类不记录、不回显明文或密文。
 */
@Component
public class WorkflowApprovalJdbc {

    /** 错误信息列宽。 */
    private static final int ERROR_MAX_LENGTH = 500;

    /** 运行审批停车快照。 */
    private static final String RUN_PARKING_SQL = """
            SELECT workflow_id, workflow_version_id, requester_user_id, status,
                   current_node_key, lease_owner
              FROM workflow_run
             WHERE tenant_id = :tenantId AND id = :runId
            """;

    /** 运行审批恢复状态。 */
    private static final String RUN_APPROVAL_STATE_SQL = """
            SELECT status, current_node_key, approval_wait_ms
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

    /** 当前 RUNNING 的节点尝试号。 */
    private static final String RUNNING_ATTEMPT_SQL = """
            SELECT attempt
              FROM workflow_node_run
             WHERE tenant_id = :tenantId
               AND workflow_run_id = :runId
               AND node_key = :nodeKey
               AND status = 'RUNNING'
             ORDER BY attempt DESC
             LIMIT 1
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

    /** 审批行投影。 */
    private static final String APPROVAL_SQL = """
            SELECT id, workflow_run_id, workflow_id, node_key, attempt, status,
                   assignee_type, assignee_role_code, action_kind, target_node_key,
                   target_tool_id, target_tool_name, params_preview, params_sha256, plan_sha256,
                   requested_by, decided_by, decided_at, decision_comment, created_at
              FROM workflow_approval
             WHERE tenant_id = :tenantId AND id = :approvalId
            """;

    /** 新建审批；PENDING 与未决定约束由数据库 CHECK 兜底。 */
    private static final String INSERT_APPROVAL_SQL = """
            INSERT INTO workflow_approval (
                tenant_id, id, workflow_run_id, workflow_id, node_key, attempt, status,
                assignee_type, assignee_role_code, action_kind, target_node_key,
                target_tool_id, target_tool_name, params_preview, params_ciphertext,
                params_key_id, params_sha256, plan_sha256, requested_by,
                created_at, updated_at
            ) VALUES (
                :tenantId, :id, :runId, :workflowId, :nodeKey, :attempt, 'PENDING',
                :assigneeType, :assigneeRoleCode, :actionKind, :targetNodeKey,
                :targetToolId, :targetToolName, :paramsPreview, :paramsCiphertext,
                :paramsKeyId, :paramsSha256, :planSha256, :requestedBy,
                :now, :now
            )
            """;

    /** 指定用户审批人。 */
    private static final String INSERT_ASSIGNEE_SQL = """
            INSERT INTO workflow_approval_assignee (tenant_id, approval_id, user_id, created_at)
            VALUES (:tenantId, :approvalId, :userId, :now)
            """;

    /** 审批人是否存在。 */
    private static final String ASSIGNED_SQL = """
            SELECT 1
              FROM workflow_approval_assignee
             WHERE tenant_id = :tenantId AND approval_id = :approvalId AND user_id = :userId
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

    /** 审批节点进入等待态。 */
    private static final String MARK_NODE_WAITING_SQL = """
            UPDATE workflow_node_run
               SET status = 'WAITING_APPROVAL',
                   approval_id = :approvalId,
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND workflow_run_id = :runId
               AND node_key = :nodeKey
               AND attempt = :attempt
               AND status = 'RUNNING'
            """;

    /** 运行进入等待态并释放租约；条件绑定租约持有者与当前节点，防止覆盖其他实例。 */
    private static final String MARK_RUN_WAITING_SQL = """
            UPDATE workflow_run
               SET status = 'WAITING_APPROVAL',
                   current_node_key = :nodeKey,
                   error_code = :errorCode,
                   error_message = :errorMessage,
                   lease_owner = NULL,
                   lease_until = NULL,
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND id = :runId
               AND status = 'RUNNING'
               AND current_node_key = :nodeKey
               AND lease_owner = :leaseOwner
            """;

    /** 批准：PENDING -> APPROVED，条件更新是并发决定/重复决定的唯一守卫。 */
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
     * 读取审批停车所需的运行快照。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @return 快照；运行不存在为空
     */
    public Optional<WorkflowApprovalParkingSnapshot> findParkingSnapshot(TenantId tenantId, UUID runId) {
        return tenantJdbc.queryOne(
                tenantId,
                RUN_PARKING_SQL,
                Map.of("runId", UuidBinary.toBytes(runId)),
                (rs, rowNum) -> new WorkflowApprovalParkingSnapshot(
                        UuidBinary.fromBytes(rs.getBytes("workflow_id")),
                        UuidBinary.fromBytes(rs.getBytes("workflow_version_id")),
                        UuidBinary.fromBytes(rs.getBytes("requester_user_id")),
                        WorkflowRunStatus.valueOf(rs.getString("status")),
                        rs.getString("current_node_key"),
                        rs.getString("lease_owner")));
    }

    /**
     * 读取运行审批恢复状态。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @return 状态；运行不存在为空
     */
    public Optional<WorkflowRunApprovalState> findRunApprovalState(TenantId tenantId, UUID runId) {
        return tenantJdbc.queryOne(
                tenantId,
                RUN_APPROVAL_STATE_SQL,
                Map.of("runId", UuidBinary.toBytes(runId)),
                (rs, rowNum) -> new WorkflowRunApprovalState(
                        WorkflowRunStatus.valueOf(rs.getString("status")),
                        rs.getString("current_node_key"),
                        rs.getLong("approval_wait_ms")));
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
                Map.of("runId", UuidBinary.toBytes(runId)),
                (rs, rowNum) -> rs.getString("graph_json"));
    }

    /**
     * 读取当前 RUNNING 的节点尝试号。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @return 尝试号；节点不是 RUNNING 为空
     */
    public Optional<Integer> findRunningAttempt(TenantId tenantId, UUID runId, String nodeKey) {
        return tenantJdbc.queryOne(
                tenantId,
                RUNNING_ATTEMPT_SQL,
                Map.of("runId", UuidBinary.toBytes(runId), "nodeKey", nodeKey),
                (rs, rowNum) -> rs.getInt("attempt"));
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
                        Map.of("runId", UuidBinary.toBytes(runId), "nodeKey", nodeKey),
                        (rs, rowNum) -> Boolean.TRUE)
                .isPresent();
    }

    /**
     * 读取审批行。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @return 审批行；不存在为空
     */
    public Optional<WorkflowApprovalRow> findApproval(TenantId tenantId, UUID approvalId) {
        return tenantJdbc.queryOne(
                tenantId, APPROVAL_SQL, Map.of("approvalId", UuidBinary.toBytes(approvalId)), (rs, rowNum) -> {
                    LocalDateTime decidedAt = rs.getObject("decided_at", LocalDateTime.class);
                    LocalDateTime createdAt = rs.getObject("created_at", LocalDateTime.class);
                    return new WorkflowApprovalRow(
                            UuidBinary.fromBytes(rs.getBytes("id")),
                            UuidBinary.fromBytes(rs.getBytes("workflow_run_id")),
                            UuidBinary.fromBytes(rs.getBytes("workflow_id")),
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
                            rs.getString("params_sha256"),
                            rs.getString("plan_sha256"),
                            UuidBinary.fromBytes(rs.getBytes("requested_by")),
                            rs.getBytes("decided_by") == null ? null : UuidBinary.fromBytes(rs.getBytes("decided_by")),
                            decidedAt == null ? null : decidedAt.toInstant(ZoneOffset.UTC),
                            rs.getString("decision_comment"),
                            createdAt.toInstant(ZoneOffset.UTC));
                });
    }

    /**
     * 插入 PENDING 审批。
     *
     * @param tenantId 租户
     * @param insert 审批参数
     * @return 受影响行数
     */
    public int insertApproval(TenantId tenantId, WorkflowApprovalInsert insert) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(insert.id()));
        params.put("runId", UuidBinary.toBytes(insert.workflowRunId()));
        params.put("workflowId", UuidBinary.toBytes(insert.workflowId()));
        params.put("nodeKey", insert.nodeKey());
        params.put("attempt", insert.attempt());
        params.put("assigneeType", insert.assigneeType().name());
        params.put("assigneeRoleCode", insert.assigneeRoleCode());
        params.put("actionKind", insert.actionKind().name());
        params.put("targetNodeKey", insert.targetNodeKey());
        params.put("targetToolId", insert.targetToolId() == null ? null : UuidBinary.toBytes(insert.targetToolId()));
        params.put("targetToolName", insert.targetToolName());
        params.put("paramsPreview", insert.paramsPreview());
        params.put("paramsCiphertext", insert.paramsCiphertext());
        params.put("paramsKeyId", insert.paramsKeyId());
        params.put("paramsSha256", insert.paramsSha256());
        params.put("planSha256", insert.planSha256());
        params.put("requestedBy", UuidBinary.toBytes(insert.requestedBy()));
        params.put("now", databaseTime(insert.now()));
        return tenantJdbc.insert(tenantId, INSERT_APPROVAL_SQL, params);
    }

    /**
     * 写入指定用户审批人。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @param userIds 审批人 ID
     * @param now 当前时间
     * @return 写入行数
     */
    public int insertAssignees(TenantId tenantId, UUID approvalId, Collection<UUID> userIds, Instant now) {
        int inserted = 0;
        for (UUID userId : userIds) {
            inserted += tenantJdbc.insert(
                    tenantId,
                    INSERT_ASSIGNEE_SQL,
                    Map.of(
                            "approvalId", UuidBinary.toBytes(approvalId),
                            "userId", UuidBinary.toBytes(userId),
                            "now", databaseTime(now)));
        }
        return inserted;
    }

    /**
     * 判断用户是否被显式指派。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @param userId 用户 ID
     * @return 是否被指派
     */
    public boolean isAssignedUser(TenantId tenantId, UUID approvalId, UUID userId) {
        return tenantJdbc
                .queryOne(
                        tenantId,
                        ASSIGNED_SQL,
                        Map.of("approvalId", UuidBinary.toBytes(approvalId), "userId", UuidBinary.toBytes(userId)),
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
                        Map.of("userId", UuidBinary.toBytes(userId)),
                        (rs, rowNum) -> Boolean.TRUE)
                .isPresent();
    }

    /**
     * 判断账号是否拥有指定角色。
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
                        Map.of("userId", UuidBinary.toBytes(userId), "roleCode", roleCode),
                        (rs, rowNum) -> Boolean.TRUE)
                .isPresent();
    }

    /**
     * 解析可选用户审批人：本租户 ACTIVE，排除发起人。
     *
     * @param tenantId 租户
     * @param userIds 节点配置的用户 ID
     * @param excludeUserId 发起人
     * @return 可用用户 ID，按查询顺序；没有时为空列表
     */
    public List<UUID> findActiveUserAssignees(TenantId tenantId, Collection<UUID> userIds, UUID excludeUserId) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT id
                  FROM app_user
                 WHERE tenant_id = :tenantId
                   AND status = 'ACTIVE'
                   AND id <> :excludeUserId
                   AND id IN (""");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("excludeUserId", UuidBinary.toBytes(excludeUserId));
        int index = 0;
        for (UUID userId : userIds) {
            if (index > 0) {
                sql.append(", ");
            }
            String name = "userId" + index;
            sql.append(':').append(name);
            params.put(name, UuidBinary.toBytes(userId));
            index++;
        }
        sql.append(") ORDER BY id");
        return tenantJdbc.query(
                tenantId, sql.toString(), params, (rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes("id")));
    }

    /**
     * 解析角色审批人：本租户 ACTIVE 且拥有该角色，排除发起人。
     *
     * <p>角色审批不在 {@code workflow_approval_assignee} 写行；结果只用于审计计数与计划哈希，
     * 上限 500 行是防御性边界（租户管理员/普通成员规模远小于此）。
     *
     * @param tenantId 租户
     * @param roleCode 角色码
     * @param excludeUserId 发起人
     * @return 可用用户 ID；没有时为空列表
     */
    public List<UUID> findActiveRoleAssignees(TenantId tenantId, String roleCode, UUID excludeUserId) {
        return tenantJdbc.query(
                tenantId,
                """
                SELECT DISTINCT u.id
                  FROM app_user u
                  JOIN user_role ur
                    ON ur.tenant_id = u.tenant_id AND ur.user_id = u.id
                  JOIN role r
                    ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
                 WHERE u.tenant_id = :tenantId
                   AND u.status = 'ACTIVE'
                   AND r.code = :roleCode
                   AND u.id <> :excludeUserId
                 ORDER BY u.id
                 LIMIT 500
                """,
                Map.of("roleCode", roleCode, "excludeUserId", UuidBinary.toBytes(excludeUserId)),
                (rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes("id")));
    }

    /**
     * 审批节点进入 WAITING_APPROVAL。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param attempt 尝试号
     * @param approvalId 审批 ID
     * @param now 当前时间
     * @return 受影响行数；0 表示尝试已不是 RUNNING
     */
    public int markNodeWaiting(
            TenantId tenantId, UUID runId, String nodeKey, int attempt, UUID approvalId, Instant now) {
        return tenantJdbc.update(
                tenantId,
                MARK_NODE_WAITING_SQL,
                Map.of(
                        "runId", UuidBinary.toBytes(runId),
                        "nodeKey", nodeKey,
                        "attempt", attempt,
                        "approvalId", UuidBinary.toBytes(approvalId),
                        "now", databaseTime(now)));
    }

    /**
     * 运行进入 WAITING_APPROVAL 并释放租约。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 当前节点
     * @param leaseOwner 本实例租约
     * @param errorCode 稳定错误码；可空
     * @param errorMessage 脱敏原因；可空
     * @param now 当前时间
     * @return 受影响行数；0 表示租约/状态已变化
     */
    public int markRunWaiting(
            TenantId tenantId,
            UUID runId,
            String nodeKey,
            String leaseOwner,
            String errorCode,
            String errorMessage,
            Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("runId", UuidBinary.toBytes(runId));
        params.put("nodeKey", nodeKey);
        params.put("leaseOwner", leaseOwner);
        params.put("errorCode", errorCode);
        params.put("errorMessage", truncate(errorMessage, ERROR_MAX_LENGTH));
        params.put("now", databaseTime(now));
        return tenantJdbc.update(tenantId, MARK_RUN_WAITING_SQL, params);
    }

    /**
     * 条件批准。
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
        params.put("approvalId", UuidBinary.toBytes(approvalId));
        params.put("decidedBy", UuidBinary.toBytes(decidedBy));
        params.put("comment", comment);
        params.put("now", databaseTime(now));
        return tenantJdbc.update(tenantId, APPROVE_SQL, params);
    }

    /**
     * 条件拒绝。
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
        params.put("approvalId", UuidBinary.toBytes(approvalId));
        params.put("decidedBy", UuidBinary.toBytes(decidedBy));
        params.put("comment", comment);
        params.put("now", databaseTime(now));
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
     * @param errorCode 错误码；可空
     * @param errorMessage 原因；可空
     * @param now 当前时间
     * @return 受影响行数；0 表示节点已不是 WAITING_APPROVAL
     */
    public int finishApprovalNode(
            TenantId tenantId,
            UUID runId,
            String nodeKey,
            int attempt,
            String status,
            String outputJson,
            String outputSummary,
            String errorCode,
            String errorMessage,
            Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("runId", UuidBinary.toBytes(runId));
        params.put("nodeKey", nodeKey);
        params.put("attempt", attempt);
        params.put("status", status);
        params.put("outputJson", outputJson);
        params.put("outputSummary", outputSummary);
        params.put("errorCode", errorCode);
        params.put("errorMessage", truncate(errorMessage, ERROR_MAX_LENGTH));
        params.put("now", databaseTime(now));
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
                        "runId", UuidBinary.toBytes(runId),
                        "targetNodeKey", targetNodeKey,
                        "waitMs", Math.max(0L, waitMs),
                        "now", databaseTime(now)));
    }

    /**
     * 拒绝后运行失败。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 审批节点
     * @param message 脱敏原因
     * @param waitMs 本次等待毫秒
     * @param now 当前时间
     * @return 受影响行数；0 表示运行已不等待审批
     */
    public int failRunRejected(
            TenantId tenantId, UUID runId, String nodeKey, String message, long waitMs, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("runId", UuidBinary.toBytes(runId));
        params.put("nodeKey", nodeKey);
        params.put("errorMessage", truncate(message, ERROR_MAX_LENGTH));
        params.put("waitMs", Math.max(0L, waitMs));
        params.put("now", databaseTime(now));
        return tenantJdbc.update(tenantId, FAIL_RUN_REJECTED_SQL, params);
    }

    /**
     * 把 UTC Instant 转为数据库 {@code DATETIME(6)}（MySQL 列按 UTC 存储）。
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
