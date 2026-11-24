package com.riccon.api.workflow.run.repository;

import com.riccon.api.workflow.domain.WorkflowGraph;
import com.riccon.api.workflow.domain.WorkflowGraphFormatException;
import com.riccon.api.workflow.domain.WorkflowGraphJson;
import com.riccon.api.workflow.run.domain.WorkflowRunDefinitionSnapshot;
import com.riccon.api.workflow.run.domain.WorkflowRunNodeSnapshot;
import com.riccon.api.workflow.run.domain.WorkflowRunSnapshot;
import com.riccon.api.workflow.run.domain.WorkflowRunVersionSnapshot;
import com.riccon.api.workflow.run.persistence.WorkflowRunDefinitionRow;
import com.riccon.api.workflow.run.domain.WorkflowRunInsert;
import com.riccon.api.workflow.run.persistence.WorkflowRunJdbc;
import com.riccon.api.workflow.run.domain.WorkflowRunNodeInsert;
import com.riccon.api.workflow.run.persistence.WorkflowRunNodeRow;
import com.riccon.api.workflow.run.persistence.WorkflowRunRow;
import com.riccon.api.workflow.run.persistence.WorkflowRunVersionRow;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 工作流运行仓储。
 *
 * <p>把持久化行转换为运行准入领域快照，并封装条件状态更新；服务层不接触 {@code JdbcClient} 或 SQL。
 * 版本图在仓储层解析为 {@link WorkflowGraph}，让服务层只处理已校验的图结构（ADR-0015 分层）。
 */
@Repository
public class WorkflowRunRepository {

    /** 运行 SQL。 */
    private final WorkflowRunJdbc runJdbc;

    /**
     * 创建运行仓储。
     *
     * @param runJdbc 运行 SQL
     */
    public WorkflowRunRepository(WorkflowRunJdbc runJdbc) {
        this.runJdbc = Objects.requireNonNull(runJdbc, "runJdbc must not be null");
    }

    /**
     * 按 ID 读取运行。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @return 运行快照
     */
    public Optional<WorkflowRunSnapshot> findById(TenantId tenantId, UUID runId) {
        return runJdbc.findById(tenantId, runId).map(WorkflowRunRepository::toSnapshot);
    }

    /**
     * 按创建幂等键读取既有运行。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @param requesterUserId 发起人
     * @param idempotencyKey 幂等键
     * @return 既有运行
     */
    public Optional<WorkflowRunSnapshot> findByIdempotencyKey(
            TenantId tenantId, UUID workflowId, UUID requesterUserId, String idempotencyKey) {
        return runJdbc.findByIdempotencyKey(tenantId, workflowId, requesterUserId, idempotencyKey)
                .map(WorkflowRunRepository::toSnapshot);
    }

    /**
     * 读取流程定义摘要。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @return 定义快照
     */
    public Optional<WorkflowRunDefinitionSnapshot> findDefinition(TenantId tenantId, UUID workflowId) {
        return runJdbc.findDefinition(tenantId, workflowId).map(WorkflowRunRepository::toSnapshot);
    }

    /**
     * 读取发布版本快照并解析图。
     *
     * @param tenantId 当前租户
     * @param versionId 版本 ID
     * @return 版本快照
     * @throws IllegalStateException 已发布版本的图 JSON 损坏（数据错误，不静默降级）
     */
    public Optional<WorkflowRunVersionSnapshot> findVersion(TenantId tenantId, UUID versionId) {
        return runJdbc.findVersion(tenantId, versionId).map(WorkflowRunRepository::toSnapshot);
    }

    /**
     * 统计租户活跃运行数。
     *
     * @param tenantId 当前租户
     * @return 活跃运行数
     */
    public long countActiveTenant(TenantId tenantId) {
        return runJdbc.countActiveTenant(tenantId);
    }

    /**
     * 统计单流程活跃运行数。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @return 活跃运行数
     */
    public long countActiveWorkflow(TenantId tenantId, UUID workflowId) {
        return runJdbc.countActiveWorkflow(tenantId, workflowId);
    }

