package com.riccon.api.workflow.approval.repository;

import com.riccon.api.workflow.approval.domain.WorkflowApprovalCitationSnapshot;
import com.riccon.api.workflow.approval.domain.WorkflowApprovalSnapshot;
import com.riccon.api.workflow.approval.persistence.WorkflowApprovalCitationRow;
import com.riccon.api.workflow.approval.persistence.WorkflowApprovalJdbc;
import com.riccon.api.workflow.approval.persistence.WorkflowApprovalRow;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 审批仓储。
 *
 * <p>服务层只依赖本类，不直接接触 SQL；所有读取按租户过滤，决定写入使用条件更新表达状态机。审批响应
 * 与范围摘要均由同一仓储投影，避免待办/详情/决定三处各写一套字段口径。
 */
@Component
public class WorkflowApprovalRepository {

    /** 审批持久化。 */
    private final WorkflowApprovalJdbc approvalJdbc;

    /**
     * 创建审批仓储。
     *
     * @param approvalJdbc 审批持久化
     */
    public WorkflowApprovalRepository(WorkflowApprovalJdbc approvalJdbc) {
        this.approvalJdbc = Objects.requireNonNull(approvalJdbc, "approvalJdbc must not be null");
    }

    /**
     * 统计当前用户的待办数。
     *
     * @param tenantId 租户
     * @param userId 当前用户
     * @param now 当前时间
     * @return 待办数
     */
    public long countInbox(TenantId tenantId, UUID userId, Instant now) {
        return approvalJdbc.countInbox(tenantId, userId, now);
    }

    /**
     * 分页读取当前用户的待办。
     *
     * @param tenantId 租户
     * @param userId 当前用户
     * @param now 当前时间
     * @param limit 每页大小
     * @param offset 偏移
     * @return 审批快照
     */
    public List<WorkflowApprovalSnapshot> listInbox(
            TenantId tenantId, UUID userId, Instant now, int limit, int offset) {
        return approvalJdbc.listInbox(tenantId, userId, now, limit, offset).stream()
                .map(WorkflowApprovalRow::toSnapshot)
                .toList();
    }

    /**
     * 按 ID 读取未到期审批。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @param now 当前时间
     * @return 审批快照；不存在或已到期为空
     */
    public Optional<WorkflowApprovalSnapshot> findById(TenantId tenantId, UUID approvalId, Instant now) {
        return approvalJdbc.findById(tenantId, approvalId, now).map(WorkflowApprovalRow::toSnapshot);
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
        return approvalJdbc.isAssignedUser(tenantId, approvalId, userId);
    }

    /**
     * 判断账号是否在本租户启用。
     *
     * @param tenantId 租户
     * @param userId 用户 ID
     * @return 是否启用
     */
    public boolean isActiveUser(TenantId tenantId, UUID userId) {
        return approvalJdbc.isActiveUser(tenantId, userId);
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
        return approvalJdbc.hasRole(tenantId, userId, roleCode);
    }

    /**
     * 读取运行当前状态（未到期）。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param now 当前时间
     * @return 状态；运行不存在或已到期为空
     */
    public Optional<WorkflowRunStatus> findRunStatus(TenantId tenantId, UUID runId, Instant now) {
        return approvalJdbc.findRunStatus(tenantId, runId, now);
    }

    /**
     * 读取运行绑定版本的发布图 JSON（未到期）。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param now 当前时间
     * @return 图 JSON；运行不存在或已到期为空
     */
    public Optional<String> findRunGraphJson(TenantId tenantId, UUID runId, Instant now) {
        return approvalJdbc.findRunGraphJson(tenantId, runId, now);
    }

    /**
     * 读取审批创建前已执行上游节点的引用快照 JSON。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param createdAt 审批创建时间
     * @return 引用 JSON 列表
     */
    public List<String> findSucceededCitations(TenantId tenantId, UUID runId, Instant createdAt) {
        return approvalJdbc.findSucceededCitations(tenantId, runId, createdAt);
    }

