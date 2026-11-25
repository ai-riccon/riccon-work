package com.riccon.worker.workflow.tool.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.workflow.tool.domain.WorkflowToolApprovalSnapshot;
import com.riccon.worker.workflow.tool.domain.WorkflowToolInvocationInsert;
import com.riccon.worker.workflow.tool.domain.WorkflowToolInvocationStatus;
import com.riccon.worker.workflow.tool.domain.WorkflowToolRunBinding;
import com.riccon.worker.workflow.tool.persistence.WorkflowToolJdbc;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 工作流工具调用仓储。
 *
 * <p>service 只依赖本类，不直接接触 {@code persistence}；所有写入都带租户条件与状态条件。
 * 审批快照与调用记录的读取都限定在 {@code workflow_run} / {@code workflow_approval} 行内，
 * 不提供跨租户或跨运行查询。
 */
@Component
public class WorkflowToolRepository {

    /** 持久化。 */
    private final WorkflowToolJdbc jdbc;

    /**
     * 创建仓储。
     *
     * @param jdbc 持久化
     */
    public WorkflowToolRepository(WorkflowToolJdbc jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    /**
     * 读取最新工具审批快照。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 工具节点 key
     * @return 审批快照；不存在为空
     */
    public Optional<WorkflowToolApprovalSnapshot> findLatestToolApproval(
            TenantId tenantId, UUID runId, String nodeKey) {
        return jdbc.findLatestToolApproval(tenantId, runId, nodeKey);
    }

    /**
     * 读取运行绑定的流程与版本。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @return 绑定；不存在为空
     */
    public Optional<WorkflowToolRunBinding> findRunBinding(TenantId tenantId, UUID runId) {
        return jdbc.findRunBinding(tenantId, runId);
    }

    /**
     * 读取当前 RUNNING 节点尝试号。
     *
     * @param tenantId 租户
     * @param nodeRunId 节点尝试 ID
     * @return 尝试号；节点已不 RUNNING 为空
     */
    public Optional<Integer> findAttempt(TenantId tenantId, UUID nodeRunId) {
        return jdbc.findAttempt(tenantId, nodeRunId);
    }

    /**
     * 读取当前工具节点历史尝试的最晚开始时间。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 工具节点 key
     * @param attempt 当前尝试号
     * @return 历史开始时间；没有历史尝试为空
     */
    public Optional<Instant> findPreviousAttemptStartedAt(TenantId tenantId, UUID runId, String nodeKey, int attempt) {
        return jdbc.findPreviousAttemptStartedAt(tenantId, runId, nodeKey, attempt);
    }

    /**
     * 读取审批已写入的调用状态。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @return 调用状态；尚未执行为空
     */
    public Optional<WorkflowToolInvocationStatus> findInvocationStatus(TenantId tenantId, UUID approvalId) {
        return jdbc.findInvocationStatus(tenantId, approvalId);
    }

    /**
     * 写入 STARTED 调用。
     *
     * @param tenantId 租户
     * @param insert 插入参数
     * @return 插入行数
     */
    public int insertStarted(TenantId tenantId, WorkflowToolInvocationInsert insert) {
        return jdbc.insertStarted(tenantId, insert);
    }

    /**
     * 标记调用成功。
     *
     * @param tenantId 租户
     * @param invocationId 调用 ID
     * @param resultSha256 结果摘要
     * @param resultSizeBytes 结果字节数
     * @param resultPreview 脱敏结果摘要
     * @param durationMs 耗时
     * @param now 完成时间
     * @return 受影响行数
     */
    public int markSucceeded(
            TenantId tenantId,
            UUID invocationId,
            String resultSha256,
            long resultSizeBytes,
            String resultPreview,
            long durationMs,
            Instant now) {
        return jdbc.markSucceeded(
                tenantId, invocationId, resultSha256, resultSizeBytes, resultPreview, durationMs, now);
    }

    /**
     * 标记调用失败。
     *
     * @param tenantId 租户
     * @param invocationId 调用 ID
     * @param status FAILED 或 DENIED
     * @param errorCode 稳定错误码
     * @param durationMs 耗时
     * @param now 完成时间
     * @return 受影响行数
     */
    public int markTerminated(
            TenantId tenantId,
            UUID invocationId,
            WorkflowToolInvocationStatus status,
            String errorCode,
            long durationMs,
            Instant now) {
        return jdbc.markTerminated(tenantId, invocationId, status, errorCode, durationMs, now);
    }

    /**
     * 把调用 ID 绑定到当前节点尝试。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param attempt 尝试号
     * @param invocationId 调用 ID
     * @param now 当前时间
     * @return 受影响行数；0 表示节点已不在 RUNNING
     */
    public int bindNodeInvocation(
            TenantId tenantId, UUID runId, String nodeKey, int attempt, UUID invocationId, Instant now) {
        return jdbc.bindNodeInvocation(tenantId, runId, nodeKey, attempt, invocationId, now);
    }
}
