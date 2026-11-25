package com.riccon.worker.workflow.engine;

import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.worker.common.UuidV7;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.workflow.engine.domain.WorkflowCitation;
import com.riccon.worker.workflow.heartbeat.domain.WorkflowLease;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecution;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecutor;
import com.riccon.worker.workflow.node.domain.WorkflowNodeResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 串行引擎集成测试夹具。
 *
 * <p>直接写 V15 表；每个测试使用唯一租户隔离。测试类通过
 * {@code riccon.workflow.claim-enabled=false} 关闭自动领取，避免共享上下文中的调度槽位与
 * 手工 {@code engine.execute} 抢同一 PENDING 运行。
 */
public final class WorkflowEngineTestSupport {

    /** JDBC 客户端。 */
    private final JdbcClient jdbcClient;

    /** JSON 解析。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建夹具。
     *
     * @param jdbcClient JDBC 客户端
     * @param objectMapper JSON 解析
     */
    public WorkflowEngineTestSupport(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 把其他测试遗留的可领取运行置为远期租约。
     */
    public void drainPendingRuns() {
        jdbcClient.sql("""
                        UPDATE workflow_run
                           SET status = 'RUNNING',
                               lease_owner = 'test-drain',
                               lease_until = DATE_ADD(NOW(6), INTERVAL 1 DAY),
                               started_at = COALESCE(started_at, NOW(6)),
                               updated_at = NOW(6)
                         WHERE status IN ('PENDING', 'RUNNING')
                        """).update();
    }

    /**
     * 创建租户。
     *
     * @param tenantId 租户 ID
     */
    public void createTenant(String tenantId) {
        jdbcClient
                .sql("""
                        INSERT INTO tenant (id, name, status, plan, retention_days)
                        VALUES (:tenantId, :name, 'ACTIVE', 'standard', 180)
                        """)
                .param("tenantId", tenantId)
                .param("name", "Test " + tenantId)
                .update();
    }

    /**
     * 创建账号。
     *
     * @param tenantId 租户
     * @return 账号 ID
     */
    public UUID createUser(String tenantId) {
        UUID userId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO app_user (tenant_id, id, username, display_name, status, auth_source)
                        VALUES (:tenantId, :id, :username, 'Engine User', 'ACTIVE', 'LOCAL')
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(userId))
                .param("username", "engine-" + userId.toString().substring(0, 8))
                .update();
        return userId;
    }

