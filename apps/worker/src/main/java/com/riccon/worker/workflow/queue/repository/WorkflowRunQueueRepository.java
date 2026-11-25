package com.riccon.worker.workflow.queue.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.workflow.queue.domain.ClaimedWorkflowRun;
import com.riccon.worker.workflow.queue.domain.WorkflowRunClaimState;
import com.riccon.worker.workflow.queue.domain.WorkflowRunQueueStats;
import com.riccon.worker.workflow.queue.persistence.WorkflowRunClaimer;
import com.riccon.worker.workflow.queue.persistence.WorkflowRunQueueJdbc;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 运行队列仓储。
 *
 * <p>对上只暴露领取、领取后状态、WF_INTERNAL 终态与队列指标，屏蔽跨租户 claim SQL 与
 * TenantJdbc 的差异；服务层不直接依赖持久化包（MVC 分层单向）。
 */
@Component
public class WorkflowRunQueueRepository {

    /** 受限跨租户领取器。 */
    private final WorkflowRunClaimer claimer;

    /** 租约与终态持久化。 */
    private final WorkflowRunQueueJdbc queueJdbc;

    /**
     * 创建仓储。
     *
     * @param claimer 领取器
     * @param queueJdbc 队列持久化
     */
    public WorkflowRunQueueRepository(WorkflowRunClaimer claimer, WorkflowRunQueueJdbc queueJdbc) {
        this.claimer = Objects.requireNonNull(claimer, "claimer must not be null");
        this.queueJdbc = Objects.requireNonNull(queueJdbc, "queueJdbc must not be null");
    }

    /**
     * 领取一个可执行运行。
     *
     * @param leaseOwner 租约持有者
     * @param leaseDuration 租约时长
     * @return 已领取运行；空队列为空
     */
    public Optional<ClaimedWorkflowRun> claim(String leaseOwner, Duration leaseDuration) {
        return claimer.claim(leaseOwner, leaseDuration);
    }

    /**
     * 读取领取后的运行状态（仅元数据）。
     *
     * @param tenantId 已校验租户
     * @param runId 运行 ID
     * @return 状态；行不存在时为空
     */
    public Optional<WorkflowRunClaimState> findClaimState(TenantId tenantId, UUID runId) {
        return queueJdbc.findClaimState(tenantId, runId);
    }

    /**
     * 推进到 WF_INTERNAL 失败终态。
     *
     * @param tenantId 已校验租户
     * @param runId 运行 ID
     * @param leaseOwner 当前租约持有者
     * @param currentNodeKey 当前节点 key
     * @param now 当前 UTC 时间
     * @return true 表示终态已写入
     */
    public boolean markClaimExhausted(
            TenantId tenantId, UUID runId, String leaseOwner, String currentNodeKey, Instant now) {
        return queueJdbc.markClaimExhausted(tenantId, runId, leaseOwner, currentNodeKey, now);
    }

    /**
     * 队列聚合指标。
     *
     * @return 队列快照
     */
    public WorkflowRunQueueStats queueStats() {
        return queueJdbc.queueStats();
    }
}
