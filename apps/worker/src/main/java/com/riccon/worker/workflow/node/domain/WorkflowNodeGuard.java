package com.riccon.worker.workflow.node.domain;

/**
 * 节点执行期间的取消/租约检查端口。
 *
 * <p>引擎把 {@code WorkflowLease} 适配成本接口传入节点；检索在查询前后、模型在流式等待期间
 * 检查，避免取消后继续占用执行槽位或写入过期结果（ADR-0024 §4.6，AC-23）。
 */
public interface WorkflowNodeGuard {

    /**
     * 租约是否已丢失。
     *
     * @return true 表示结果必须丢弃
     */
    boolean lost();

    /**
     * 运行是否已取消。
     *
     * @return true 表示应尽快停止
     */
    boolean cancelled();

    /**
     * 断言仍可执行。
     *
     * @throws WorkflowNodeAbortedException 已取消或租约丢失
     */
    default void requireActive() {
        if (lost() || cancelled()) {
            throw new WorkflowNodeAbortedException(lost());
        }
    }
}
