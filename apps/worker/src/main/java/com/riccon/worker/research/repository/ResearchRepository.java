package com.riccon.worker.research.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.research.StageStatus;
import com.riccon.worker.research.domain.ApprovalRecord;
import com.riccon.worker.research.domain.ResearchTaskRecord;
import com.riccon.worker.research.domain.RunStageRecord;
import com.riccon.worker.research.persistence.ApprovalJdbc;
import com.riccon.worker.research.persistence.ResearchTaskJdbc;
import com.riccon.worker.research.persistence.RunStageJdbc;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 报告任务、审批与运行阶段仓储。
 *
 * <p>覆盖 HITL 与报告编排所需的最小读写：审批创建/读取、阶段时间线、任务状态投影；
 * 产物、版本与引用由 {@link ArtifactRepository} 负责。
 */
@Component
public class ResearchRepository {

    /** 任务访问。 */
    private final ResearchTaskJdbc taskJdbc;

    /** 审批访问。 */
    private final ApprovalJdbc approvalJdbc;

    /** 阶段访问。 */
    private final RunStageJdbc stageJdbc;

    /**
     * 创建报告仓储。
     *
     * @param taskJdbc 任务访问
     * @param approvalJdbc 审批访问
     * @param stageJdbc 阶段访问
     */
    public ResearchRepository(ResearchTaskJdbc taskJdbc, ApprovalJdbc approvalJdbc, RunStageJdbc stageJdbc) {
        this.taskJdbc = Objects.requireNonNull(taskJdbc, "taskJdbc must not be null");
        this.approvalJdbc = Objects.requireNonNull(approvalJdbc, "approvalJdbc must not be null");
        this.stageJdbc = Objects.requireNonNull(stageJdbc, "stageJdbc must not be null");
    }

    /**
     * 读取任务。
     *
     * @param tenantId 租户
     * @param taskId 任务 ID
     * @return 任务
     */
    public Optional<ResearchTaskRecord> findTask(TenantId tenantId, UUID taskId) {
        return taskJdbc.find(tenantId, taskId);
    }

    /**
     * 标记任务运行中并推进阶段。
     *
     * @param tenantId 租户
     * @param taskId 任务 ID
     * @param runId 当前运行 ID
     * @param stage 当前阶段
     * @return 受影响行数
     */
    public int markTaskRunning(TenantId tenantId, UUID taskId, UUID runId, String stage) {
        return taskJdbc.markRunning(tenantId, taskId, runId, stage);
    }

    /**
     * 标记任务等待审批。
     *
     * @param tenantId 租户
     * @param taskId 任务 ID
     * @param runId 当前运行 ID
     * @param stage 当前阶段
     * @return 受影响行数
     */
    public int markTaskWaitingApproval(TenantId tenantId, UUID taskId, UUID runId, String stage) {
        return taskJdbc.markWaitingApproval(tenantId, taskId, runId, stage);
    }

    /**
     * 标记任务成功；必须在产物保存之后调用。
     *
     * @param tenantId 租户
     * @param taskId 任务 ID
     * @param runId 当前运行 ID
     * @return 受影响行数
     */
    public int markTaskSucceeded(TenantId tenantId, UUID taskId, UUID runId) {
        return taskJdbc.markSucceeded(tenantId, taskId, runId);
    }

    /**
     * 标记任务失败。
     *
     * @param tenantId 租户
     * @param taskId 任务 ID
     * @param runId 当前运行 ID
     * @param errorCode 错误码
     * @param errorMessage 脱敏错误
     * @return 受影响行数
     */
    public int markTaskFailed(TenantId tenantId, UUID taskId, UUID runId, String errorCode, String errorMessage) {
        return taskJdbc.markFailed(tenantId, taskId, runId, errorCode, errorMessage);
    }

    /**
     * 标记任务取消。
     *
     * @param tenantId 租户
     * @param taskId 任务 ID
     * @param runId 当前运行 ID
     * @param failureCode 终止原因码，可为空
     * @param failureMessage 脱敏终止原因，可为空
     * @return 受影响行数
     */
    public int markTaskCancelled(
            TenantId tenantId, UUID taskId, UUID runId, String failureCode, String failureMessage) {
        return taskJdbc.markCancelled(tenantId, taskId, runId, failureCode, failureMessage);
    }

