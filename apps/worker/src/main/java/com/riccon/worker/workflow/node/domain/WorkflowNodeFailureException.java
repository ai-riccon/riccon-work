package com.riccon.worker.workflow.node.domain;

import com.riccon.platform.domain.workflow.WorkflowErrorCode;

/**
 * 节点可控失败（稳定错误码 + 脱敏可读原因）。
 *
 * <p>执行器只在“可预期失败”时抛出本异常：检索失败、模型不可用、分类输出非法、条件无法求值、
 * 配置缺失等。引擎把错误码写入节点/运行并跳过下游；异常消息不得包含提示词、工具参数、文档正文或
 * 凭据（AC-13/TM-78）。
 */
public class WorkflowNodeFailureException extends RuntimeException {

    /** 稳定错误码。 */
    private final WorkflowErrorCode errorCode;

    /**
     * 创建节点失败。
     *
     * @param errorCode 稳定错误码
     * @param message 脱敏可读原因
     */
    public WorkflowNodeFailureException(WorkflowErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 创建带原因的节点失败。
     *
     * @param errorCode 稳定错误码
     * @param message 脱敏可读原因
     * @param cause 原始异常
     */
    public WorkflowNodeFailureException(WorkflowErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 返回稳定错误码。
     *
     * @return 错误码
     */
    public WorkflowErrorCode errorCode() {
        return errorCode;
    }
}
