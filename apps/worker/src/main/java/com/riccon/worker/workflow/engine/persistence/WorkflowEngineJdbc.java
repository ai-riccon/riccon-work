package com.riccon.worker.workflow.engine.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.platform.domain.workflow.WorkflowResultStatus;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.workflow.engine.domain.WorkflowEngineLimits;
import com.riccon.worker.workflow.engine.domain.WorkflowNodeAttemptInsert;
import com.riccon.worker.workflow.engine.domain.WorkflowNodeAttemptRow;
import com.riccon.worker.workflow.engine.domain.WorkflowNodeFinish;
import com.riccon.worker.workflow.engine.domain.WorkflowRunGraphSnapshot;
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
 * 串行引擎的租户数据访问（ADR-0024 §4.2/§4.6）。
 *
 * <p>所有语句经 {@link TenantJdbc} 强制 {@code tenant_id}；运行终态与当前节点推进都带
 * {@code status='RUNNING'} + {@code lease_owner} 条件，返回 0 表示租约已丢失，调用方必须丢弃结果。
 * 节点尝试唯一键 {@code (tenant_id, workflow_run_id, node_key, attempt)} 是重复执行的最后守卫。
 *
 * <p>图快照读取用 {@code (tenant_id, version, workflow)} 复合绑定校验版本属于运行；MySQL JSON 列
 * 会规范化键序/空白，不能逐字节重算 {@code graph_sha256}，改由引擎校验
 * {@code v.node_count} 与解析节点数一致。
 */
@Component
public class WorkflowEngineJdbc {

    /** 错误信息列宽。 */
    private static final int ERROR_MAX_LENGTH = WorkflowEngineLimits.ERROR_MESSAGE_MAX_LENGTH;

    /** 摘要列宽。 */
    private static final int SUMMARY_MAX_LENGTH = WorkflowEngineLimits.SUMMARY_MAX_LENGTH;

    /** 运行 + 版本图快照。 */
    private static final String RUN_GRAPH_SQL = """
            SELECT r.workflow_id, r.workflow_version_id, r.requester_user_id, r.status,
                   r.inputs_json, r.current_node_key, r.execution_ms,
                   v.node_count, v.graph_json
              FROM workflow_run r
              JOIN workflow_version v
                ON v.tenant_id = r.tenant_id
               AND v.id = r.workflow_version_id
               AND v.workflow_id = r.workflow_id
             WHERE r.tenant_id = :tenantId AND r.id = :runId
            """;

    /** 节点尝试时间线（按 key/attempt 升序）。 */
    private static final String NODE_ATTEMPTS_SQL = """
            SELECT id, node_key, node_type, node_name, attempt, status,
                   input_summary, output_summary, output_json, citations_json,
                   error_code, error_message
              FROM workflow_node_run
             WHERE tenant_id = :tenantId AND workflow_run_id = :runId
             ORDER BY node_key ASC, attempt ASC
            """;

    /** 插入节点尝试（PENDING 或直接终态）。 */
    private static final String INSERT_NODE_ATTEMPT_SQL = """
            INSERT INTO workflow_node_run (
                tenant_id, id, workflow_run_id, node_key, node_type, node_name, attempt, status,
                input_summary, output_summary, error_code, error_message,
                started_at, finished_at, duration_ms, created_at, updated_at
            ) VALUES (
                :tenantId, :id, :runId, :nodeKey, :nodeType, :nodeName, :attempt, :status,
                :inputSummary, :outputSummary, :errorCode, :errorMessage,
                CASE WHEN :status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED', 'CANCELLED')
                     THEN :now ELSE NULL END,
                CASE WHEN :status IN ('SUCCEEDED', 'FAILED', 'SKIPPED', 'CANCELLED')
                     THEN :now ELSE NULL END,
                CASE WHEN :status IN ('SUCCEEDED', 'FAILED', 'SKIPPED', 'CANCELLED')
                     THEN 0 ELSE NULL END,
                :now, :now
            )
            """;

    /** 节点开始：PENDING -> RUNNING。 */
    private static final String START_NODE_ATTEMPT_SQL = """
            UPDATE workflow_node_run
               SET status = 'RUNNING',
                   started_at = :now,
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND workflow_run_id = :runId
               AND node_key = :nodeKey
               AND attempt = :attempt
               AND status = 'PENDING'
            """;

