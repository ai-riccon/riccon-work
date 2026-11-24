package com.riccon.api.workflow.run.persistence;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.api.workflow.run.domain.WorkflowRunInsert;
import com.riccon.api.workflow.run.domain.WorkflowRunNodeInsert;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowDefinitionStatus;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code workflow_run}/{@code workflow_node_run}/{@code workflow_approval} 的运行准入 SQL。
 *
 * <p>所有语句经 {@link TenantJdbc} 强制 {@code tenant_id} 条件；本类不暴露跨租户读取，也不读取
 * {@code inputs_json}/{@code result_json} 原文。并发计数在调用方持有的租户行锁内执行，保证“计数 + 插入”
 * 不被同租户其他创建/重试事务穿插（ADR-0024 §4.8）。
 *
 * <p>运行状态推进使用条件更新（精确匹配前一状态），受影响行数为 0 时由服务层重新读取并区分幂等与冲突；
 * 取消先更新待审批再条件更新运行，与审批决定的“审批行 → 运行行”顺序一致，降低死锁概率。
 */
@Component
public class WorkflowRunJdbc {

    /** 运行读取列；流程名来自 JOIN，用于创建响应与幂等重放。 */
    private static final String RUN_COLUMNS = """
            r.id, r.workflow_id, r.workflow_version_id, r.version_no, r.requester_user_id, r.status,
            r.current_node_key, r.error_code, r.error_message, r.result_status,
            r.retention_expires_at, r.created_at, r.updated_at, r.started_at, r.finished_at,
            r.idempotency_key, d.name AS workflow_name
            """;

    /** 运行 FROM 与流程定义 JOIN。 */
    private static final String RUN_FROM = """
             FROM workflow_run r
             JOIN workflow_definition d
               ON d.tenant_id = r.tenant_id AND d.id = r.workflow_id
            """;

    /** 活跃运行状态；审批等待仍占用租户准入并发（ADR-0024 §4.8）。 */
    private static final String ACTIVE_STATUSES = "('PENDING', 'RUNNING', 'WAITING_APPROVAL')";

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建运行 SQL 组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public WorkflowRunJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 按 ID 读取运行。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @return 运行行
     */
    public Optional<WorkflowRunRow> findById(TenantId tenantId, UUID runId) {
        return tenantJdbc.queryOne(
                tenantId,
                "SELECT " + RUN_COLUMNS + RUN_FROM + " WHERE r.tenant_id = :tenantId AND r.id = :runId",
                Map.of("runId", BinaryUuids.toBytes(runId)),
                WorkflowRunJdbc::mapRun);
    }

    /**
     * 按创建幂等键读取既有运行。
     *
     * <p>唯一键为 {@code (tenant_id, workflow_id, requester_user_id, idempotency_key)}，因此同键只可能
     * 命中同一主体在同一流程创建的那一条运行；查询在租户行锁之后执行，避免并发重复插入
     * （ADR-0024 §4.6/§7.4）。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @param requesterUserId 发起人
     * @param idempotencyKey 幂等键
     * @return 既有运行
     */
    public Optional<WorkflowRunRow> findByIdempotencyKey(
            TenantId tenantId, UUID workflowId, UUID requesterUserId, String idempotencyKey) {
        return tenantJdbc.queryOne(
                tenantId,
                "SELECT " + RUN_COLUMNS + RUN_FROM + """
                         WHERE r.tenant_id = :tenantId
                           AND r.workflow_id = :workflowId
                           AND r.requester_user_id = :requesterUserId
                           AND r.idempotency_key = :idempotencyKey
                        """,
                Map.of(
                        "workflowId", BinaryUuids.toBytes(workflowId),
                        "requesterUserId", BinaryUuids.toBytes(requesterUserId),
                        "idempotencyKey", idempotencyKey),
                WorkflowRunJdbc::mapRun);
    }

