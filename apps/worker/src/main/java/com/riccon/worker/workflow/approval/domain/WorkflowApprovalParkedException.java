package com.riccon.worker.workflow.approval.domain;

import com.riccon.worker.workflow.node.domain.WorkflowNodeAbortedException;

/**
 * 审批节点已进入等待态的信号。
 *
 * <p>审批执行器已经在同一事务内把节点推进为 {@code WAITING_APPROVAL}、运行推进为
 * {@code WAITING_APPROVAL} 并释放租约，因此不能返回普通成功结果让引擎继续推进下游，也不能抛
 * {@code WF_INTERNAL} 让引擎写失败终态。本异常继承 {@link WorkflowNodeAbortedException} 的
 * “丢弃本段结果”出口（{@code lost=true}），T06b 引擎捕获后直接返回、不再写任何运行/节点终态，
 * 与 ADR-0024 §4.2/§5.4 的“审批等待时释放租约、由决定恢复运行”一致。
 *
 * <p>异常消息不包含审批人、工具参数或提示词；它只用于日志定位，不进入响应。
 */
public class WorkflowApprovalParkedException extends WorkflowNodeAbortedException {

    /** 审批 ID；仅用于服务内部断言与测试。 */
    private final String approvalId;

    /**
     * 创建审批等待信号。
     *
     * @param approvalId 审批 ID
     */
    public WorkflowApprovalParkedException(String approvalId) {
        super(true);
        this.approvalId = approvalId;
    }

    /**
     * 返回审批 ID。
     *
     * @return 审批 ID
     */
    public String approvalId() {
        return approvalId;
    }
}