    /** 节点终态写入；只允许 RUNNING -> 终态。 */
    private static final String FINISH_NODE_ATTEMPT_SQL = """
            UPDATE workflow_node_run
               SET status = :status,
                   input_summary = COALESCE(:inputSummary, input_summary),
                   output_summary = :outputSummary,
                   output_json = :outputJson,
                   citations_json = :citationsJson,
                   error_code = :errorCode,
                   error_message = :errorMessage,
                   finished_at = :finishedAt,
                   updated_at = :finishedAt,
                   duration_ms = CASE
                         WHEN started_at IS NULL THEN 0
                         ELSE GREATEST(0, TIMESTAMPDIFF(MICROSECOND, started_at, :finishedAt) DIV 1000)
                       END
             WHERE tenant_id = :tenantId
               AND workflow_run_id = :runId
               AND node_key = :nodeKey
               AND attempt = :attempt
               AND status = 'RUNNING'
            """;

    /** 崩溃遗留的 RUNNING 尝试置为 WF_INTERNAL 失败，允许下一尝试重新执行。 */
    private static final String FAIL_STALE_ATTEMPT_SQL = """
            UPDATE workflow_node_run
               SET status = 'FAILED',
                   error_code = 'WF_INTERNAL',
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
               AND status = 'RUNNING'
            """;

    /** 未执行节点：PENDING -> SKIPPED。 */
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

    /** 未开始节点：PENDING -> FAILED/CANCELLED（步数/运行超时在节点开始前终止）。 */
    private static final String CLOSE_PENDING_NODE_SQL = """
            UPDATE workflow_node_run
               SET status = :status,
                   error_code = :errorCode,
                   error_message = :errorMessage,
                   finished_at = :now,
                   updated_at = :now,
                   duration_ms = 0
             WHERE tenant_id = :tenantId
               AND workflow_run_id = :runId
               AND node_key = :nodeKey
               AND status = 'PENDING'
            """;

    /** 取消运行后，把剩余未执行/执行中节点置为 CANCELLED。 */
    private static final String CANCEL_REMAINING_NODES_SQL = """
            UPDATE workflow_node_run
               SET status = 'CANCELLED',
                   error_code = 'WF_CANCELLED',
                   error_message = :errorMessage,
                   finished_at = :now,
                   updated_at = :now,
                   duration_ms = CASE
                         WHEN started_at IS NULL THEN 0
                         ELSE GREATEST(0, TIMESTAMPDIFF(MICROSECOND, started_at, :now) DIV 1000)
                       END
             WHERE tenant_id = :tenantId
               AND workflow_run_id = :runId
               AND status IN ('PENDING', 'RUNNING')
            """;

    /** 推进当前节点；租约丢失时返回 0。 */
    private static final String UPDATE_CURRENT_NODE_SQL = """
            UPDATE workflow_run
               SET current_node_key = :nodeKey,
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND id = :runId
               AND status = 'RUNNING'
               AND lease_owner = :leaseOwner
            """;

    /** 累加执行时间；审批等待由 T07a 单独累加 approval_wait_ms。 */
    private static final String ADD_EXECUTION_MS_SQL = """
            UPDATE workflow_run
               SET execution_ms = execution_ms + :deltaMs,
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND id = :runId
               AND status = 'RUNNING'
               AND lease_owner = :leaseOwner
            """;

    /** 运行成功并写最终结果。 */
    private static final String FINISH_RUN_SUCCEEDED_SQL = """
            UPDATE workflow_run
               SET status = 'SUCCEEDED',
                   result_json = :resultJson,
                   result_status = 'SAVED',
                   result_size_bytes = :resultSizeBytes,
                   current_node_key = :nodeKey,
                   error_code = NULL,
                   error_message = NULL,
                   execution_ms = execution_ms + :deltaMs,
                   finished_at = :now,
                   lease_owner = NULL,
                   lease_until = NULL,
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND id = :runId
               AND status = 'RUNNING'
               AND lease_owner = :leaseOwner
            """;

    /** 运行失败终态。 */
    private static final String FINISH_RUN_FAILED_SQL = """
            UPDATE workflow_run
               SET status = 'FAILED',
                   error_code = :errorCode,
                   error_message = :errorMessage,
                   result_status = :resultStatus,
                   result_size_bytes = :resultSizeBytes,
                   current_node_key = :nodeKey,
                   execution_ms = execution_ms + :deltaMs,
                   finished_at = :now,
                   lease_owner = NULL,
                   lease_until = NULL,
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND id = :runId
               AND status = 'RUNNING'
               AND lease_owner = :leaseOwner
            """;

    /** 关联工作流运行到模型调用（ADR-0024 §4.10）。 */
    private static final String LINK_MODEL_INVOCATION_SQL = """
            UPDATE model_invocation
               SET workflow_run_id = :workflowRunId,
                   workflow_node_run_id = :workflowNodeRunId
             WHERE tenant_id = :tenantId AND id = :invocationId
            """;

