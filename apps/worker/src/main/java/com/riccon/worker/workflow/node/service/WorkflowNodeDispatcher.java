package com.riccon.worker.workflow.node.service;

import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecution;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecutor;
import com.riccon.worker.workflow.node.domain.WorkflowNodeFailureException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeResult;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 节点执行器分派（按节点类型）。
 *
 * <p>启动时收集全部 {@link WorkflowNodeExecutor} Bean，一个类型只允许一个实现；缺失类型在运行时
 * 失败 {@code WF_INTERNAL}，不静默跳过。T06b 注册 START/END/RETRIEVAL/MODEL/CONDITION；
 * APPROVAL/MCP_TOOL 由 T07a/T07b 追加（ADR-0024 §4.4）。
 */
@Component
public class WorkflowNodeDispatcher {

    /** 类型到执行器。 */
    private final Map<WorkflowNodeType, WorkflowNodeExecutor> executors;

    /**
     * 创建分派器。
     *
     * @param executorProvider 执行器提供者；避免依赖注入顺序造成的启动期循环
     */
    @Autowired
    public WorkflowNodeDispatcher(ObjectProvider<WorkflowNodeExecutor> executorProvider) {
        this(executorProvider.orderedStream().toList());
    }

    /**
     * 显式构造分派器（测试与手动装配）。
     *
     * @param executors 执行器列表
     * @throws IllegalStateException 同一节点类型注册多个执行器
     */
    public WorkflowNodeDispatcher(List<WorkflowNodeExecutor> executors) {
        EnumMap<WorkflowNodeType, WorkflowNodeExecutor> map = new EnumMap<>(WorkflowNodeType.class);
        for (WorkflowNodeExecutor executor : executors) {
            WorkflowNodeExecutor previous = map.put(executor.nodeType(), executor);
            if (previous != null) {
                throw new IllegalStateException(
                        "duplicate workflow node executor for " + executor.nodeType());
            }
        }
        this.executors = Map.copyOf(map);
    }

    /**
     * 执行节点。
     *
     * @param execution 执行上下文
     * @return 成功结果
     * @throws WorkflowNodeFailureException 节点类型没有执行器或执行器可控失败
     */
    public WorkflowNodeResult execute(WorkflowNodeExecution execution) {
        WorkflowNodeExecutor executor = executors.get(execution.node().type());
        if (executor == null) {
            // T07a/T07b 落地前，含审批/工具节点的运行会明确失败，而不是卡在 RUNNING。
            throw new WorkflowNodeFailureException(
                    WorkflowErrorCode.WF_INTERNAL, "当前版本不支持执行该节点类型：" + execution.node().type());
        }
        return executor.execute(execution);
    }
}
