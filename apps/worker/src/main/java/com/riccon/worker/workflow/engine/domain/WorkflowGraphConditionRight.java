package com.riccon.worker.workflow.engine.domain;

import com.riccon.platform.domain.workflow.WorkflowConditionValueType;
import java.util.List;
import java.util.Objects;

/**
 * 条件节点右值（Worker 只读副本）。
 *
 * <p>{@code operator=IN} 使用 {@code values}，其余算子使用 {@code value}；左右类型必须一致，
 * 不做隐式转换（ADR-0024 §4.5）。
 *
 * @param valueType 右值类型
 * @param value 单值
 * @param values 集合值；仅 IN 使用
 */
public record WorkflowGraphConditionRight(WorkflowConditionValueType valueType, Object value, List<Object> values) {

    /** 校验类型并复制集合。 */
    public WorkflowGraphConditionRight {
        Objects.requireNonNull(valueType, "condition right value type must not be null");
        values = values == null ? null : List.copyOf(values);
    }
}