    /** 租户数据访问。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建引擎持久化组件。
     *
     * @param tenantJdbc 租户数据访问
     */
    public WorkflowEngineJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 读取运行与发布图快照。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @return 快照；不存在为空
     */
    public Optional<WorkflowRunGraphSnapshot> findRunGraph(TenantId tenantId, UUID runId) {
        return tenantJdbc.queryOne(
                tenantId,
                RUN_GRAPH_SQL,
                Map.of("runId", UuidBinary.toBytes(runId)),
                (rs, rowNum) -> new WorkflowRunGraphSnapshot(
                        runId,
                        UuidBinary.fromBytes(rs.getBytes("workflow_id")),
                        UuidBinary.fromBytes(rs.getBytes("workflow_version_id")),
                        UuidBinary.fromBytes(rs.getBytes("requester_user_id")),
                        WorkflowRunStatus.valueOf(rs.getString("status")),
                        rs.getString("inputs_json"),
                        rs.getString("current_node_key"),
                        rs.getLong("execution_ms"),
                        rs.getInt("node_count"),
                        rs.getString("graph_json")));
    }

    /**
     * 读取运行的节点尝试。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @return 尝试列表
     */
    public List<WorkflowNodeAttemptRow> findNodeAttempts(TenantId tenantId, UUID runId) {
        return tenantJdbc.query(
                tenantId,
                NODE_ATTEMPTS_SQL,
                Map.of("runId", UuidBinary.toBytes(runId)),
                (rs, rowNum) -> new WorkflowNodeAttemptRow(
                        UuidBinary.fromBytes(rs.getBytes("id")),
                        rs.getString("node_key"),
                        WorkflowNodeType.valueOf(rs.getString("node_type")),
                        rs.getString("node_name"),
                        rs.getInt("attempt"),
                        WorkflowNodeStatus.valueOf(rs.getString("status")),
                        rs.getString("input_summary"),
                        rs.getString("output_summary"),
                        rs.getString("output_json"),
                        rs.getString("citations_json"),
                        rs.getString("error_code"),
                        rs.getString("error_message")));
    }

