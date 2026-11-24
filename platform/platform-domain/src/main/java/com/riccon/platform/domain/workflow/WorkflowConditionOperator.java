package com.riccon.platform.domain.workflow;

/**
 * 条件节点使用的封闭算子。
 *
 * <p>不支持任意表达式、脚本或正则；只允许这些算子与受控数据源（运行输入/检索命中数/模型分类标签/
 * 审批决定/工具状态）。缺值、类型不符或算子不匹配一律 {@code WF_CONDITION_INVALID}，不默认走任一分支
 * （ADR-0024 第 4.5 节，AC-09）。
 */
public enum WorkflowConditionOperator {
    /** 等于。 */
    EQ,
    /** 不等于。 */
    NE,
    /** 大于（仅 NUMBER）。 */
    GT,
    /** 大于等于（仅 NUMBER）。 */
    GTE,
    /** 小于（仅 NUMBER）。 */
    LT,
    /** 小于等于（仅 NUMBER）。 */
    LTE,
    /** 命中集合（右侧为 values 数组）。 */
    IN
}
