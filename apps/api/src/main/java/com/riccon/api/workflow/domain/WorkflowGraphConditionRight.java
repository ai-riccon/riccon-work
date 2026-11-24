package com.riccon.api.workflow.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.riccon.platform.domain.workflow.WorkflowConditionValueType;
import java.util.List;
import java.util.Objects;

/**
 * 条件节点右值。
 *
 * <p>对应契约 {@code WorkflowConditionRight}：{@code operator=IN} 时使用 {@code values}，其余算子使用
 * {@code value}。右值只允许标量或标量数组，不接受表达式、对象或正则；类型必须与左值一致，服务端在运行前
 * 复查（ADR-0024 第 4.5 节）。
 *
 * @param valueType 右值类型
 * @param value 单值；使用 {@code values} 时为空
 * @param values 集合值；仅 {@code IN} 使用，最多 20 个
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowGraphConditionRight(WorkflowConditionValueType valueType, Object value, List<Object> values) {

    /** 集合值最大元素数，与契约 {@code maxItems: 20} 一致。 */
    public static final int MAX_VALUES = 20;

    /** 校验类型并复制集合，避免调用方修改内部状态。 */
    public WorkflowGraphConditionRight {
        Objects.requireNonNull(valueType, "valueType must not be null");
        if (values != null) {
            if (values.size() > MAX_VALUES) {
                throw new IllegalArgumentException("values must not exceed " + MAX_VALUES + " items");
            }
            values = List.copyOf(values);
        }
    }
}