    /**
     * 插入节点尝试。
     *
     * @param tenantId 租户
     * @param insert 插入参数
     * @return 受影响行数
     */
    public int insertNodeAttempt(TenantId tenantId, WorkflowNodeAttemptInsert insert) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(insert.id()));
        params.put("runId", UuidBinary.toBytes(insert.runId()));
        params.put("nodeKey", insert.nodeKey());
        params.put("nodeType", insert.nodeType().name());
        params.put("nodeName", truncate(insert.nodeName(), 200));
        params.put("attempt", insert.attempt());
        params.put("status", insert.status().name());
        params.put("inputSummary", truncate(insert.inputSummary(), SUMMARY_MAX_LENGTH));
        params.put("outputSummary", truncate(insert.outputSummary(), SUMMARY_MAX_LENGTH));
        params.put("errorCode", insert.errorCode());
        params.put("errorMessage", truncate(insert.errorMessage(), ERROR_MAX_LENGTH));
        params.put("now", databaseTime(insert.now()));
        return tenantJdbc.insert(tenantId, INSERT_NODE_ATTEMPT_SQL, params);
    }

    /**
     * 节点开始：条件更新 PENDING -> RUNNING。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param attempt 尝试号
     * @param now 当前时间
     * @return 受影响行数
     */
    public int startNodeAttempt(TenantId tenantId, UUID runId, String nodeKey, int attempt, Instant now) {
        return tenantJdbc.update(
                tenantId,
                START_NODE_ATTEMPT_SQL,
                Map.of(
                        "runId", UuidBinary.toBytes(runId),
                        "nodeKey", nodeKey,
                        "attempt", attempt,
                        "now", databaseTime(now)));
    }

    /**
     * 写节点终态。
     *
     * @param tenantId 租户
     * @param finish 终态参数
     * @return 受影响行数；0 表示尝试不再是 RUNNING
     */
    public int finishNodeAttempt(TenantId tenantId, WorkflowNodeFinish finish) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("runId", UuidBinary.toBytes(finish.runId()));
        params.put("nodeKey", finish.nodeKey());
        params.put("attempt", finish.attempt());
        params.put("status", finish.status().name());
        params.put("inputSummary", truncate(finish.inputSummary(), SUMMARY_MAX_LENGTH));
        params.put("outputSummary", truncate(finish.outputSummary(), SUMMARY_MAX_LENGTH));
        params.put("outputJson", finish.outputJson());
        params.put("citationsJson", finish.citationsJson());
        params.put("errorCode", finish.errorCode());
        params.put("errorMessage", truncate(finish.errorMessage(), ERROR_MAX_LENGTH));
        params.put("finishedAt", databaseTime(finish.finishedAt()));
        return tenantJdbc.update(tenantId, FINISH_NODE_ATTEMPT_SQL, params);
    }

    /**
     * 把崩溃遗留的 RUNNING 尝试置为 WF_INTERNAL 失败。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param attempt 尝试号
     * @param now 当前时间
     * @return 受影响行数
     */
    public int failStaleAttempt(TenantId tenantId, UUID runId, String nodeKey, int attempt, Instant now) {
        return tenantJdbc.update(
                tenantId,
                FAIL_STALE_ATTEMPT_SQL,
                Map.of(
                        "runId", UuidBinary.toBytes(runId),
                        "nodeKey", nodeKey,
                        "attempt", attempt,
                        "errorMessage", truncate("上次执行中断，节点尝试已过期", ERROR_MAX_LENGTH),
                        "now", databaseTime(now)));
    }

    /**
     * 把尚未执行的 PENDING 尝试标记为 SKIPPED。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param summary 跳过原因摘要
     * @param now 当前时间
     * @return 受影响行数
     */
    public int skipPendingNode(TenantId tenantId, UUID runId, String nodeKey, String summary, Instant now) {
        return tenantJdbc.update(
                tenantId,
                SKIP_PENDING_NODE_SQL,
                Map.of(
                        "runId", UuidBinary.toBytes(runId),
                        "nodeKey", nodeKey,
                        "summary", truncate(summary, SUMMARY_MAX_LENGTH),
                        "now", databaseTime(now)));
    }

    /**
     * 取消运行后把剩余 PENDING/RUNNING 节点置为 CANCELLED。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param message 脱敏原因
     * @param now 当前时间
     * @return 受影响行数
     */
    public int cancelRemainingNodes(TenantId tenantId, UUID runId, String message, Instant now) {
        return tenantJdbc.update(
                tenantId,
                CANCEL_REMAINING_NODES_SQL,
                Map.of(
                        "runId", UuidBinary.toBytes(runId),
                        "errorMessage", truncate(message, ERROR_MAX_LENGTH),
                        "now", databaseTime(now)));
    }

    /**
     * 把尚未开始的 PENDING 尝试关闭为失败或取消。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param status FAILED 或 CANCELLED
     * @param errorCode 稳定错误码
     * @param message 脱敏原因
     * @param now 当前时间
     * @return 受影响行数
     */
    public int closePendingNode(
            TenantId tenantId,
            UUID runId,
            String nodeKey,
            WorkflowNodeStatus status,
            WorkflowErrorCode errorCode,
            String message,
            Instant now) {
        if (status != WorkflowNodeStatus.FAILED && status != WorkflowNodeStatus.CANCELLED) {
            throw new IllegalArgumentException("pending node can only close as FAILED or CANCELLED");
        }
        return tenantJdbc.update(
                tenantId,
                CLOSE_PENDING_NODE_SQL,
                Map.of(
                        "runId", UuidBinary.toBytes(runId),
                        "nodeKey", nodeKey,
                        "status", status.name(),
                        "errorCode", errorCode.name(),
                        "errorMessage", truncate(message, ERROR_MAX_LENGTH),
                        "now", databaseTime(now)));
    }

    /**
     * 推进当前节点。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param leaseOwner 租约持有者
     * @param nodeKey 节点 key
     * @param now 当前时间
     * @return 受影响行数；0 表示租约丢失
     */
    public int updateCurrentNode(
            TenantId tenantId, UUID runId, String leaseOwner, String nodeKey, Instant now) {
        return tenantJdbc.update(
                tenantId,
                UPDATE_CURRENT_NODE_SQL,
                Map.of(
                        "runId", UuidBinary.toBytes(runId),
                        "leaseOwner", leaseOwner,
                        "nodeKey", nodeKey,
                        "now", databaseTime(now)));
    }

    /**
     * 累加执行时间。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param leaseOwner 租约持有者
     * @param deltaMs 增量毫秒
     * @param now 当前时间
     * @return 受影响行数；0 表示租约丢失
     */
    public int addExecutionMs(
            TenantId tenantId, UUID runId, String leaseOwner, long deltaMs, Instant now) {
        return tenantJdbc.update(
                tenantId,
                ADD_EXECUTION_MS_SQL,
                Map.of(
                        "runId", UuidBinary.toBytes(runId),
                        "leaseOwner", leaseOwner,
                        "deltaMs", Math.max(0L, deltaMs),
                        "now", databaseTime(now)));
    }

    /**
     * 运行成功并写最终结果。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param leaseOwner 租约持有者
     * @param nodeKey END 节点 key
     * @param resultJson 结果 JSON
     * @param resultSizeBytes 结果 JSON 字节数
     * @param deltaMs 本次执行片段毫秒
     * @param now 当前时间
     * @return 受影响行数；0 表示租约丢失或运行已取消
     */
    public int finishRunSucceeded(
            TenantId tenantId,
            UUID runId,
            String leaseOwner,
            String nodeKey,
            String resultJson,
            long resultSizeBytes,
            long deltaMs,
            Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("runId", UuidBinary.toBytes(runId));
        params.put("leaseOwner", leaseOwner);
        params.put("nodeKey", nodeKey);
        params.put("resultJson", resultJson);
        params.put("resultSizeBytes", Math.max(0L, resultSizeBytes));
        params.put("deltaMs", Math.max(0L, deltaMs));
        params.put("now", databaseTime(now));
        return tenantJdbc.update(tenantId, FINISH_RUN_SUCCEEDED_SQL, params);
    }

    /**
     * 运行失败终态。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param leaseOwner 租约持有者
     * @param nodeKey 当前节点 key
     * @param errorCode 稳定错误码
     * @param errorMessage 脱敏原因
     * @param resultStatus 结果保存状态
     * @param resultSizeBytes 结果大小（超限时为尝试值）
     * @param deltaMs 本次执行片段毫秒
     * @param now 当前时间
     * @return 受影响行数；0 表示租约丢失或运行已取消
     */
    public int finishRunFailed(
            TenantId tenantId,
            UUID runId,
            String leaseOwner,
            String nodeKey,
            WorkflowErrorCode errorCode,
            String errorMessage,
            WorkflowResultStatus resultStatus,
            long resultSizeBytes,
            long deltaMs,
            Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("runId", UuidBinary.toBytes(runId));
        params.put("leaseOwner", leaseOwner);
        params.put("nodeKey", nodeKey);
        params.put("errorCode", errorCode.name());
        params.put("errorMessage", truncate(errorMessage, ERROR_MAX_LENGTH));
        params.put("resultStatus", resultStatus.name());
        params.put("resultSizeBytes", Math.max(0L, resultSizeBytes));
        params.put("deltaMs", Math.max(0L, deltaMs));
        params.put("now", databaseTime(now));
        return tenantJdbc.update(tenantId, FINISH_RUN_FAILED_SQL, params);
    }

    /**
     * 把模型调用关联到工作流运行/节点。
     *
     * @param tenantId 租户
     * @param invocationId 模型调用 ID
     * @param runId 工作流运行 ID
     * @param nodeRunId 节点尝试 ID
     * @return 受影响行数
     */
    public int linkModelInvocation(TenantId tenantId, UUID invocationId, UUID runId, UUID nodeRunId) {
        return tenantJdbc.update(
                tenantId,
                LINK_MODEL_INVOCATION_SQL,
                Map.of(
                        "invocationId", UuidBinary.toBytes(invocationId),
                        "workflowRunId", UuidBinary.toBytes(runId),
                        "workflowNodeRunId", UuidBinary.toBytes(nodeRunId)));
    }

    /**
     * 批量读取知识库文档 checksum。
     *
     * @param tenantId 租户
     * @param documentIds 文档 ID
     * @return 文档 ID 到 checksum 的映射
     */
    public Map<UUID, String> findKnowledgeChecksums(TenantId tenantId, Collection<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Map.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT id, checksum FROM knowledge_document WHERE tenant_id = :tenantId AND id IN (");
        Map<String, Object> params = new LinkedHashMap<>();
        int index = 0;
        for (UUID documentId : documentIds) {
            if (index > 0) {
                sql.append(", ");
            }
            String name = "documentId" + index;
            sql.append(':').append(name);
            params.put(name, UuidBinary.toBytes(documentId));
            index++;
        }
        sql.append(')');
        List<Map.Entry<UUID, String>> rows = tenantJdbc.query(
                tenantId,
                sql.toString(),
                params,
                (rs, rowNum) -> Map.entry(
                        UuidBinary.fromBytes(rs.getBytes("id")), rs.getString("checksum")));
        Map<UUID, String> checksums = new LinkedHashMap<>(rows.size());
        for (Map.Entry<UUID, String> row : rows) {
            checksums.put(row.getKey(), row.getValue());
        }
        return checksums;
    }

    /**
     * 把 UTC Instant 转为数据库 {@code DATETIME(6)}。
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
