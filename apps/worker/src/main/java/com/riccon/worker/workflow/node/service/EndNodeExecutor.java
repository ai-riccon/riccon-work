package com.riccon.worker.workflow.node.service;

import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.worker.workflow.engine.domain.WorkflowEngineLimits;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecution;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecutor;
import com.riccon.worker.workflow.node.domain.WorkflowNodeResult;
import com.riccon.worker.workflow.node.domain.WorkflowResultPayload;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * END 节点执行器（ADR-0024 §4.4）。
 *
 * <p>按 {@code resultFrom} 组装最终结果并携带引擎聚合去重后的引用；节点自身的 {@code output_json}
 * 只保留来源、格式与大小，避免把最大 5 MiB 的结果再复制成节点输出（单节点输出上限 256 KiB）。
 * 5 MiB 上限由引擎检查并写 {@code result_status=OVER_LIMIT}，执行器不做截断。
 */
@Component
public class EndNodeExecutor implements WorkflowNodeExecutor {

    /** 结果组装。 */
    private final WorkflowResultAssembler assembler;

    /**
     * 创建 END 执行器。
     *
     * @param assembler 结果组装
     */
    public EndNodeExecutor(WorkflowResultAssembler assembler) {
        this.assembler = assembler;
    }

    /** {@inheritDoc} */
    @Override
    public WorkflowNodeType nodeType() {
        return WorkflowNodeType.END;
    }

    /** {@inheritDoc} */
    @Override
    public WorkflowNodeResult execute(WorkflowNodeExecution execution) {
        execution.guard().requireActive();
        execution.deadline().requireRemaining();
        String resultFrom = execution.node().config().resultFrom();
        WorkflowResultPayload payload = assembler.assemble(execution.graph(), execution.node(), execution.upstreamOutputs());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("resultFrom", resultFrom);
        output.put("format", payload.format().name());
        output.put("sizeBytes", payload.sizeBytes());
        String summary = "已生成结果（" + payload.sizeBytes() + " 字节）";
        return new WorkflowNodeResult(
                truncate("结果来源：" + resultFrom), truncate(summary), output, execution.citations(), payload);
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
