package com.riccon.worker.workflow.node.domain;

/**
 * 节点执行超过超时预算。
 *
 * <p>引擎捕获后按“是否已超过运行截止时间”区分 {@code WF_RUN_TIMEOUT}（当前节点 CANCELLED）与
 * {@code WF_NODE_TIMEOUT}（节点 FAILED），不做截断或静默继续（ADR-0024 §4.4/§4.6）。
 */
public class WorkflowNodeTimeoutException extends RuntimeException {

    /**
     * 创建超时异常。
     *
     * @param message 脱敏可读原因
     */
    public WorkflowNodeTimeoutException(String message) {
        super(message);
    }

    /**
     * 创建带原因的超时异常。
     *
     * @param message 脱敏可读原因
     * @param cause 原始异常
     */
    public WorkflowNodeTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
