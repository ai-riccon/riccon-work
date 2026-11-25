package com.riccon.worker.workflow.queue.domain;

import com.riccon.worker.workflow.heartbeat.domain.WorkflowLease;

/**
 * 运行执行端口（T06b 串行引擎实现）。
 *
 * <p>队列层只负责领取、租约、心跳与终态兜底，不理解图与节点；调用时已打开租户上下文，
 * 并已启动租约心跳。实现方必须在节点边界检查 {@link WorkflowLease#lost()} 与
 * {@link WorkflowLease#cancelled()}，租约丢失时丢弃结果，避免与新租约持有者双执行
 * （ADR-0024 §4.3/§4.6）。
 *
 * <p>本接口在 T06a 只有端口与默认无实现：缺少实现 Bean 时调度器不领取任何运行，
 * 避免把 PENDING 运行领走却无人推进；T06b 落地后自动生效。
 */
@FunctionalInterface
public interface WorkflowRunExecutor {

    /**
     * 执行一次已领取运行。
     *
     * @param claim 领取元数据
     * @param lease 租约句柄；执行结束或放弃时必须由调用方关闭
     */
    void execute(ClaimedWorkflowRun claim, WorkflowLease lease);
}
