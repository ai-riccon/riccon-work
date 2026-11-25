package com.riccon.worker.workflow.tool.domain;

/**
 * 工作流工具调用状态（{@code workflow_tool_invocation.status}）。
 *
 * <p>与 V15 CHECK 约束一致：{@link #STARTED} 表示已写入调用意图、外部副作用可能已经发生；
 * 只有 {@link #SUCCEEDED}/{@link #FAILED}/{@link #DENIED} 是终态。同一审批最多执行一次：
 * 出现 STARTED 无终态时按 {@code WF_SIDE_EFFECT_UNKNOWN} fail-closed，不自动重放。
 */
public enum WorkflowToolInvocationStatus {
    /** 已写入调用记录，尚未得到终态。 */
    STARTED,
    /** 调用成功。 */
    SUCCEEDED,
    /** 调用失败（协议、端点、大小或未知副作用）。 */
    FAILED,
    /** 调用前置校验拒绝，未发起外部调用。 */
    DENIED;

    /**
     * 是否为终态。
     *
     * @return true 表示不再是 STARTED
     */
    public boolean terminal() {
        return this != STARTED;
    }
}
