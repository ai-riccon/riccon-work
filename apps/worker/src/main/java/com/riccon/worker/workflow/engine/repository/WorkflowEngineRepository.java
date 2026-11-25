package com.riccon.worker.workflow.engine.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.platform.domain.workflow.WorkflowResultStatus;
import com.riccon.worker.workflow.engine.domain.WorkflowNodeAttemptInsert;
import com.riccon.worker.workflow.engine.domain.WorkflowNodeAttemptRow;
import com.riccon.worker.workflow.engine.domain.WorkflowNodeFinish;
import com.riccon.worker.workflow.engine.domain.WorkflowRunGraphSnapshot;
import com.riccon.worker.workflow.engine.persistence.WorkflowEngineJdbc;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 串行引擎仓储。
 *
 * <p>对服务层只暴露运行对推进、节点尝试与模型关联；服务层不直接依赖 {@code persistence}，
 * 保持 MVC 单向（ADR-0024 §4.2）。
 */
@Component
public class WorkflowEngineRepository {

    /** 引擎持久化。 */
    private final WorkflowEngineJdbc engineJdbc;

    /**
     * 创建仓储。
     *
     * @param engineJdbc 引擎持久化
     */
    public WorkflowEngineRepository(WorkflowEngineJdbc engineJdbc) {
        this.engineJdbc = Objects.requireNonNull(engineJdbc, "engineJdbc must not be null");
    }

    /**
     * 读取运行与发布图快照。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @return 快照；不存在为空
     */
    public Optional<WorkflowRunGraphSnapshot> findRunGraph(TenantId tenantId, UUID runId) {
        return engineJdbc.findRunGraph(tenantId, runId);
    }

    /**
     * 读取节点尝试时间线。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @return 尝试列表
     */
    public List<WorkflowNodeAttemptRow> findNodeAttempts(TenantId tenantId, UUID runId) {
        return engineJdbc.findNodeAttempts(tenantId, runId);
    }

    /**
     * 插入节点尝试。
     *
     * @param tenantId 租户
     * @param insert 插入参数
     * @return 受影响行数
     */
    public int insertNodeAttempt(TenantId tenantId, WorkflowNodeAttemptInsert insert) {
        return engineJdbc.insertNodeAttempt(tenantId, insert);
    }

    /**
     * 节点开始。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param attempt 尝试号
     * @param now 当前时间
     * @return 受影响行数
     */
    public int startNodeAttempt(TenantId tenantId, UUID runId, String nodeKey, int attempt, Instant now) {
        return engineJdbc.startNodeAttempt(tenantId, runId, nodeKey, attempt, now);
    }

    /**
     * 写节点终态。
     *
     * @param tenantId 租户
     * @param finish 终态参数
     * @return 受影响行数
     */
    public int finishNodeAttempt(TenantId tenantId, WorkflowNodeFinish finish) {
        return engineJdbc.finishNodeAttempt(tenantId, finish);
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
        return engineJdbc.failStaleAttempt(tenantId, runId, nodeKey, attempt, now);
    }

    /**
     * 把 PENDING 尝试标记为 SKIPPED。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param summary 原因摘要
     * @param now 当前时间
     * @return 受影响行数
     */
    public int skipPendingNode(TenantId tenantId, UUID runId, String nodeKey, String summary, Instant now) {
        return engineJdbc.skipPendingNode(tenantId, runId, nodeKey, summary, now);
    }

    /**
     * 关闭 PENDING 尝试为失败/取消。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param status FAILED 或 CANCELLED
     * @param errorCode 错误码
     * @param message 原因
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
        return engineJdbc.closePendingNode(tenantId, runId, nodeKey, status, errorCode, message, now);
    }

    /**
     * 取消运行后把剩余节点置为 CANCELLED。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param message 原因
     * @param now 当前时间
     * @return 受影响行数
     */
    public int cancelRemainingNodes(TenantId tenantId, UUID runId, String message, Instant now) {
        return engineJdbc.cancelRemainingNodes(tenantId, runId, message, now);
    }

    /**
     * 推进当前节点。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param leaseOwner 租约持有者
     * @param nodeKey 节点 key
     * @param now 当前时间
     * @return 受影响行数
     */
    public int updateCurrentNode(TenantId tenantId, UUID runId, String leaseOwner, String nodeKey, Instant now) {
        return engineJdbc.updateCurrentNode(tenantId, runId, leaseOwner, nodeKey, now);
    }

    /**
     * 累加执行时间。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param leaseOwner 租约持有者
     * @param deltaMs 增量毫秒
     * @param now 当前时间
     * @return 受影响行数
     */
    public int addExecutionMs(TenantId tenantId, UUID runId, String leaseOwner, long deltaMs, Instant now) {
        return engineJdbc.addExecutionMs(tenantId, runId, leaseOwner, deltaMs, now);
    }

    /**
     * 运行成功。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param leaseOwner 租约持有者
     * @param nodeKey END 节点 key
     * @param resultJson 结果 JSON
     * @param resultSizeBytes 结果字节数
     * @param deltaMs 执行片段毫秒
     * @param now 当前时间
     * @return 受影响行数
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
        return engineJdbc.finishRunSucceeded(
                tenantId, runId, leaseOwner, nodeKey, resultJson, resultSizeBytes, deltaMs, now);
    }

    /**
     * 运行失败。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param leaseOwner 租约持有者
     * @param nodeKey 当前节点 key
     * @param errorCode 错误码
     * @param errorMessage 原因
     * @param resultStatus 结果状态
     * @param resultSizeBytes 结果大小
     * @param deltaMs 执行片段毫秒
     * @param now 当前时间
     * @return 受影响行数
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
        return engineJdbc.finishRunFailed(
                tenantId,
                runId,
                leaseOwner,
                nodeKey,
                errorCode,
                errorMessage,
                resultStatus,
                resultSizeBytes,
                deltaMs,
                now);
    }

    /**
     * 关联模型调用到工作流运行/节点。
     *
     * @param tenantId 租户
     * @param invocationId 模型调用 ID
     * @param runId 工作流运行 ID
     * @param nodeRunId 节点尝试 ID
     * @return 受影响行数
     */
    public int linkModelInvocation(TenantId tenantId, UUID invocationId, UUID runId, UUID nodeRunId) {
        return engineJdbc.linkModelInvocation(tenantId, invocationId, runId, nodeRunId);
    }

    /**
     * 批量读取知识库文档 checksum。
     *
     * @param tenantId 租户
     * @param documentIds 文档 ID
     * @return 文档 ID 到 checksum 的映射
     */
    public Map<UUID, String> findKnowledgeChecksums(TenantId tenantId, Collection<UUID> documentIds) {
        return engineJdbc.findKnowledgeChecksums(tenantId, documentIds);
    }
}
