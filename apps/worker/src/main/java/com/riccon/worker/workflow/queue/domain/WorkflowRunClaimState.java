package com.riccon.worker.workflow.queue.domain;

import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.util.Objects;

/**
 * 领取后在租户作用域内读取的运行队列状态（仅元数据）。
 *
 * <p>用于重复领取防护与 {@code claim_attempts} 终态判断：{@code status/lease_owner} 必须仍与本次
 * 领取一致，否则说明租约已被取消或过期重领，当前槽位必须放弃执行；{@code claim_attempts}
 * 超过上限时进入 {@code WF_INTERNAL} 终态。该读取发生在打开租户上下文之后，不扩大跨租户
 * 领取例外（TM-85）。
 *
 * @param status 当前运行状态
 * @param claimAttempts 租约丢失重领次数
 * @param currentNodeKey 当前节点 key；可为空
 * @param leaseOwner 当前租约持有者；可为空（审批等待/终态）
 */
public record WorkflowRunClaimState(
        WorkflowRunStatus status, int claimAttempts, String currentNodeKey, String leaseOwner) {

    /** 校验状态必填。 */
    public WorkflowRunClaimState {
        Objects.requireNonNull(status, "status must not be null");
    }
}
