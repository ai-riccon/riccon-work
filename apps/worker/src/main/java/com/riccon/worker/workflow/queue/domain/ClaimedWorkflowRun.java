package com.riccon.worker.workflow.queue.domain;

import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 已领取的运行队列条目（跨租户 claim 元数据）。
 *
 * <p>只承载 claim SELECT 的 4 列（{@code id/tenant_id/status/lease_until}）与本地租约标识；
 * 不包含输入、结果、图或流程名等业务内容（TM-85）。租户在领取后由调用方
 * 通过 {@code TenantId.of} 校验并立即打开 {@code TenantContext}，非法租户 fail-closed，
 * 不允许通过该路径读取业务列。
 *
 * @param runId 运行 ID
 * @param tenantId 领取行上的原始租户字符串；尚未校验
 * @param previousStatus 领取前状态（PENDING 或租约过期的 RUNNING）
 * @param leaseUntil 本次租约到期的近似 UTC 时间
 * @param leaseOwner 本次租约持有者（实例 + 槽位）
 */
public record ClaimedWorkflowRun(
        UUID runId, String tenantId, WorkflowRunStatus previousStatus, Instant leaseUntil, String leaseOwner) {

    /** 校验必填字段，避免半残条目进入执行路径。 */
    public ClaimedWorkflowRun {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        Objects.requireNonNull(leaseOwner, "leaseOwner must not be null");
    }
}
