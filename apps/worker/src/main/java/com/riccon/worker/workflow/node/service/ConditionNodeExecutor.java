package com.riccon.worker.workflow.node.service;

import com.riccon.platform.domain.workflow.WorkflowEdgeBranch;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecution;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecutor;
import com.riccon.worker.workflow.node.domain.WorkflowNodeResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * CONDITION 节点执行器（ADR-0024 §4.5，AC-09）。
 *
 * <p>只输出 {@code TRUE}/{@code FALSE} 分支名；未走分支的下游由引擎标记 {@code SKIPPED}。
 * 求值失败抛 {@code WF_CONDITION_INVALID}，不默认走任一分支。
 */
@Component
public class ConditionNodeExecutor implements WorkflowNodeExecutor {

    /** 封闭条件求值。 */
    private final WorkflowConditionEvaluator evaluator;

    /**
     * 创建条件执行器。
     *
     * @param evaluator 条件求值
     */
    public ConditionNodeExecutor(WorkflowConditionEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    /** {@inheritDoc} */
    @Override
    public WorkflowNodeType nodeType() {
        return WorkflowNodeType.CONDITION;
    }

    /** {@inheritDoc} */
    @Override
    public WorkflowNodeResult execute(WorkflowNodeExecution execution) {
        execution.guard().requireActive();
        execution.deadline().requireRemaining();
        WorkflowEdgeBranch branch = evaluator.evaluate(execution);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("branch", branch.name());
        output.put("result", branch == WorkflowEdgeBranch.TRUE);
        String summary = branch == WorkflowEdgeBranch.TRUE ? "条件成立，执行 TRUE 分支" : "条件不成立，执行 FALSE 分支";
        return WorkflowNodeResult.of("条件求值", summary, output, List.of());
    }
}
