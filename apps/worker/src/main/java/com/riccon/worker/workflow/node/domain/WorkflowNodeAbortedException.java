package com.riccon.worker.workflow.node.domain;

/**
 * 节点执行被取消或租约丢失中断。
 *
 * <p>{@code lost=true} 表示租约已被其他实例重领或续期失败，引擎必须丢弃本次结果且不写任何终态；
 * {@code lost=false} 表示运行被取消，引擎保留已完成节点并停止后续节点（ADR-0024 §4.3/§4.6）。
 */
public class WorkflowNodeAbortedException extends RuntimeException {

    /** 是否因租约丢失中断。 */
    private final boolean lost;

    /**
     * 创建中断异常。
     *
     * @param lost true 表示租约丢失；false 表示运行取消
     */
    public WorkflowNodeAbortedException(boolean lost) {
        super(lost ? "workflow lease lost" : "workflow run cancelled");
        this.lost = lost;
    }

    /**
     * 是否因租约丢失中断。
     *
     * @return true 表示租约丢失
     */
    public boolean lost() {
        return lost;
    }
}