    /**
     * 创建流程定义与发布版本。
     *
     * @param tenantId 租户
     * @param owner 所有者
     * @param graphJson 图 JSON
     * @param nodeCount 节点数
     * @return 种子
     */
    public Seed createWorkflow(String tenantId, UUID owner, String graphJson, int nodeCount) {
        UUID workflowId = UuidV7.generate();
        UUID versionId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO workflow_definition (
                            tenant_id, id, name, description, owner_user_id, draft_version, draft_graph,
                            status, created_by, updated_by
                        ) VALUES (
                            :tenantId, :id, 'Engine Test Workflow', NULL, :owner, 1, :graph,
                            'ACTIVE', :owner, :owner
                        )
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(workflowId))
                .param("owner", UuidBinary.toBytes(owner))
                .param("graph", graphJson)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO workflow_version (
                            tenant_id, id, workflow_id, version_no, graph_json, graph_sha256,
                            node_count, run_scope, published_by, published_at, created_at
                        ) VALUES (
                            :tenantId, :id, :workflowId, 1, :graph, REPEAT('a', 64),
                            :nodeCount, 'TENANT', :owner, NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(versionId))
                .param("workflowId", UuidBinary.toBytes(workflowId))
                .param("graph", graphJson)
                .param("nodeCount", nodeCount)
                .param("owner", UuidBinary.toBytes(owner))
                .update();
        return new Seed(workflowId, versionId);
    }

    /**
     * 插入运行。
     *
     * @param tenantId 租户
     * @param seed 流程种子
     * @param requester 发起人
     * @param inputsJson 运行输入
     * @param status 运行状态
     * @param leaseOwner 租约持有者
     * @param currentNodeKey 当前节点
     * @param executionMs 累计执行毫秒
     * @return 运行 ID
     */
    public UUID insertRun(
            String tenantId,
            Seed seed,
            UUID requester,
            String inputsJson,
            String status,
            String leaseOwner,
            String currentNodeKey,
            long executionMs) {
        UUID runId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO workflow_run (
                            tenant_id, id, workflow_id, workflow_version_id, version_no, requester_user_id,
                            status, inputs_json, result_status, result_size_bytes, current_node_key,
                            execution_ms, approval_wait_ms, claim_attempts, idempotency_key,
                            lease_owner, lease_until, retention_expires_at, created_at, updated_at, started_at
                        ) VALUES (
                            :tenantId, :id, :workflowId, :versionId, 1, :requester,
                            :status, :inputs, 'NONE', 0, :nodeKey,
                            :executionMs, 0, 0, :idempotency,
                            :leaseOwner, DATE_ADD(NOW(6), INTERVAL 90 SECOND),
                            DATE_ADD(NOW(6), INTERVAL 180 DAY), NOW(6), NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(runId))
                .param("workflowId", UuidBinary.toBytes(seed.workflowId()))
                .param("versionId", UuidBinary.toBytes(seed.versionId()))
                .param("requester", UuidBinary.toBytes(requester))
                .param("status", status)
                .param("inputs", inputsJson)
                .param("nodeKey", currentNodeKey)
                .param("executionMs", executionMs)
                .param("idempotency", "run-" + runId)
                .param("leaseOwner", leaseOwner)
                .update();
        return runId;
    }

    /**
     * 读取运行行。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @return 运行行
     */
    public RunRow readRun(String tenantId, UUID runId) {
        return jdbcClient
                .sql("""
                        SELECT status, error_code, error_message, current_node_key, result_json,
                               result_status, result_size_bytes, execution_ms, lease_owner, finished_at
                          FROM workflow_run
                         WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("tenantId", tenantId)
                .param("runId", UuidBinary.toBytes(runId))
                .query(WorkflowEngineTestSupport::mapRun)
                .single();
    }

    /**
     * 读取节点尝试。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @return 尝试列表
     */
    public List<NodeRow> readNodes(String tenantId, UUID runId, String nodeKey) {
        return jdbcClient
                .sql("""
                        SELECT node_key, attempt, status, input_summary, output_summary, output_json,
                               citations_json, error_code, error_message, started_at, finished_at, duration_ms
                          FROM workflow_node_run
                         WHERE tenant_id = :tenantId AND workflow_run_id = :runId AND node_key = :nodeKey
                         ORDER BY attempt
                        """)
                .param("tenantId", tenantId)
                .param("runId", UuidBinary.toBytes(runId))
                .param("nodeKey", nodeKey)
                .query(WorkflowEngineTestSupport::mapNode)
                .list();
    }

    /**
     * 读取全部节点尝试。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @return 尝试列表
     */
    public List<NodeRow> readAllNodes(String tenantId, UUID runId) {
        return jdbcClient
                .sql("""
                        SELECT node_key, attempt, status, input_summary, output_summary, output_json,
                               citations_json, error_code, error_message, started_at, finished_at, duration_ms
                          FROM workflow_node_run
                         WHERE tenant_id = :tenantId AND workflow_run_id = :runId
                         ORDER BY node_key, attempt
                        """)
                .param("tenantId", tenantId)
                .param("runId", UuidBinary.toBytes(runId))
                .query(WorkflowEngineTestSupport::mapNode)
                .list();
    }

    /**
     * 模拟“API 重试 + Worker 领取”：运行置 RUNNING（已领取）、当前节点为失败节点、
     * 下游 SKIPPED 恢复 PENDING、失败节点新增 PENDING 尝试。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param failedNodeKey 失败节点
     * @param failedNodeType 失败节点类型
     * @param failedNodeName 失败节点名
     * @param leaseOwner 新租约
     * @return 新尝试号
     */
    public int prepareRetry(
            String tenantId,
            UUID runId,
            String failedNodeKey,
            WorkflowNodeType failedNodeType,
            String failedNodeName,
            String leaseOwner) {
        int latest = jdbcClient
                .sql("""
                        SELECT COALESCE(MAX(attempt), 0)
                          FROM workflow_node_run
                         WHERE tenant_id = :tenantId AND workflow_run_id = :runId AND node_key = :nodeKey
                        """)
                .param("tenantId", tenantId)
                .param("runId", UuidBinary.toBytes(runId))
                .param("nodeKey", failedNodeKey)
                .query(Integer.class)
                .single();
        int next = latest + 1;
        jdbcClient
                .sql("""
                        UPDATE workflow_run
                           SET status = 'RUNNING', current_node_key = :nodeKey,
                               error_code = NULL, error_message = NULL, finished_at = NULL,
                               lease_owner = :leaseOwner,
                               lease_until = DATE_ADD(NOW(6), INTERVAL 90 SECOND),
                               claim_attempts = 0, updated_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("tenantId", tenantId)
                .param("runId", UuidBinary.toBytes(runId))
                .param("nodeKey", failedNodeKey)
                .param("leaseOwner", leaseOwner)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO workflow_node_run (
                            tenant_id, id, workflow_run_id, node_key, node_type, node_name, attempt, status,
                            created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, :runId, :nodeKey, :nodeType, :nodeName, :attempt, 'PENDING',
                            NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(UuidV7.generate()))
                .param("runId", UuidBinary.toBytes(runId))
                .param("nodeKey", failedNodeKey)
                .param("nodeType", failedNodeType.name())
                .param("nodeName", failedNodeName)
                .param("attempt", next)
                .update();
        jdbcClient
                .sql("""
                        UPDATE workflow_node_run
                           SET status = 'PENDING', started_at = NULL, finished_at = NULL, duration_ms = NULL,
                               error_code = NULL, error_message = NULL, updated_at = NOW(6)
                         WHERE tenant_id = :tenantId AND workflow_run_id = :runId AND status = 'SKIPPED'
                        """)
                .param("tenantId", tenantId)
                .param("runId", UuidBinary.toBytes(runId))
                .update();
        return next;
    }

    /**
     * 把运行置为 PENDING 以便同一槽位再次执行（租约持有者不变）。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param leaseOwner 新租约
     * @param currentNodeKey 当前节点
     */
    public void requeue(String tenantId, UUID runId, String leaseOwner, String currentNodeKey) {
        jdbcClient
                .sql("""
                        UPDATE workflow_run
                           SET status = 'PENDING', lease_owner = :leaseOwner,
                               lease_until = DATE_ADD(NOW(6), INTERVAL 90 SECOND),
                               current_node_key = :nodeKey, updated_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("tenantId", tenantId)
                .param("runId", UuidBinary.toBytes(runId))
                .param("leaseOwner", leaseOwner)
                .param("nodeKey", currentNodeKey)
                .update();
    }

    /**
     * 直接设置累计执行时间（模拟接近运行超时）。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param executionMs 毫秒
     */
    public void setExecutionMs(String tenantId, UUID runId, long executionMs) {
        jdbcClient
                .sql("""
                        UPDATE workflow_run SET execution_ms = :executionMs, updated_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("tenantId", tenantId)
                .param("runId", UuidBinary.toBytes(runId))
                .param("executionMs", executionMs)
                .update();
    }

    /**
     * 模拟 API 取消运行。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     */
    public void cancelRun(String tenantId, UUID runId) {
        jdbcClient
                .sql("""
                        UPDATE workflow_run
                           SET status = 'CANCELLED', error_code = 'WF_CANCELLED',
                               finished_at = NOW(6), lease_owner = NULL, lease_until = NULL, updated_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("tenantId", tenantId)
                .param("runId", UuidBinary.toBytes(runId))
                .update();
    }

    /**
     * 插入一条 STARTED 模型调用记录，用于验证工作流关联列。
     *
     * @param tenantId 租户
     * @param invocationId 调用 ID
     * @param modelId 模型 ID
     */
    public void insertModelInvocation(String tenantId, UUID invocationId, UUID modelId) {
        jdbcClient
                .sql("""
                        INSERT INTO model_invocation (
                            tenant_id, id, model_id, model_key_snapshot, purpose, status, trace_id,
                            started_at, created_at
                        ) VALUES (
                            :tenantId, :id, :modelId, :modelKey, 'REPORT', 'STARTED', 'engine-trace',
                            NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(invocationId))
                .param("modelId", UuidBinary.toBytes(modelId))
                .param("modelKey", "engine-model")
                .update();
    }

    /**
     * 创建 DISABLED 模型目录行，避免影响全局路由基线。
     *
     * @return 模型 ID
     */
    public UUID createDisabledModel() {
        UUID providerId = UuidV7.generate();
        UUID modelId = UuidV7.generate();
        String suffix = modelId.toString().substring(0, 8);
        jdbcClient
                .sql("""
                        INSERT INTO model_provider (id, code, name, base_url, status)
                        VALUES (:id, :code, 'Engine Test Provider', 'http://127.0.0.1:19090/v1', 'DISABLED')
                        """)
                .param("id", UuidBinary.toBytes(providerId))
                .param("code", "engine-provider-" + suffix)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO model (
                            id, provider_id, model_key, display_name, context_window, max_output_tokens, status
                        ) VALUES (
                            :id, :providerId, :modelKey, 'Engine Test Model', 8192, 1024, 'DISABLED'
                        )
                        """)
                .param("id", UuidBinary.toBytes(modelId))
                .param("providerId", UuidBinary.toBytes(providerId))
                .param("modelKey", "engine-model-" + suffix)
                .update();
        return modelId;
    }

    /**
     * 把运行的当前节点设为指定节点（保持 RUNNING）。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     */
    public void setCurrentNode(String tenantId, UUID runId, String nodeKey) {
        jdbcClient
                .sql("""
                        UPDATE workflow_run SET current_node_key = :nodeKey, updated_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("tenantId", tenantId)
                .param("runId", UuidBinary.toBytes(runId))
                .param("nodeKey", nodeKey)
                .update();
    }

    /**
     * 读取模型调用的工作流关联列。
     *
     * @param tenantId 租户
     * @param invocationId 调用 ID
     * @return 关联行
     */
    public ModelLink readModelLink(String tenantId, UUID invocationId) {
        return jdbcClient
                .sql("""
                        SELECT workflow_run_id, workflow_node_run_id
                          FROM model_invocation
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(invocationId))
                .query((rs, rowNum) -> new ModelLink(
                        rs.getBytes("workflow_run_id") == null
                                ? null
                                : UuidBinary.fromBytes(rs.getBytes("workflow_run_id")),
                        rs.getBytes("workflow_node_run_id") == null
                                ? null
                                : UuidBinary.fromBytes(rs.getBytes("workflow_node_run_id"))))
                .single();
    }

    /**
     * 插入已成功的节点尝试（用于崩溃恢复场景）。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param nodeType 节点类型
     * @param nodeName 节点名
     * @param outputJson 输出 JSON
     * @param summary 输出摘要
     * @param citationsJson 引用 JSON；可为空
     */
    public void insertSucceededNode(
            String tenantId,
            UUID runId,
            String nodeKey,
            WorkflowNodeType nodeType,
            String nodeName,
            String outputJson,
            String summary,
            String citationsJson) {
        jdbcClient
                .sql("""
                        INSERT INTO workflow_node_run (
                            tenant_id, id, workflow_run_id, node_key, node_type, node_name, attempt, status,
                            input_summary, output_summary, output_json, citations_json,
                            started_at, finished_at, duration_ms, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, :runId, :nodeKey, :nodeType, :nodeName, 1, 'SUCCEEDED',
                            NULL, :summary, :outputJson, :citationsJson,
                            NOW(6), NOW(6), 5, NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(UuidV7.generate()))
                .param("runId", UuidBinary.toBytes(runId))
                .param("nodeKey", nodeKey)
                .param("nodeType", nodeType.name())
                .param("nodeName", nodeName)
                .param("summary", summary)
                .param("outputJson", outputJson)
                .param("citationsJson", citationsJson)
                .update();
    }

    /**
     * 插入 RUNNING 节点尝试（用于模拟崩溃遗留）。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param nodeType 类型
     * @param nodeName 名称
     */
    public void insertRunningNode(
            String tenantId, UUID runId, String nodeKey, WorkflowNodeType nodeType, String nodeName) {
        jdbcClient
                .sql("""
                        INSERT INTO workflow_node_run (
                            tenant_id, id, workflow_run_id, node_key, node_type, node_name, attempt, status,
                            started_at, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, :runId, :nodeKey, :nodeType, :nodeName, 1, 'RUNNING',
                            NOW(6), NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(UuidV7.generate()))
                .param("runId", UuidBinary.toBytes(runId))
                .param("nodeKey", nodeKey)
                .param("nodeType", nodeType.name())
                .param("nodeName", nodeName)
                .update();
    }

    /**
     * 创建知识库文档（用于引用 checksum 查询）。
     *
     * @param tenantId 租户
     * @param createdBy 创建人
     * @param checksum checksum
     * @return 文档 ID
     */
    public UUID createKnowledgeDocument(String tenantId, UUID createdBy, String checksum) {
        UUID documentId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_document (
                            tenant_id, id, title, source_type, checksum, mime_type, size_bytes, status, created_by
                        ) VALUES (
                            :tenantId, :id, 'Engine Doc', 'UPLOAD', :checksum, 'text/plain', 10, 'PENDING', :createdBy
                        )
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(documentId))
                .param("checksum", checksum)
                .param("createdBy", UuidBinary.toBytes(createdBy))
                .update();
        return documentId;
    }

    /**
     * 插入 PENDING 节点尝试（用于模拟重试/超时边界）。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param nodeType 类型
     * @param nodeName 名称
     */
    public void insertPendingNode(
            String tenantId, UUID runId, String nodeKey, WorkflowNodeType nodeType, String nodeName) {
        jdbcClient
                .sql("""
                        INSERT INTO workflow_node_run (
                            tenant_id, id, workflow_run_id, node_key, node_type, node_name, attempt, status,
                            created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, :runId, :nodeKey, :nodeType, :nodeName, 1, 'PENDING',
                            NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(UuidV7.generate()))
                .param("runId", UuidBinary.toBytes(runId))
                .param("nodeKey", nodeKey)
                .param("nodeType", nodeType.name())
                .param("nodeName", nodeName)
                .update();
    }

    /**
     * 可推进的测试时钟；用于在不 sleep 的情况下验证运行超时。
     */
    public static final class MutableClock extends java.time.Clock {

        /** 当前瞬时。 */
        private volatile Instant instant;

        /**
         * 创建时钟。
         *
         * @param instant 初始时间
         */
        public MutableClock(Instant instant) {
            this.instant = instant;
        }

        /**
         * 前进指定时长。
         *
         * @param duration 时长
         */
        public void advance(java.time.Duration duration) {
            instant = instant.plus(duration);
        }

        /** {@inheritDoc} */
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /** {@inheritDoc} */
        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public Instant instant() {
            return instant;
        }
    }

    /**
     * 节点 JSON。
     *
     * @param id 节点 key
     * @param type 类型
     * @param name 名称
     * @param config 配置 JSON
     * @return 节点 JSON 文本
     */
    public static String node(String id, String type, String name, String config) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"name\":\"" + name + "\",\"config\":" + config + "}";
    }

    /**
     * 边 JSON。
     *
     * @param id 边 ID
     * @param source 源
     * @param target 目标
     * @param branch 分支；空为普通边
     * @return 边 JSON 文本
     */
    public static String edge(String id, String source, String target, String branch) {
        String branchField = branch == null ? "" : ",\"branch\":\"" + branch + "\"";
        return "{\"id\":\"" + id + "\",\"source\":\"" + source + "\",\"target\":\"" + target + "\"" + branchField + "}";
    }

    /**
     * 图 JSON。
     *
     * @param nodes 节点 JSON 列表
     * @param edges 边 JSON 列表
     * @return 图 JSON 文本
     */
    public static String graph(List<String> nodes, List<String> edges) {
        return "{\"schemaVersion\":1,\"nodes\":[" + String.join(",", nodes) + "],\"edges\":[" + String.join(",", edges)
                + "]}";
    }

    /**
     * 构造检索引用。
     *
     * @param count 数量
     * @return 引用列表
     */
    public static List<WorkflowCitation> citations(int count) {
        List<WorkflowCitation> citations = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            citations.add(new WorkflowCitation(
                    index,
                    com.riccon.platform.domain.chat.CitationSourceType.KNOWLEDGE,
                    null,
                    UuidV7.generate(),
                    UuidV7.generate(),
                    com.riccon.platform.domain.chat.LocatorType.PARAGRAPH,
                    null,
                    null,
                    null,
                    index,
                    "文档 " + index,
                    "引用片段 " + index,
                    0.9,
                    "checksum-" + index));
        }
        return citations;
    }

    /**
     * 解析 JSON 文本。
     *
     * @param json JSON
     * @return JSON 树
     */
    public JsonNode json(String json) {
        return objectMapper.readTree(json);
    }

    /**
     * 映射运行行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 运行行
     * @throws SQLException 读取失败
     */
    private static RunRow mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new RunRow(
                rs.getString("status"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getString("current_node_key"),
                rs.getString("result_json"),
                rs.getString("result_status"),
                rs.getLong("result_size_bytes"),
                rs.getLong("execution_ms"),
                rs.getString("lease_owner"),
                rs.getObject("finished_at", LocalDateTime.class));
    }

    /**
     * 映射节点行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 节点行
     * @throws SQLException 读取失败
     */
    private static NodeRow mapNode(ResultSet rs, int rowNum) throws SQLException {
        return new NodeRow(
                rs.getString("node_key"),
                rs.getInt("attempt"),
                rs.getString("status"),
                rs.getString("input_summary"),
                rs.getString("output_summary"),
                rs.getString("output_json"),
                rs.getString("citations_json"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getObject("started_at", LocalDateTime.class),
                rs.getObject("finished_at", LocalDateTime.class),
                rs.getObject("duration_ms", Long.class));
    }

    /**
     * 流程种子。
     *
     * @param workflowId 流程 ID
     * @param versionId 版本 ID
     */
    public record Seed(UUID workflowId, UUID versionId) {}

    /**
     * 运行行。
     *
     * @param status 状态
     * @param errorCode 错误码
     * @param errorMessage 错误信息
     * @param currentNodeKey 当前节点
     * @param resultJson 结果 JSON
     * @param resultStatus 结果状态
     * @param resultSizeBytes 结果字节数
     * @param executionMs 累计执行毫秒
     * @param leaseOwner 租约持有者
     * @param finishedAt 完成时间
     */
    public record RunRow(
            String status,
            String errorCode,
            String errorMessage,
            String currentNodeKey,
            String resultJson,
            String resultStatus,
            long resultSizeBytes,
            long executionMs,
            String leaseOwner,
            LocalDateTime finishedAt) {}

    /**
     * 节点行。
     *
     * @param nodeKey 节点 key
     * @param attempt 尝试号
     * @param status 状态
     * @param inputSummary 输入摘要
     * @param outputSummary 输出摘要
     * @param outputJson 输出 JSON
     * @param citationsJson 引用 JSON
     * @param errorCode 错误码
     * @param errorMessage 错误信息
     * @param startedAt 开始时间
     * @param finishedAt 完成时间
     * @param durationMs 耗时
     */
    public record NodeRow(
            String nodeKey,
            int attempt,
            String status,
            String inputSummary,
            String outputSummary,
            String outputJson,
            String citationsJson,
            String errorCode,
            String errorMessage,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            Long durationMs) {}

    /**
     * 模型调用关联行。
     *
     * @param workflowRunId 运行 ID
     * @param workflowNodeRunId 节点尝试 ID
     */
    public record ModelLink(UUID workflowRunId, UUID workflowNodeRunId) {}

    /**
     * 测试租约。
     */
    public static final class TestLease implements WorkflowLease {

        /** 运行 ID。 */
        private final UUID runId;

        /** 租户。 */
        private final String tenantId;

        /** 是否丢失。 */
        private final AtomicBoolean lost = new AtomicBoolean();

        /** 是否取消。 */
        private final AtomicBoolean cancelled = new AtomicBoolean();

        /** 是否关闭。 */
        private final AtomicBoolean stopped = new AtomicBoolean();

        /**
         * 创建租约。
         *
         * @param runId 运行 ID
         * @param tenantId 租户
         */
        public TestLease(UUID runId, String tenantId) {
            this.runId = runId;
            this.tenantId = tenantId;
        }

        /** 标记租约丢失。 */
        public void markLost() {
            lost.set(true);
        }

        /** 标记运行取消。 */
        public void markCancelled() {
            cancelled.set(true);
        }

        /** {@inheritDoc} */
        @Override
        public UUID runId() {
            return runId;
        }

        /** {@inheritDoc} */
        @Override
        public String tenantId() {
            return tenantId;
        }

        /** {@inheritDoc} */
        @Override
        public boolean lost() {
            return lost.get();
        }

        /** {@inheritDoc} */
        @Override
        public boolean cancelled() {
            return cancelled.get();
        }

        /** {@inheritDoc} */
        @Override
        public boolean stopped() {
            return stopped.get() || lost.get() || cancelled.get();
        }

        /** {@inheritDoc} */
        @Override
        public void close() {
            stopped.set(true);
        }
    }

    /**
     * 可编程节点执行器。
     */
    public static final class StubExecutor implements WorkflowNodeExecutor {

        /** 节点类型。 */
        private final WorkflowNodeType type;

        /** 行为；可替换以模拟失败/成功。 */
        private volatile Function<WorkflowNodeExecution, WorkflowNodeResult> behavior;

        /** 调用次数。 */
        private final AtomicInteger invocations = new AtomicInteger();

        /** 调用参数。 */
        private final List<WorkflowNodeExecution> executions = new CopyOnWriteArrayList<>();

        /**
         * 创建桩执行器。
         *
         * @param type 节点类型
         * @param behavior 行为
         */
        public StubExecutor(WorkflowNodeType type, Function<WorkflowNodeExecution, WorkflowNodeResult> behavior) {
            this.type = type;
            this.behavior = behavior;
        }

        /** {@inheritDoc} */
        @Override
        public WorkflowNodeType nodeType() {
            return type;
        }

        /** {@inheritDoc} */
        @Override
        public WorkflowNodeResult execute(WorkflowNodeExecution execution) {
            invocations.incrementAndGet();
            executions.add(execution);
            return behavior.apply(execution);
        }

        /**
         * 替换行为。
         *
         * @param newBehavior 新行为
         */
        public void behavior(Function<WorkflowNodeExecution, WorkflowNodeResult> newBehavior) {
            this.behavior = newBehavior;
        }

        /**
         * 调用次数。
         *
         * @return 次数
         */
        public int invocations() {
            return invocations.get();
        }

        /**
         * 调用参数。
         *
         * @return 执行上下文列表
         */
        public List<WorkflowNodeExecution> executions() {
            return List.copyOf(executions);
        }
    }

    /**
     * 当前 UTC 时间。
     *
     * @return 时间
     */
    public static Instant now() {
        return Instant.now();
    }

    /**
     * 数据库 UTC 时间。
     *
     * @param instant 瞬时
     * @return DATETIME
     */
    public static LocalDateTime databaseTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
