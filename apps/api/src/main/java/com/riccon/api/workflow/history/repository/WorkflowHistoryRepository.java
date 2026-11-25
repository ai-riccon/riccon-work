package com.riccon.api.workflow.history.repository;

import com.riccon.api.workflow.history.domain.WorkflowApprovalCounts;
import com.riccon.api.workflow.history.domain.WorkflowHistoryActor;
import com.riccon.api.workflow.history.domain.WorkflowHistoryFilter;
import com.riccon.api.workflow.history.domain.WorkflowRunApprovalSnapshot;
import com.riccon.api.workflow.history.domain.WorkflowRunHistorySnapshot;
import com.riccon.api.workflow.history.domain.WorkflowRunNodeSnapshot;
import com.riccon.api.workflow.history.persistence.WorkflowHistoryJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 运行历史仓储。
 *
 * <p>服务层只依赖本类，不直接接触 SQL；所有读取按租户过滤并在 SQL 层应用保留期，跨租户/到期运行
 * 统一表现为空结果或 404（ADR-0024 §3.3.3/§6.2）。
 */
@Repository
public class WorkflowHistoryRepository {

    /** 运行历史 SQL。 */
    private final WorkflowHistoryJdbc historyJdbc;

    /**
     * 创建运行历史仓储。
     *
     * @param historyJdbc 运行历史 SQL
     */
    public WorkflowHistoryRepository(WorkflowHistoryJdbc historyJdbc) {
        this.historyJdbc = Objects.requireNonNull(historyJdbc, "historyJdbc must not be null");
    }

    /**
     * 统计可见运行数。
     *
     * @param tenantId 当前租户
     * @param actor 读取主体
     * @param filter 过滤条件
     * @param now 当前时间
     * @return 可见运行数
     */
    public long countRuns(TenantId tenantId, WorkflowHistoryActor actor, WorkflowHistoryFilter filter, Instant now) {
        return historyJdbc.countRuns(tenantId, actor, filter, now);
    }

    /**
     * 分页读取可见运行。
     *
     * @param tenantId 当前租户
     * @param actor 读取主体
     * @param filter 过滤条件
     * @param now 当前时间
     * @param limit 每页大小
     * @param offset 偏移
     * @return 运行摘要列表
     */
    public List<WorkflowRunHistorySnapshot> findRuns(
            TenantId tenantId,
            WorkflowHistoryActor actor,
            WorkflowHistoryFilter filter,
            Instant now,
            int limit,
            int offset) {
        return historyJdbc.findRuns(tenantId, actor, filter, now, limit, offset);
    }

    /**
     * 按 ID 读取未到期运行详情。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param now 当前时间
     * @return 运行详情行；不存在或已到期为空
     */
    public Optional<WorkflowRunHistorySnapshot> findRunDetail(TenantId tenantId, UUID runId, Instant now) {
        return historyJdbc.findRunDetail(tenantId, runId, now);
    }

    /**
     * 读取运行下全部节点尝试。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @return 节点尝试列表
     */
    public List<WorkflowRunNodeSnapshot> findNodeRuns(TenantId tenantId, UUID runId) {
        return historyJdbc.findNodeRuns(tenantId, runId);
    }

    /**
     * 读取运行下审批记录。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @return 审批记录列表
     */
    public List<WorkflowRunApprovalSnapshot> findApprovals(TenantId tenantId, UUID runId) {
        return historyJdbc.findApprovals(tenantId, runId);
    }

    /**
     * 批量读取审批状态计数。
     *
     * @param tenantId 当前租户
     * @param runIds 运行 ID 集合
     * @return 运行 ID 到计数的映射
     */
    public Map<UUID, WorkflowApprovalCounts> findApprovalCounts(TenantId tenantId, Collection<UUID> runIds) {
        return historyJdbc.findApprovalCounts(tenantId, runIds);
    }

    /**
     * 判断当前账号是哪些审批的 assignee。
     *
     * @param tenantId 当前租户
     * @param userId 当前账号
     * @param approvalIds 审批 ID 集合
     * @return 被指派的审批 ID
     */
    public Set<UUID> findAssignedApprovalIds(TenantId tenantId, UUID userId, Collection<UUID> approvalIds) {
        return historyJdbc.findAssignedApprovalIds(tenantId, userId, approvalIds);
    }
}
