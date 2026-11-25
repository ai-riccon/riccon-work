package com.riccon.worker.workflow.queue.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import com.riccon.worker.common.UuidV7;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.workflow.queue.domain.WorkflowQueueLimits;
import com.riccon.worker.workflow.queue.domain.WorkflowRunClaimState;
import com.riccon.worker.workflow.queue.domain.WorkflowRunQueueStats;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code workflow_run} 租约、领取状态与终态持久化。
 *
 * <p>除 {@code queueStats()} 的全局聚合与 {@link WorkflowRunClaimer} 的受限领取外，所有语句都经
 * {@link TenantJdbc} 强制 {@code tenant_id} 条件；调用方必须已打开对应租户上下文。续期与终态更新
 * 都要求 {@code status='RUNNING'} 且 {@code lease_owner} 匹配，租约被取消或被其他实例重领时
 * 受影响行数为 0，调用方必须放弃结果。
 *
 * <p>{@code WF_INTERNAL} 兜底遵循 ADR-0024 §4.3/§4.6：运行置 {@code FAILED}；当前节点存在活跃尝试时
 * 置 {@code FAILED}，否则从发布版本图快照中读取节点类型/名称并按下一尝试号创建一次失败尝试，
 * 保证 {@code POST /retry} 可用。图解析只服务失败记账，不执行任何节点逻辑（引擎属 T06b）。
 */
