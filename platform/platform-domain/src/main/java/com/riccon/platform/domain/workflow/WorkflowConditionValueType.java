package com.riccon.platform.domain.workflow;

/**
 * 条件比较值的类型。
 *
 * <p>左右值类型必须一致；不做隐式类型转换，避免字符串/数字比较产生意外分支
 * （ADR-0024 第 4.5 节）。
 */
public enum WorkflowConditionValueType {
    /** 数字。 */
    NUMBER,
    /** 字符串。 */
    STRING,
    /** 布尔。 */
    BOOLEAN
}
