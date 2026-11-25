package com.riccon.worker.workflow.approval.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.worker.common.UuidV7;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalInsert;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalParkingSnapshot;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalRow;
import com.riccon.worker.workflow.approval.domain.WorkflowRunApprovalState;
import com.riccon.worker.workflow.approval.persistence.WorkflowApprovalCatalogJdbc;
import com.riccon.worker.workflow.approval.persistence.WorkflowApprovalJdbc;
import com.riccon.worker.workflow.engine.domain.WorkflowNodeAttemptInsert;
import com.riccon.worker.workflow.engine.persistence.WorkflowEngineJdbc;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 工作流审批仓储。
 *
 * <p>对服务层只暴露审批、assignee、运行停车/恢复与节点跳过操作；服务层不直接依赖
 * {@code persistence}。节点尝试的插入与跳过复用 T06b 的 {@link WorkflowEngineJdbc}，避免审批
 * 模块复制状态字符串与列宽逻辑（ADR-0024 §4.2/§5.4）。
 */
@Component
public class WorkflowApprovalRepository {

    /** 审批持久化。 */
    private final WorkflowApprovalJdbc approvalJdbc;

    /** 全局工具目录（只读工具名快照）。 */
    private final WorkflowApprovalCatalogJdbc catalogJdbc;

    /** 引擎持久化；复用节点尝试插入/跳过 SQL。 */
    private final WorkflowEngineJdbc engineJdbc;

    /**
     * 创建审批仓储。
     *
     * @param approvalJdbc 审批持久化
     * @param catalogJdbc 全局工具目录
     * @param engineJdbc 引擎持久化
     */
    public WorkflowApprovalRepository(
            WorkflowApprovalJdbc approvalJdbc, WorkflowApprovalCatalogJdbc catalogJdbc, WorkflowEngineJdbc engineJdbc) {
        this.approvalJdbc = Objects.requireNonNull(approvalJdbc, "approvalJdbc must not be null");
        this.catalogJdbc = Objects.requireNonNull(catalogJdbc, "catalogJdbc must not be null");
        this.engineJdbc = Objects.requireNonNull(engineJdbc, "engineJdbc must not be null");
    }

    /**
     * 读取审批停车所需运行快照。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @return 快照；不存在为空
     */
    public Optional<WorkflowApprovalParkingSnapshot> findParkingSnapshot(TenantId tenantId, UUID runId) {
        return approvalJdbc.findParkingSnapshot(tenantId, runId);
    }

    /**
     * 读取运行审批恢复状态。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @return 状态；不存在为空
     */
    public Optional<WorkflowRunApprovalState> findRunApprovalState(TenantId tenantId, UUID runId) {
        return approvalJdbc.findRunApprovalState(tenantId, runId);
    }

    /**
     * 读取运行绑定版本的发布图 JSON。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @return 图 JSON；不存在为空
     */
    public Optional<String> findRunGraphJson(TenantId tenantId, UUID runId) {
        return approvalJdbc.findRunGraphJson(tenantId, runId);
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
        return approvalJdbc.findRunningAttempt(tenantId, runId, nodeKey);
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
     * 读取审批行。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @return 审批行；不存在为空
     */
    public Optional<WorkflowApprovalRow> findApproval(TenantId tenantId, UUID approvalId) {
        return approvalJdbc.findApproval(tenantId, approvalId);
    }

    /**
     * 插入 PENDING 审批。
     *
     * @param tenantId 租户
     * @param insert 审批参数
     * @return 受影响行数
     */
    public int insertApproval(TenantId tenantId, WorkflowApprovalInsert insert) {
        return approvalJdbc.insertApproval(tenantId, insert);
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
        return approvalJdbc.insertAssignees(tenantId, approvalId, userIds, now);
    }

    /**
     * 解析可用用户审批人。
     *
     * @param tenantId 租户
     * @param userIds 配置用户 ID
     * @param excludeUserId 发起人
     * @return 可用用户 ID
     */
    public List<UUID> findActiveUserAssignees(TenantId tenantId, Collection<UUID> userIds, UUID excludeUserId) {
        return approvalJdbc.findActiveUserAssignees(tenantId, userIds, excludeUserId);
    }

    /**
     * 解析可用角色审批人。
     *
     * @param tenantId 租户
     * @param roleCode 角色码
     * @param excludeUserId 发起人
     * @return 可用用户 ID
     */
    public List<UUID> findActiveRoleAssignees(TenantId tenantId, String roleCode, UUID excludeUserId) {
        return approvalJdbc.findActiveRoleAssignees(tenantId, roleCode, excludeUserId);
    }

    /**
     * 读取 ACTIVE 工具名。
     *
     * @param toolId 工具 ID
     * @return 工具名；不存在或非 ACTIVE 为空
     */
    public Optional<String> findActiveToolName(UUID toolId) {
        return catalogJdbc.findActiveToolName(toolId);
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
     * 审批节点进入 WAITING_APPROVAL。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param nodeKey 节点 key
     * @param attempt 尝试号
     * @param approvalId 审批 ID
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markNodeWaiting(
            TenantId tenantId, UUID runId, String nodeKey, int attempt, UUID approvalId, Instant now) {
        return approvalJdbc.markNodeWaiting(tenantId, runId, nodeKey, attempt, approvalId, now);
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
     * @return 受影响行数
     */
    public int markRunWaiting(
            TenantId tenantId,
            UUID runId,
            String nodeKey,
            String leaseOwner,
            String errorCode,
            String errorMessage,
            Instant now) {
        return approvalJdbc.markRunWaiting(tenantId, runId, nodeKey, leaseOwner, errorCode, errorMessage, now);
    }

    /**
     * 条件批准审批。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @param decidedBy 审批人
     * @param comment 理由；可空
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
     * @param comment 拒绝理由
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
     * @param status SUCCEEDED/FAILED
     * @param outputJson 输出 JSON；可空
     * @param outputSummary 摘要
     * @param errorCode 错误码；可空
     * @param errorMessage 原因；可空
     * @param now 当前时间
     * @return 受影响行数
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
     * 把 PENDING 节点尝试标记为 SKIPPED。
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
     * 插入一条 SKIPPED 节点尝试（未执行节点不创建 PENDING 再跳过，直接写终态）。
     *
     * @param tenantId 租户
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
            UUID runId,
            String nodeKey,
            WorkflowNodeType nodeType,
            String nodeName,
            String summary,
            Instant now) {
        return engineJdbc.insertNodeAttempt(
                tenantId,
                new WorkflowNodeAttemptInsert(
                        UuidV7.generate(),
                        runId,
                        nodeKey,
                        nodeType,
                        nodeName,
                        1,
                        WorkflowNodeStatus.SKIPPED,
                        null,
                        summary,
                        null,
                        null,
                        now));
    }
}
