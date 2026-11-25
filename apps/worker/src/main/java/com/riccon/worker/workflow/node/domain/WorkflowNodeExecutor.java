package com.riccon.worker.workflow.node.domain;

import com.riccon.platform.domain.workflow.WorkflowNodeType;

/**
 * 单节点执行器端口（按节点类型分派）。
 *
 * <p>T06b 实现 START/END/RETRIEVAL/MODEL/CONDITION；APPROVAL 与 MCP_TOOL 分别由 T07a/T07b
 * 注册同名端口 Bean，引擎不感知具体实现。执行器禁止解释任意脚本、表达式或 HTTP
 * （ADR-0024 §4.4/§9）。
 */
public interface WorkflowNodeExecutor {

    /**
     * 支持的节点类型；一个执行器只注册一种。
     *
     * @return 节点类型
     */
    WorkflowNodeType nodeType();

    /**
     * 执行节点。
     *
     * @param execution 执行上下文
     * @return 成功结果
     * @throws WorkflowNodeFailureException 可预期失败
     * @throws WorkflowNodeTimeoutException 超时
     * @throws WorkflowNodeAbortedException 取消或租约丢失
     */
    WorkflowNodeResult execute(WorkflowNodeExecution execution);
}
