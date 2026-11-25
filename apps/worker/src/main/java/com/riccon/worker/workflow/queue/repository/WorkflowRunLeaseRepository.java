package com.riccon.worker.workflow.queue.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import com.riccon.worker.workflow.queue.persistence.WorkflowRunQueueJdbc;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 运行租约仓储（续期与状态读取）。
 *
 * <p>心跳只依赖本仓储；续期使用租户条件 + 状态 + {@code lease_owner} 精确匹配，返回 0 即表示
 * 租约丢失或被取消，心跳据此停止并通知执行器放弃结果（ADR-0024 §4.3）。
 */
@Component
public class WorkflowRunLeaseRepository {

    /** 租约持久化。 */
    private final WorkflowRunQueueJdbc queueJdbc;

    /**
     * 创建租约仓储。
     *
     * @param queueJdbc 队列持久化
     */
    public WorkflowRunLeaseRepository(WorkflowRunQueueJdbc queueJdbc) {
        this.queueJdbc = Objects.requireNonNull(queueJdbc, "queueJdbc must not be null");
    }

    /**
     * 续期租约。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param leaseOwner 当前租约持有者
     * @param leaseDuration 新租约时长
     * @return 受影响行数；0 表示租约已丢失
     */
    public int renew(TenantId tenantId, UUID runId, String leaseOwner, Duration leaseDuration) {
        return queueJdbc.renewLease(tenantId, runId, leaseOwner, leaseDuration);
    }

    /**
     * 读取当前运行状态。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @return 状态；行不存在时为空
     */
    public Optional<WorkflowRunStatus> findStatus(TenantId tenantId, UUID runId) {
        return queueJdbc.findStatus(tenantId, runId);
    }
}
