package com.riccon.api.workflow.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.riccon.platform.domain.workflow.WorkflowConditionOperator;
import java.util.Objects;

/**
 * 条件节点配置。
 *
 * <p>对应契约 {@code WorkflowCondition}：左值、封闭算子与右值。M06 不支持表达式、脚本或正则；
 * 缺值、类型不符或算子与右值形态不匹配时，运行在条件节点失败并返回 {@code WF_CONDITION_INVALID}，
 * 不默认走任一分支（ADR-0024 第 4.5 节，AC-09）。
 *
 * @param left 左值
 * @param operator 封闭算子
 * @param right 右值
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowGraphCondition(
        WorkflowGraphConditionSource left, WorkflowConditionOperator operator, WorkflowGraphConditionRight right) {

    /** 校验三个组成部分都存在。 */
    public WorkflowGraphCondition {
        Objects.requireNonNull(left, "left must not be null");
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(right, "right must not be null");
    }
}