    /**
     * 读取流程定义摘要。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @return 定义行
     */
    public Optional<WorkflowRunDefinitionRow> findDefinition(TenantId tenantId, UUID workflowId) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT d.id, d.name, d.owner_user_id, d.status, d.published_version_id
                  FROM workflow_definition d
                 WHERE d.tenant_id = :tenantId AND d.id = :workflowId
                """,
                Map.of("workflowId", BinaryUuids.toBytes(workflowId)),
                (rs, rowNum) -> new WorkflowRunDefinitionRow(
                        BinaryUuids.fromBytes(rs.getBytes("id")),
                        rs.getString("name"),
                        BinaryUuids.fromBytes(rs.getBytes("owner_user_id")),
                        WorkflowDefinitionStatus.valueOf(rs.getString("status")),
                        BinaryUuids.fromBytes(rs.getBytes("published_version_id"))));
    }

    /**
     * 读取发布版本行（含图 JSON）。
     *
     * @param tenantId 当前租户
     * @param versionId 版本 ID
     * @return 版本行
     */
    public Optional<WorkflowRunVersionRow> findVersion(TenantId tenantId, UUID versionId) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT v.id, v.workflow_id, v.version_no, v.run_scope, v.department_id, v.node_count, v.graph_json
                  FROM workflow_version v
                 WHERE v.tenant_id = :tenantId AND v.id = :versionId
                """,
                Map.of("versionId", BinaryUuids.toBytes(versionId)),
                (rs, rowNum) -> new WorkflowRunVersionRow(
                        BinaryUuids.fromBytes(rs.getBytes("id")),
                        BinaryUuids.fromBytes(rs.getBytes("workflow_id")),
                        rs.getInt("version_no"),
                        WorkflowRunScope.valueOf(rs.getString("run_scope")),
                        BinaryUuids.fromBytes(rs.getBytes("department_id")),
                        rs.getInt("node_count"),
                        rs.getString("graph_json")));
    }

    /**
     * 统计租户内活跃运行数。
     *
     * @param tenantId 当前租户
     * @return 活跃运行数
     */
    public long countActiveTenant(TenantId tenantId) {
        return tenantJdbc.count(
                tenantId,
                "SELECT COUNT(*) FROM workflow_run r WHERE r.tenant_id = :tenantId AND r.status IN " + ACTIVE_STATUSES,
                Map.of());
    }

    /**
     * 统计单流程活跃运行数。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @return 活跃运行数
     */
    public long countActiveWorkflow(TenantId tenantId, UUID workflowId) {
        return tenantJdbc.count(
                tenantId,
                "SELECT COUNT(*) FROM workflow_run r WHERE r.tenant_id = :tenantId AND r.workflow_id = :workflowId"
                        + " AND r.status IN " + ACTIVE_STATUSES,
                Map.of("workflowId", BinaryUuids.toBytes(workflowId)));
    }

    /**
     * 插入待执行运行。
     *
     * @param tenantId 当前租户
     * @param insert 插入参数
     */
    public void insertRun(TenantId tenantId, WorkflowRunInsert insert) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(insert.id()));
        params.put("workflowId", BinaryUuids.toBytes(insert.workflowId()));
        params.put("workflowVersionId", BinaryUuids.toBytes(insert.workflowVersionId()));
        params.put("versionNo", insert.versionNo());
        params.put("requesterUserId", BinaryUuids.toBytes(insert.requesterUserId()));
        params.put("inputsJson", insert.inputsJson());
        params.put("currentNodeKey", insert.currentNodeKey());
        params.put("idempotencyKey", insert.idempotencyKey());
        params.put("retentionExpiresAt", SqlTimestamps.toDatabase(insert.retentionExpiresAt()));
        params.put("now", SqlTimestamps.toDatabase(insert.createdAt()));
        tenantJdbc.insert(tenantId, """
                INSERT INTO workflow_run (
                    tenant_id, id, workflow_id, workflow_version_id, version_no, requester_user_id,
                    status, inputs_json, result_status, result_size_bytes, current_node_key,
                    execution_ms, approval_wait_ms, claim_attempts, idempotency_key,
                    retention_expires_at, created_at, updated_at
                ) VALUES (
                    :tenantId, :id, :workflowId, :workflowVersionId, :versionNo, :requesterUserId,
                    'PENDING', :inputsJson, 'NONE', 0, :currentNodeKey,
                    0, 0, 0, :idempotencyKey,
                    :retentionExpiresAt, :now, :now
                )
                """, params);
    }

    /**
     * 条件取消运行。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param expectedStatus 读取时的状态
     * @param now 当前时间
     * @return 受影响行数；0 表示状态已被并发改变
     */
    public int markCancelled(TenantId tenantId, UUID runId, WorkflowRunStatus expectedStatus, Instant now) {
        return tenantJdbc.update(
                tenantId,
                """
                UPDATE workflow_run
                   SET status = 'CANCELLED',
                       error_code = 'WF_CANCELLED',
                       error_message = NULL,
                       finished_at = :now,
                       lease_owner = NULL,
                       lease_until = NULL,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :runId AND status = :expectedStatus
                """,
                Map.of(
                        "runId", BinaryUuids.toBytes(runId),
                        "expectedStatus", expectedStatus.name(),
                        "now", SqlTimestamps.toDatabase(now)));
    }

    /**
     * 取消运行下所有待审批项。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param now 当前时间
     * @return 受影响行数
     */
    public int cancelPendingApprovals(TenantId tenantId, UUID runId, Instant now) {
        return tenantJdbc.update(
                tenantId, """
                UPDATE workflow_approval
                   SET status = 'CANCELLED',
                       updated_at = :now
                 WHERE tenant_id = :tenantId
                   AND workflow_run_id = :runId
                   AND status = 'PENDING'
                """, Map.of("runId", BinaryUuids.toBytes(runId), "now", SqlTimestamps.toDatabase(now)));
    }

    /**
     * 按失败节点恢复运行到待执行。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param currentNodeKey 失败节点 key
     * @param now 当前时间
     * @return 受影响行数；0 表示运行已不是 FAILED
     */
    public int markRetried(TenantId tenantId, UUID runId, String currentNodeKey, Instant now) {
        return tenantJdbc.update(
                tenantId,
                """
                UPDATE workflow_run
                   SET status = 'PENDING',
                       current_node_key = :currentNodeKey,
                       error_code = NULL,
                       error_message = NULL,
                       finished_at = NULL,
                       lease_owner = NULL,
                       lease_until = NULL,
                       claim_attempts = 0,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :runId AND status = 'FAILED'
                """,
                Map.of(
                        "runId", BinaryUuids.toBytes(runId),
                        "currentNodeKey", currentNodeKey,
                        "now", SqlTimestamps.toDatabase(now)));
    }

    /**
     * 读取某节点最新尝试。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @return 最新尝试行
     */
    public Optional<WorkflowRunNodeRow> findLatestNode(TenantId tenantId, UUID runId, String nodeKey) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT id, node_key, node_type, node_name, attempt, status
                  FROM workflow_node_run
                 WHERE tenant_id = :tenantId
                   AND workflow_run_id = :runId
                   AND node_key = :nodeKey
                 ORDER BY attempt DESC
                 LIMIT 1
                """,
                Map.of("runId", BinaryUuids.toBytes(runId), "nodeKey", nodeKey),
                (rs, rowNum) -> new WorkflowRunNodeRow(
                        BinaryUuids.fromBytes(rs.getBytes("id")),
                        rs.getString("node_key"),
                        WorkflowNodeType.valueOf(rs.getString("node_type")),
                        rs.getString("node_name"),
                        rs.getInt("attempt"),
                        WorkflowNodeStatus.valueOf(rs.getString("status"))));
    }

    /**
     * 插入新的节点尝试。
     *
     * @param tenantId 当前租户
     * @param insert 插入参数
     */
    public void insertNodeAttempt(TenantId tenantId, WorkflowRunNodeInsert insert) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(insert.id()));
        params.put("workflowRunId", BinaryUuids.toBytes(insert.workflowRunId()));
        params.put("nodeKey", insert.nodeKey());
        params.put("nodeType", insert.nodeType().name());
        params.put("nodeName", insert.nodeName());
        params.put("attempt", insert.attempt());
        params.put("now", SqlTimestamps.toDatabase(insert.createdAt()));
        tenantJdbc.insert(tenantId, """
                INSERT INTO workflow_node_run (
                    tenant_id, id, workflow_run_id, node_key, node_type, node_name, attempt, status,
                    created_at, updated_at
                ) VALUES (
                    :tenantId, :id, :workflowRunId, :nodeKey, :nodeType, :nodeName, :attempt, 'PENDING',
                    :now, :now
                )
                """, params);
    }

    /**
     * 把指定下游节点中已跳过的恢复为待执行。
     *
     * <p>只恢复失败节点的可达下游；条件分支未走的 {@code SKIPPED} 节点不在下游集合内，必须保持跳过，
     * 否则重试会错误执行未选分支（ADR-0024 §4.6）。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param nodeKeys 失败节点的可达下游节点 key；不能为空
     * @param now 当前时间
     * @return 受影响行数
     */
    public int resetSkippedNodes(TenantId tenantId, UUID runId, Set<String> nodeKeys, Instant now) {
        return tenantJdbc.update(
                tenantId, """
                UPDATE workflow_node_run
                   SET status = 'PENDING',
                       started_at = NULL,
                       finished_at = NULL,
                       duration_ms = NULL,
                       error_code = NULL,
                       error_message = NULL,
                       updated_at = :now
                 WHERE tenant_id = :tenantId
                   AND workflow_run_id = :runId
                   AND node_key IN (:nodeKeys)
                   AND status = 'SKIPPED'
                """, Map.of(
                        "runId", BinaryUuids.toBytes(runId),
                        "nodeKeys", nodeKeys,
                        "now", SqlTimestamps.toDatabase(now)));
    }

    /**
     * 映射运行行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 运行行
     * @throws SQLException 读取失败
     */
    private static WorkflowRunRow mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new WorkflowRunRow(
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
                instant(rs.getObject("created_at", LocalDateTime.class)),
                instant(rs.getObject("updated_at", LocalDateTime.class)),
                instant(rs.getObject("started_at", LocalDateTime.class)),
                instant(rs.getObject("finished_at", LocalDateTime.class)),
                instant(rs.getObject("retention_expires_at", LocalDateTime.class)),
                rs.getString("idempotency_key"));
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
            throw new IllegalStateException("unknown workflow_run.error_code: " + value, exception);
        }
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
