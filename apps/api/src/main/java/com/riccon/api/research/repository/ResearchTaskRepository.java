package com.riccon.api.research.repository;

import com.riccon.api.research.domain.ResearchTaskSnapshot;
import com.riccon.api.research.persistence.ResearchTaskJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.research.ResearchTaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 报告任务仓储。
 *
 * <p>只做持久化编排；状态投影、幂等与跨租户语义由 {@code ResearchTaskService} 决定。
 */
@Repository
public class ResearchTaskRepository {

    /** 报告任务 SQL。 */
    private final ResearchTaskJdbc researchTaskJdbc;

    /**
     * 创建仓储。
     *
     * @param researchTaskJdbc 报告任务 SQL
     */
    public ResearchTaskRepository(ResearchTaskJdbc researchTaskJdbc) {
        this.researchTaskJdbc = Objects.requireNonNull(researchTaskJdbc, "researchTaskJdbc must not be null");
    }

    /**
     * 把工作空间内任务状态与当前运行同步。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @return 受影响行数
     */
    public int syncWorkspace(TenantId tenantId, UUID workspaceId) {
        return researchTaskJdbc.syncWorkspace(tenantId, workspaceId);
    }

    /**
     * 把单个任务状态与当前运行同步。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param taskId 任务 ID
     * @return 受影响行数
     */
    public int syncTask(TenantId tenantId, UUID workspaceId, UUID taskId) {
        return researchTaskJdbc.syncTask(tenantId, workspaceId, taskId);
    }

    /**
     * 插入任务。
     *
     * @param tenantId 当前租户
     * @param task 任务快照
     * @param idempotencyKey 创建幂等键
     */
    public void insert(TenantId tenantId, ResearchTaskSnapshot task, String idempotencyKey) {
        researchTaskJdbc.insert(tenantId, task, idempotencyKey);
    }

    /**
     * 按主键读取任务。
     *
     * @param tenantId 当前租户
     * @param taskId 任务 ID
     * @return 任务快照
     */
    public Optional<ResearchTaskSnapshot> findById(TenantId tenantId, UUID taskId) {
        return researchTaskJdbc.findById(tenantId, taskId);
    }

    /**
     * 按幂等键读取任务并加锁。
     *
     * @param tenantId 当前租户
     * @param createdBy 创建人
     * @param idempotencyKey 幂等键
     * @return 既有任务
     */
    public Optional<ResearchTaskSnapshot> findByIdempotencyKeyForUpdate(
            TenantId tenantId, UUID createdBy, String idempotencyKey) {
        return researchTaskJdbc.findByIdempotencyKeyForUpdate(tenantId, createdBy, idempotencyKey);
    }

    /**
     * 统计工作空间任务数。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param status 可选状态过滤
     * @return 任务数
     */
    public long count(TenantId tenantId, UUID workspaceId, ResearchTaskStatus status) {
        return researchTaskJdbc.count(tenantId, workspaceId, status);
    }

    /**
     * 分页列出工作空间任务。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param status 可选状态过滤
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 任务列表
     */
    public List<ResearchTaskSnapshot> list(
            TenantId tenantId, UUID workspaceId, ResearchTaskStatus status, int limit, int offset) {
        return researchTaskJdbc.list(tenantId, workspaceId, status, limit, offset);
    }

    /**
     * 审批通过后推进任务为 RUNNING。
     *
     * @param tenantId 当前租户
     * @param taskId 任务 ID
     * @param runId 运行 ID
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markRunning(TenantId tenantId, UUID taskId, UUID runId, Instant now) {
        return researchTaskJdbc.markRunning(tenantId, taskId, runId, now);
    }

    /**
     * 审批拒绝后终止任务。
     *
     * @param tenantId 当前租户
     * @param taskId 任务 ID
     * @param runId 运行 ID
     * @param failureCode 稳定失败码
     * @param failureMessage 脱敏失败原因
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markTerminated(
            TenantId tenantId, UUID taskId, UUID runId, String failureCode, String failureMessage, Instant now) {
        return researchTaskJdbc.markTerminated(tenantId, taskId, runId, failureCode, failureMessage, now);
    }

    /**
     * 判断 MCP 工具是否已授权且可用于报告取数。
     *
     * @param tenantId 当前租户
     * @param toolId 工具 ID
     * @return 可用时返回 {@code true}
     */
    public boolean isToolAuthorized(TenantId tenantId, UUID toolId) {
        return researchTaskJdbc.isToolAuthorized(tenantId, toolId);
    }
}