@Component
public class WorkflowRunQueueJdbc {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowRunQueueJdbc.class);

    /** 错误信息列长度。 */
    private static final int MAX_ERROR_LENGTH = 500;

    /** 续期：只允许当前租约持有者且状态仍为 RUNNING。 */
    private static final String RENEW_LEASE_SQL = """
            UPDATE workflow_run
               SET lease_until = DATE_ADD(NOW(6), INTERVAL :leaseSeconds SECOND),
                   updated_at = NOW(6)
             WHERE tenant_id = :tenantId
               AND id = :runId
               AND status = 'RUNNING'
               AND lease_owner = :leaseOwner
            """;

    /** WF_INTERNAL：只有当前租约持有者可以把运行推进到失败终态。 */
    private static final String FAIL_RUN_SQL = """
            UPDATE workflow_run
               SET status = 'FAILED',
                   error_code = 'WF_INTERNAL',
                   error_message = :errorMessage,
                   finished_at = :now,
                   lease_owner = NULL,
                   lease_until = NULL,
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND id = :runId
               AND status = 'RUNNING'
               AND lease_owner = :leaseOwner
            """;

    /** 把当前节点的活跃尝试置为失败；不存在活跃尝试时由调用方创建一次失败尝试。 */
    private static final String FAIL_CURRENT_NODE_SQL = """
            UPDATE workflow_node_run
               SET status = 'FAILED',
                   error_code = 'WF_INTERNAL',
                   error_message = :errorMessage,
                   finished_at = :now,
                   updated_at = :now,
                   duration_ms = CASE
                         WHEN started_at IS NULL THEN 0
                         ELSE TIMESTAMPDIFF(MICROSECOND, started_at, :now) DIV 1000
                       END
             WHERE tenant_id = :tenantId
               AND workflow_run_id = :runId
               AND node_key = :nodeKey
               AND status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL')
            """;

    /** 读取当前节点已有最大尝试号，保证新建失败尝试不冲突唯一键。 */
    private static final String LATEST_NODE_ATTEMPT_SQL = """
            SELECT COALESCE(MAX(attempt), 0) AS latest_attempt
              FROM workflow_node_run
             WHERE tenant_id = :tenantId
               AND workflow_run_id = :runId
               AND node_key = :nodeKey
            """;

    /** 读取运行绑定的发布版本图快照，仅用于失败记账的节点类型/名称。 */
    private static final String RUN_GRAPH_SQL = """
            SELECT v.graph_json
              FROM workflow_run r
              JOIN workflow_version v
                ON v.tenant_id = r.tenant_id AND v.id = r.workflow_version_id
             WHERE r.tenant_id = :tenantId AND r.id = :runId
            """;

    /** 创建一次失败节点尝试，保证失败运行可被 POST /retry。 */
    private static final String INSERT_FAILED_NODE_SQL = """
            INSERT INTO workflow_node_run (
                tenant_id, id, workflow_run_id, node_key, node_type, node_name, attempt, status,
                error_code, error_message, started_at, finished_at, duration_ms, created_at, updated_at
            ) VALUES (
                :tenantId, :id, :runId, :nodeKey, :nodeType, :nodeName, :attempt, 'FAILED',
                'WF_INTERNAL', :errorMessage, :now, :now, 0, :now, :now
            )
            """;

    /** 队列深度聚合；只按状态计数，不读取租户或业务内容。 */
    private static final String QUEUE_DEPTH_SQL = """
            SELECT status, COUNT(*) AS depth
              FROM workflow_run
             WHERE status IN ('PENDING', 'WAITING_APPROVAL')
             GROUP BY status
            """;

    /** 最老 PENDING 年龄聚合。 */
    private static final String OLDEST_PENDING_SQL = """
            SELECT TIMESTAMPDIFF(SECOND, MIN(created_at), NOW(6)) AS age_seconds
              FROM workflow_run
             WHERE status = 'PENDING'
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /** 全局聚合查询使用；不经过 TenantJdbc 是因为指标没有租户维度。 */
    private final JdbcClient jdbcClient;

    /** 图快照解析。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建队列持久化组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     * @param jdbcClient Spring JDBC 客户端
     * @param objectMapper JSON 序列化器
     */
    public WorkflowRunQueueJdbc(TenantJdbc tenantJdbc, JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 读取领取后的运行状态（仅元数据）。
     *
     * @param tenantId 已校验租户
     * @param runId 运行 ID
     * @return 状态；行不存在时为空
     */
    public Optional<WorkflowRunClaimState> findClaimState(TenantId tenantId, UUID runId) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT status, claim_attempts, current_node_key, lease_owner
                  FROM workflow_run
                 WHERE tenant_id = :tenantId AND id = :runId
                """,
                Map.of("runId", UuidBinary.toBytes(runId)),
                (rs, rowNum) -> new WorkflowRunClaimState(
                        WorkflowRunStatus.valueOf(rs.getString("status")),
                        rs.getInt("claim_attempts"),
                        rs.getString("current_node_key"),
                        rs.getString("lease_owner")));
    }

    /**
     * 读取运行状态。
     *
     * @param tenantId 已校验租户
     * @param runId 运行 ID
     * @return 状态；行不存在时为空
     */
    public Optional<WorkflowRunStatus> findStatus(TenantId tenantId, UUID runId) {
        return tenantJdbc.queryOne(
                tenantId,
                "SELECT status FROM workflow_run WHERE tenant_id = :tenantId AND id = :runId",
                Map.of("runId", UuidBinary.toBytes(runId)),
                (rs, rowNum) -> WorkflowRunStatus.valueOf(rs.getString("status")));
    }

    /**
     * 续期运行租约。
     *
     * @param tenantId 已校验租户
     * @param runId 运行 ID
     * @param leaseOwner 当前租约持有者
     * @param leaseDuration 新租约时长；至少 1 秒
     * @return 受影响行数；0 表示租约已丢失或被取消
     */
    public int renewLease(TenantId tenantId, UUID runId, String leaseOwner, Duration leaseDuration) {
        Objects.requireNonNull(leaseOwner, "leaseOwner must not be null");
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        long leaseSeconds = Math.max(1L, leaseDuration.toSeconds());
        return tenantJdbc.update(
                tenantId,
                RENEW_LEASE_SQL,
                Map.of(
                        "runId", UuidBinary.toBytes(runId),
                        "leaseOwner", leaseOwner,
                        "leaseSeconds", leaseSeconds));
    }

    /**
     * 把运行推进到 {@code WF_INTERNAL} 失败终态，并保证当前节点有一次失败尝试。
     *
     * @param tenantId 已校验租户
     * @param runId 运行 ID
     * @param leaseOwner 当前租约持有者
     * @param currentNodeKey 当前节点 key；可为空
     * @param now 当前 UTC 时间
     * @return true 表示本次调用完成终态推进；false 表示租约已变化
     */
    public boolean markClaimExhausted(
            TenantId tenantId, UUID runId, String leaseOwner, String currentNodeKey, Instant now) {
        LocalDateTime databaseNow = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        String message = "claim attempts exhausted (max " + WorkflowQueueLimits.MAX_CLAIM_ATTEMPTS + ")";
        int updated = tenantJdbc.update(
                tenantId,
                FAIL_RUN_SQL,
                Map.of(
                        "runId",
                        UuidBinary.toBytes(runId),
                        "leaseOwner",
                        leaseOwner,
                        "errorMessage",
                        truncate(message),
                        "now",
                        databaseNow));
        if (updated == 0) {
            return false;
        }
        if (currentNodeKey == null || currentNodeKey.isBlank()) {
            return true;
        }
        int nodeUpdated = tenantJdbc.update(
                tenantId,
                FAIL_CURRENT_NODE_SQL,
                Map.of(
                        "runId",
                        UuidBinary.toBytes(runId),
                        "nodeKey",
                        currentNodeKey,
                        "errorMessage",
                        truncate(message),
                        "now",
                        databaseNow));
        if (nodeUpdated > 0) {
            return true;
        }
        try {
            ensureFailedNodeAttempt(tenantId, runId, currentNodeKey, message, databaseNow);
        } catch (RuntimeException ex) {
            // 运行已是 FAILED；节点记账失败只影响重试入口，记录后用人工/后续修复处理，不掩盖终态。
            LOG.error(
                    "workflow.claim.exhausted_node_bookkeeping_failed tenantId={} runId={} nodeKey={}",
                    tenantId.value(),
                    runId,
                    currentNodeKey,
                    ex);
        }
        return true;
    }

    /**
     * 队列聚合指标。
     *
     * <p>只返回计数与年龄，不返回租户 ID、运行 ID 或业务字段；用于 §10.2 的 Gauge。
     *
     * @return 队列指标快照
     */
    public WorkflowRunQueueStats queueStats() {
        Map<String, Long> depths = jdbcClient
                .sql(QUEUE_DEPTH_SQL)
                .query((rs, rowNum) -> Map.entry(rs.getString("status"), rs.getLong("depth")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Long oldest =
                jdbcClient.sql(OLDEST_PENDING_SQL).query(Long.class).optional().orElse(null);
        return new WorkflowRunQueueStats(
                depths.getOrDefault(WorkflowRunStatus.PENDING.name(), 0L),
                depths.getOrDefault(WorkflowRunStatus.WAITING_APPROVAL.name(), 0L),
                oldest == null ? 0L : Math.max(0L, oldest));
    }

    /**
     * 当前节点没有活跃尝试时，按下一尝试号创建一次失败尝试。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 当前节点 key
     * @param message 脱敏失败原因
     * @param now 数据库 UTC 时间
     */
    private void ensureFailedNodeAttempt(
            TenantId tenantId, UUID runId, String nodeKey, String message, LocalDateTime now) {
        Long latest = tenantJdbc
                .queryOne(
                        tenantId,
                        LATEST_NODE_ATTEMPT_SQL,
                        Map.of("runId", UuidBinary.toBytes(runId), "nodeKey", nodeKey),
                        (rs, rowNum) -> rs.getLong("latest_attempt"))
                .orElse(0L);
        WorkflowNodeDescriptor descriptor = findNodeDescriptor(tenantId, runId, nodeKey);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(UuidV7.generate()));
        params.put("runId", UuidBinary.toBytes(runId));
        params.put("nodeKey", nodeKey);
        params.put("nodeType", descriptor.type().name());
        params.put("nodeName", descriptor.name());
        params.put("attempt", latest + 1);
        params.put("errorMessage", truncate(message));
        params.put("now", now);
        tenantJdbc.insert(tenantId, INSERT_FAILED_NODE_SQL, params);
    }

    /**
     * 从运行绑定的版本图快照中读取节点类型与名称。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @return 节点描述
     * @throws IllegalStateException 图缺失或节点 key 不在图中（数据损坏，fail-closed）
     */
    private WorkflowNodeDescriptor findNodeDescriptor(TenantId tenantId, UUID runId, String nodeKey) {
        String graphJson = tenantJdbc
                .queryOne(
                        tenantId,
                        RUN_GRAPH_SQL,
                        Map.of("runId", UuidBinary.toBytes(runId)),
                        (rs, rowNum) -> rs.getString("graph_json"))
                .orElseThrow(() -> new IllegalStateException("workflow run version graph is missing"));
        try {
            JsonNode graph = objectMapper.readTree(graphJson);
            JsonNode nodes = graph.path("nodes");
            for (JsonNode node : nodes) {
                if (nodeKey.equals(node.path("id").asText())) {
                    WorkflowNodeType type =
                            WorkflowNodeType.valueOf(node.path("type").asText());
                    String name = node.path("name").asText();
                    if (name.isBlank()) {
                        throw new IllegalStateException("workflow graph node name is blank: " + nodeKey);
                    }
                    return new WorkflowNodeDescriptor(type, name);
                }
            }
            throw new IllegalStateException("workflow graph missing current node: " + nodeKey);
        } catch (JacksonException ex) {
            throw new IllegalStateException("workflow graph json is unreadable", ex);
        }
    }

    /**
     * 截断错误信息，避免超过列长度。
     *
     * @param value 信息
     * @return 最长 {@value #MAX_ERROR_LENGTH} 字符
     */
    private static String truncate(String value) {
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    /**
     * 图快照中的节点描述。
     *
     * @param type 节点类型
     * @param name 节点名
     */
    private record WorkflowNodeDescriptor(WorkflowNodeType type, String name) {}
}