    /**
     * 读取审批。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param stage 阶段
     * @param attemptNo 尝试号
     * @return 审批
     */
    public Optional<ApprovalRecord> findApproval(TenantId tenantId, UUID runId, String stage, int attemptNo) {
        return approvalJdbc.find(tenantId, runId, stage, attemptNo);
    }

    /**
     * 按 ID 读取审批。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @return 审批
     */
    public Optional<ApprovalRecord> findApproval(TenantId tenantId, UUID approvalId) {
        return approvalJdbc.findById(tenantId, approvalId);
    }

    /**
     * 创建待审批。
     *
     * @param tenantId 租户
     * @param approval 审批参数
     */
    public void insertPendingApproval(TenantId tenantId, PendingApproval approval) {
        approvalJdbc.insertPending(
                tenantId,
                approval.id(),
                approval.runId(),
                approval.researchTaskId(),
                approval.stage(),
                approval.attemptNo(),
                approval.toolId(),
                approval.toolName(),
                approval.paramsPreview(),
                approval.paramsCiphertext(),
                approval.paramsKeyId(),
                approval.paramsSha256(),
                approval.requestedBy());
    }

    /**
     * 取消 PENDING 审批。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     */
    public void cancelPendingApprovals(TenantId tenantId, UUID runId) {
        approvalJdbc.cancelPending(tenantId, runId);
    }

    /**
     * 读取阶段。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param stage 阶段
     * @param attemptNo 尝试号
     * @return 阶段
     */
    public Optional<RunStageRecord> findStage(TenantId tenantId, UUID runId, String stage, int attemptNo) {
        return stageJdbc.find(tenantId, runId, stage, attemptNo);
    }

    /**
     * 启动阶段。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param stage 阶段
     * @param attemptNo 尝试号
     * @return 阶段记录 ID
     */
    public UUID startStage(TenantId tenantId, UUID runId, String stage, int attemptNo) {
        return stageJdbc.start(tenantId, runId, stage, attemptNo);
    }

    /**
     * 把运行下所有 RUNNING 阶段标记为 SKIPPED。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @return 更新的阶段数量
     */
    public int skipRunningStages(TenantId tenantId, UUID runId) {
        return stageJdbc.skipRunning(tenantId, runId);
    }

    /**
     * 更新运行中阶段的详情。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param stage 阶段
     * @param attemptNo 尝试号
     * @param detailsJson 摘要
     */
    public void updateStageDetails(TenantId tenantId, UUID runId, String stage, int attemptNo, String detailsJson) {
        stageJdbc.updateDetails(tenantId, runId, stage, attemptNo, detailsJson);
    }

    /**
     * 结束阶段。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param stage 阶段
     * @param attemptNo 尝试号
     * @param status 终态
     * @param detailsJson 摘要
     * @param errorCode 错误码
     * @param errorMessage 脱敏错误
     */
    public void finishStage(
            TenantId tenantId,
            UUID runId,
            String stage,
            int attemptNo,
            StageStatus status,
            String detailsJson,
            String errorCode,
            String errorMessage) {
        stageJdbc.finish(tenantId, runId, stage, attemptNo, status, detailsJson, errorCode, errorMessage);
    }

    /**
     * 待审批插入参数。
     *
     * @param id 审批 ID
     * @param runId 运行 ID
     * @param researchTaskId 任务 ID
     * @param stage 阶段
     * @param attemptNo 尝试号
     * @param toolId 工具 ID
     * @param toolName 工具名
     * @param paramsPreview 脱敏预览 JSON
     * @param paramsCiphertext 完整参数密文
     * @param paramsKeyId 加密所用 DEK 标识
     * @param paramsSha256 参数摘要
     * @param requestedBy 发起人
     */
    public record PendingApproval(
            UUID id,
            UUID runId,
            UUID researchTaskId,
            String stage,
            int attemptNo,
            UUID toolId,
            String toolName,
            String paramsPreview,
            byte[] paramsCiphertext,
            String paramsKeyId,
            String paramsSha256,
            UUID requestedBy) {}
}