    /**
     * 插入待执行运行。
     *
     * @param tenantId 当前租户
     * @param insert 插入参数
     */
    public void insertRun(TenantId tenantId, WorkflowRunInsert insert) {
        runJdbc.insertRun(tenantId, insert);
    }

    /**
     * 条件取消运行。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param expectedStatus 前一状态
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markCancelled(TenantId tenantId, UUID runId, WorkflowRunStatus expectedStatus, Instant now) {
        return runJdbc.markCancelled(tenantId, runId, expectedStatus, now);
    }

    /**
     * 取消运行下待审批项。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param now 当前时间
     * @return 受影响行数
     */
    public int cancelPendingApprovals(TenantId tenantId, UUID runId, Instant now) {
        return runJdbc.cancelPendingApprovals(tenantId, runId, now);
    }

    /**
     * 条件恢复失败运行为待执行。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param currentNodeKey 失败节点 key
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markRetried(TenantId tenantId, UUID runId, String currentNodeKey, Instant now) {
        return runJdbc.markRetried(tenantId, runId, currentNodeKey, now);
    }

    /**
     * 读取节点最新尝试。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @return 最新尝试快照
     */
    public Optional<WorkflowRunNodeSnapshot> findLatestNode(TenantId tenantId, UUID runId, String nodeKey) {
        return runJdbc.findLatestNode(tenantId, runId, nodeKey).map(WorkflowRunRepository::toSnapshot);
    }

    /**
     * 插入新的节点尝试。
     *
     * @param tenantId 当前租户
     * @param insert 插入参数
     */
    public void insertNodeAttempt(TenantId tenantId, WorkflowRunNodeInsert insert) {
        runJdbc.insertNodeAttempt(tenantId, insert);
    }

    /**
     * 恢复失败节点的可达下游跳过节点为待执行。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param nodeKeys 可达下游节点 key
     * @param now 当前时间
     * @return 受影响行数
     */
    public int resetSkippedNodes(TenantId tenantId, UUID runId, Set<String> nodeKeys, Instant now) {
        return runJdbc.resetSkippedNodes(tenantId, runId, nodeKeys, now);
    }

    /**
     * 映射运行快照。
     *
     * @param row 持久化行
     * @return 运行快照
     */
    private static WorkflowRunSnapshot toSnapshot(WorkflowRunRow row) {
        return new WorkflowRunSnapshot(
                row.id(),
                row.workflowName(),
                row.workflowId(),
                row.workflowVersionId(),
                row.versionNo(),
                row.requesterUserId(),
                row.status(),
                row.currentNodeKey(),
                row.errorCode(),
                row.errorMessage(),
                row.resultStatus(),
                row.createdAt(),
                row.updatedAt(),
                row.startedAt(),
                row.finishedAt(),
                row.retentionExpiresAt(),
                row.idempotencyKey());
    }

    /**
     * 映射定义快照。
     *
     * @param row 持久化行
     * @return 定义快照
     */
    private static WorkflowRunDefinitionSnapshot toSnapshot(WorkflowRunDefinitionRow row) {
        return new WorkflowRunDefinitionSnapshot(
                row.id(), row.name(), row.ownerUserId(), row.status(), row.publishedVersionId());
    }

    /**
     * 映射版本快照并解析图。
     *
     * @param row 持久化行
     * @return 版本快照
     */
    private static WorkflowRunVersionSnapshot toSnapshot(WorkflowRunVersionRow row) {
        WorkflowGraph graph;
        try {
            graph = WorkflowGraphJson.parse(row.graphJson());
        } catch (WorkflowGraphFormatException exception) {
            throw new IllegalStateException("invalid workflow_version.graph_json for version " + row.id(), exception);
        }
        return new WorkflowRunVersionSnapshot(
                row.id(),
                row.workflowId(),
                row.versionNo(),
                row.runScope(),
                row.departmentId(),
                row.nodeCount(),
                graph);
    }

    /**
     * 映射节点尝试快照。
     *
     * @param row 持久化行
     * @return 节点尝试快照
     */
    private static WorkflowRunNodeSnapshot toSnapshot(WorkflowRunNodeRow row) {
        return new WorkflowRunNodeSnapshot(
                row.id(), row.nodeKey(), row.nodeType(), row.nodeName(), row.attempt(), row.status());
    }
}
