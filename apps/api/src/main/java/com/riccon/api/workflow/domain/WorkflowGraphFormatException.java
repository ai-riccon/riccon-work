package com.riccon.api.workflow.domain;

/**
 * 图 JSON 形状非法。
 *
 * <p>只表达“无法表示为契约图结构”的错误（未知字段/类型、枚举值非法、超出节点/边上限），由服务层映射为
 * 400 {@code RICCON-WF-400}。语义结构问题（缺 START、成环、孤立节点等）由校验器以逐项 issue 返回，
 * 不使用本异常。
 */
public class WorkflowGraphFormatException extends IllegalArgumentException {

    /**
     * 创建图格式异常。
     *
     * @param message 脱敏说明；不得包含提示词、工具参数或文档正文
     */
    public WorkflowGraphFormatException(String message) {
        super(message);
    }
}
