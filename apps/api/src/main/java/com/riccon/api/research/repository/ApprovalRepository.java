package com.riccon.api.research.repository;

import com.riccon.api.research.domain.ApprovalSnapshot;
import com.riccon.api.research.persistence.ApprovalJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.research.ApprovalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 审批仓储。
 *
 * <p>只暴露公开字段；密文与密钥 ID 永远不离开 Worker 持久化层。
 */
@Repository
public class ApprovalRepository {

    /** 审批 SQL。 */
    private final ApprovalJdbc approvalJdbc;

    /**
     * 创建仓储。
     *
     * @param approvalJdbc 审批 SQL
     */
    public ApprovalRepository(ApprovalJdbc approvalJdbc) {
        this.approvalJdbc = Objects.requireNonNull(approvalJdbc, "approvalJdbc must not be null");
    }

    /**
     * 按 ID 读取审批并加锁。
     *
     * @param tenantId 当前租户
     * @param approvalId 审批 ID
     * @return 审批快照
     */
    public Optional<ApprovalSnapshot> findForUpdate(TenantId tenantId, UUID approvalId) {
        return approvalJdbc.findForUpdate(tenantId, approvalId);
    }

    /**
     * 查询指定任务的审批记录。
     *
     * @param tenantId 当前租户
     * @param taskId 报告任务 ID
     * @return 审批列表
     */
    public List<ApprovalSnapshot> listByTask(TenantId tenantId, UUID taskId) {
        return approvalJdbc.listByTask(tenantId, taskId);
    }

    /**
     * 统计审批数。
     *
     * @param tenantId 当前租户
     * @param status 可选状态过滤
     * @return 审批数
     */
    public long count(TenantId tenantId, ApprovalStatus status) {
        return approvalJdbc.count(tenantId, status);
    }

    /**
     * 分页列出审批。
     *
     * @param tenantId 当前租户
     * @param status 可选状态过滤
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 审批列表
     */
    public List<ApprovalSnapshot> list(TenantId tenantId, ApprovalStatus status, int limit, int offset) {
        return approvalJdbc.list(tenantId, status, limit, offset);
    }

    /**
     * 条件更新审批状态。
     *
     * @param tenantId 当前租户
     * @param approvalId 审批 ID
     * @param status 目标状态
     * @param decidedBy 审批人
     * @param decidedAt 审批时间
     * @param comment 审批备注
     * @return 受影响行数
     */
    public int markDecided(
            TenantId tenantId,
            UUID approvalId,
            ApprovalStatus status,
            UUID decidedBy,
            Instant decidedAt,
            String comment) {
        return approvalJdbc.markDecided(tenantId, approvalId, status, decidedBy, decidedAt, comment);
    }
}
