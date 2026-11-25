package com.riccon.worker.workflow.engine.domain;

/**
 * 发布图快照非法（结构/字段/类型/上限）。
 *
 * <p>运行入口把本异常映射为 {@code WF_INVALID_GRAPH} 失败终态，不做任何节点执行；
 * 消息只描述结构问题，不回显提示词、工具参数或文档内容（TM-72/AC-24）。
 */
public class WorkflowGraphFormatException extends RuntimeException {

    /**
     * 创建图格式异常。
     *
     * @param message 可读原因；不得包含用户内容原文
     */
    public WorkflowGraphFormatException(String message) {
        super(message);
    }

    /**
     * 创建带原因的图格式异常。
     *
     * @param message 可读原因
     * @param cause 原始异常
     */
    public WorkflowGraphFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
