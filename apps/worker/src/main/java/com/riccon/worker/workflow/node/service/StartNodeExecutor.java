package com.riccon.worker.workflow.node.service;

import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.worker.workflow.engine.domain.WorkflowEngineLimits;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecution;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecutor;
import com.riccon.worker.workflow.node.domain.WorkflowNodeResult;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * START 节点执行器（ADR-0024 §4.4）。
 *
 * <p>只做运行输入必填校验与时间线记账：扫描所有检索/模型模板中的 {@code {{input.<field>}}}
 * 占位符，缺失字段在运行开始时失败，不进入后续节点。不校验条件值类型——条件类型错误必须在
 * 条件节点失败 {@code WF_CONDITION_INVALID}（AC-09）。
 */
@Component
public class StartNodeExecutor implements WorkflowNodeExecutor {

    /** 模板渲染与输入校验。 */
    private final WorkflowTemplateRenderer renderer;

    /**
     * 创建 START 执行器。
     *
     * @param renderer 模板渲染器
     */
    public StartNodeExecutor(WorkflowTemplateRenderer renderer) {
        this.renderer = renderer;
    }

    /** {@inheritDoc} */
    @Override
    public WorkflowNodeType nodeType() {
        return WorkflowNodeType.START;
    }

    /** {@inheritDoc} */
    @Override
    public WorkflowNodeResult execute(WorkflowNodeExecution execution) {
        execution.guard().requireActive();
        execution.deadline().requireRemaining();
        renderer.requireInputs(execution.graph(), execution.inputs());
        String fields = inputFieldNames(execution.inputs());
        return WorkflowNodeResult.of(
                truncate("运行输入：" + fields),
                "运行开始",
                Map.of(),
                List.of());
    }

    /**
     * 列出运行输入字段名（不含值），避免把用户内容写入摘要。
     *
     * @param inputs 运行输入
     * @return 逗号分隔字段名
     */
    private static String inputFieldNames(JsonNode inputs) {
        if (inputs == null || !inputs.isObject() || inputs.propertyNames().isEmpty()) {
            return "无";
        }
        return String.join(", ", inputs.propertyNames());
    }

    /**
     * 截断摘要到列宽。
     *
     * @param value 文本
     * @return 截断后的文本
     */
    private static String truncate(String value) {
        return value.length() <= WorkflowEngineLimits.SUMMARY_MAX_LENGTH
                ? value
                : value.substring(0, WorkflowEngineLimits.SUMMARY_MAX_LENGTH);
    }
}
