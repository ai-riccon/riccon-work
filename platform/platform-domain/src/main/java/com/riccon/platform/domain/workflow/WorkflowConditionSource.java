package com.riccon.platform.domain.workflow;

/**
 * 条件左值的数据来源。
 *
 * <p>{@link #RUN_INPUT} 读取运行输入的标量字段；{@link #NODE_OUTPUT} 只允许上游已执行节点的受控字段
 * （{@code hitCount}/{@code classificationLabel}/{@code decision}/{@code status}）。服务端封闭解析，
 * 禁止表达式（ADR-0024 第 4.5 节）。
 */
public enum WorkflowConditionSource {
    /** 运行输入字段。 */
    RUN_INPUT,
    /** 上游节点输出字段。 */
    NODE_OUTPUT
}
