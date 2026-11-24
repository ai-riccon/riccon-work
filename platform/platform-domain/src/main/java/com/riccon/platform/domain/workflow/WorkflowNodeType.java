package com.riccon.platform.domain.workflow;

/**
 * 工作流节点类型（封闭集合）。
 *
 * <p>M06 只支持开始、结束、检索、模型、MCP 工具、审批与条件 7 类节点；不提供代码执行、脚本、任意 HTTP、
 * 并行、循环、子流程或定时触发。契约、服务端校验与 UI 必须使用同一枚举，未知类型在保存/发布/运行三处拒绝
 * （ADR-0024 第 4.1/9.3 节，AC-24）。
 */
public enum WorkflowNodeType {
    /** 开始节点：校验运行输入。 */
    START,
    /** 结束节点：组装最终结果与引用。 */
    END,
    /** 知识库检索节点：复用 M05 文档 ACL。 */
    RETRIEVAL,
    /** 模型节点：复用 M04 REPORT 路由链。 */
    MODEL,
    /** MCP 工具节点：必须前置审批绑定。 */
    MCP_TOOL,
    /** 审批节点：解析审批人并暂停运行。 */
    APPROVAL,
    /** 条件节点：封闭算子求值，只走 TRUE/FALSE 一条分支。 */
    CONDITION
}