    /**
     * 批量读取一页待办涉及的运行节点引用快照，按运行分组。
     *
     * <p>列表接口一次查询覆盖全部运行，避免逐条审批查询（N+1）；服务层按每个审批的
     * {@code createdAt} 过滤上游节点。返回领域投影而不是持久化行，保证 service 不直接依赖
     * {@code ..persistence..}（REV-TASK-0228-002 F-006）。
     *
     * @param tenantId 租户
     * @param runIds 运行 ID 集合
     * @return 运行 ID 到引用快照的映射
     */
    public Map<UUID, List<WorkflowApprovalCitationSnapshot>> findSucceededCitationsByRuns(
            TenantId tenantId, List<UUID> runIds) {
        List<WorkflowApprovalCitationRow> rows = approvalJdbc.findSucceededCitationsByRuns(tenantId, runIds);
        Map<UUID, List<WorkflowApprovalCitationSnapshot>> byRun = new LinkedHashMap<>();
        for (WorkflowApprovalCitationRow row : rows) {
            WorkflowApprovalCitationSnapshot snapshot =
                    new WorkflowApprovalCitationSnapshot(row.workflowRunId(), row.finishedAt(), row.citationsJson());
            byRun.computeIfAbsent(snapshot.workflowRunId(), key -> new ArrayList<>())
                    .add(snapshot);
        }
        return byRun;
    }

    /**
     * 读取指定用户审批人列表。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @return 用户 ID 列表
     */
    public List<UUID> findAssigneeUserIds(TenantId tenantId, UUID approvalId) {
        return approvalJdbc.findAssigneeUserIds(tenantId, approvalId);
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
        return approvalJdbc.hasNodeAttempt(tenantId, runId, nodeKey);
    }

    /**
     * 条件批准审批。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @param decidedBy 审批人
     * @param comment 理由
     * @param now 决定时间
     * @return 受影响行数
     */
    public int approve(TenantId tenantId, UUID approvalId, UUID decidedBy, String comment, Instant now) {
        return approvalJdbc.approve(tenantId, approvalId, decidedBy, comment, now);
    }

    /**
     * 条件拒绝审批。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @param decidedBy 审批人
     * @param comment 理由
     * @param now 决定时间
     * @return 受影响行数
     */
    public int reject(TenantId tenantId, UUID approvalId, UUID decidedBy, String comment, Instant now) {
        return approvalJdbc.reject(tenantId, approvalId, decidedBy, comment, now);
    }

    /**
     * 写审批节点终态。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param attempt 尝试号
     * @param status 终态
     * @param outputJson 输出 JSON
     * @param outputSummary 摘要
     * @param errorCode 稳定错误码
     * @param errorMessage 脱敏原因
     * @param now 当前时间
     * @return 受影响行数
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
        return approvalJdbc.finishApprovalNode(
                tenantId, runId, nodeKey, attempt, status, outputJson, outputSummary, errorCode, errorMessage, now);
    }

    /**
     * 批准后恢复运行。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param targetNodeKey 恢复节点
     * @param waitMs 等待毫秒
     * @param now 当前时间
     * @return 受影响行数
     */
    public int resumeRunPending(TenantId tenantId, UUID runId, String targetNodeKey, long waitMs, Instant now) {
        return approvalJdbc.resumeRunPending(tenantId, runId, targetNodeKey, waitMs, now);
    }

    /**
     * 拒绝后运行失败。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 审批节点
     * @param message 脱敏原因
     * @param waitMs 等待毫秒
     * @param now 当前时间
     * @return 受影响行数
     */
    public int failRunRejected(
            TenantId tenantId, UUID runId, String nodeKey, String message, long waitMs, Instant now) {
        return approvalJdbc.failRunRejected(tenantId, runId, nodeKey, message, waitMs, now);
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
        return approvalJdbc.skipPendingNode(tenantId, runId, nodeKey, summary, now);
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
        return approvalJdbc.insertSkippedNode(tenantId, id, runId, nodeKey, nodeType, nodeName, summary, now);
    }
}
