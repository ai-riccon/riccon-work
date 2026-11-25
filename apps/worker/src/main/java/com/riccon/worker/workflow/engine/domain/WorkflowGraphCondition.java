package com.riccon.worker.workflow.engine.domain;

import com.riccon.platform.domain.workflow.WorkflowConditionOperator;
import java.util.Objects;

/**
 * 条件节点配置（Worker 只读副本）。
 *
 * <p>只允许封闭算子与受控数据源；缺值、类型不符或算子与右值形态不匹配时，运行在条件节点失败
 * {@code WF_CONDITION_INVALID}，不默认走任一分支（ADR-0024 §4.5，AC-09）。
 *
 * @param left 左值
 * @param operator 封闭算子
 * @param right 右值
 */
public record WorkflowGraphCondition(
        WorkflowGraphConditionSource left, WorkflowConditionOperator operator, WorkflowGraphConditionRight right) {

    /** 校验三个组成部分都存在。 */
    public WorkflowGraphCondition {
        Objects.requireNonNull(left, "condition left must not be null");
        Objects.requireNonNull(operator, "condition operator must not be null");
        Objects.requireNonNull(right, "condition right must not be null");
    }
}
