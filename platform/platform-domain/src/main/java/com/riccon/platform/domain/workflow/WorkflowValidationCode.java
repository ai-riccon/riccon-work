package com.riccon.platform.domain.workflow;

/**
 * 草稿校验问题码（保存/发布/运行前三处复用）。
 *
 * <p>枚举名与契约 {@code WorkflowValidationCode} 一致；服务端逐项返回问题与修正入口，UI 必须把画布与
 * 列表/表单的校验结果显示为同一份问题列表（ADR-0024 第 4.1/11.1 节，AC-02）。
 */
public enum WorkflowValidationCode {
    /** 图存在环。 */
    GRAPH_CYCLE,
    /** 缺少 START。 */
    MISSING_START,
    /** 多个 START。 */
    MULTIPLE_START,
    /** 缺少 END。 */
    MISSING_END,
    /** 孤立节点。 */
    ORPHAN_NODE,
    /** 入边超过 1（不允许合并/并行）。 */
    INDEGREE_EXCEEDED,
    /** 边非法（源/目标/分支/数量）。 */
    EDGE_INVALID,
    /** 条件分支配置非法。 */
    CONDITION_BRANCH_INVALID,
    /** 节点必填配置缺失。 */
    REQUIRED_CONFIG,
    /** 节点数超过上限。 */
    NODE_LIMIT,
    /** 边数超过上限。 */
    EDGE_LIMIT,
    /** 模型不可用或不在 REPORT 路由链。 */
    MODEL_UNAVAILABLE,
    /** 工具未授权。 */
    TOOL_NOT_AUTHORIZED,
    /** 工具停用。 */
    TOOL_DISABLED,
    /** 审批节点配置非法。 */
    APPROVAL_CONFIG,
    /** 无可用审批人。 */
    NO_APPROVER,
    /** 结束节点结果来源非法。 */
    RESULT_INVALID,
    /** 模板非法。 */
    TEMPLATE_INVALID
}
