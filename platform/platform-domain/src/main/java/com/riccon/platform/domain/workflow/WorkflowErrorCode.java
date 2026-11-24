package com.riccon.platform.domain.workflow;

/**
 * 运行/节点的异步稳定错误码。
 *
 * <p>这些是响应字段与 UI 文案使用的稳定码，不是 HTTP 状态码；HTTP 错误仍用
 * {@code RICCON-WF-400/409/429/503}。枚举名与契约 {@code WorkflowErrorCode} 完全一致，Worker 写库与
 * API 读取共用同一词汇（ADR-0024 第 7.3 节）。{@link #WF_INTERNAL} 是 {@code claim_attempts} 耗尽或
 * 不可恢复内部错误的终态，必须保证 {@code POST /retry} 可用。
 */
public enum WorkflowErrorCode {
    /** 图结构非法（环、入边、节点类型、范围等）。 */
    WF_INVALID_GRAPH,
    /** 节点数超过部署上限。 */
    WF_NODE_LIMIT,
    /** 单运行节点尝试步数超过上限。 */
    WF_STEP_LIMIT,
    /** 运行执行超时（不含审批等待）。 */
    WF_RUN_TIMEOUT,
    /** 单节点超时。 */
    WF_NODE_TIMEOUT,
    /** 模型不可用或不在 REPORT 路由链。 */
    WF_MODEL_UNAVAILABLE,
    /** 模型分类输出不在标签枚举内。 */
    WF_MODEL_OUTPUT_INVALID,
    /** 检索失败。 */
    WF_RETRIEVAL_FAILED,
    /** 条件求值失败；不默认分支。 */
    WF_CONDITION_INVALID,
    /** 工具未授权、停用或参数校验失败。 */
    WF_TOOL_NOT_AUTHORIZED,
    /** 工具结果超过单次上限。 */
    WF_TOOL_RESULT_LIMIT,
    /** 前置审批缺失或未绑定。 */
    WF_APPROVAL_REQUIRED,
    /** 无可用审批人；运行保持等待。 */
    WF_NO_APPROVER,
    /** 审批拒绝。 */
    WF_APPROVAL_REJECTED,
    /** 工具调用已有 STARTED 无终态，外部副作用未知。 */
    WF_SIDE_EFFECT_UNKNOWN,
    /** 审批绑定漂移（版本/节点/参数/plan 哈希不一致）。 */
    WF_PLAN_DRIFT,
    /** 运行结果超过部署上限。 */
    WF_RESULT_LIMIT,
    /** 编排开关关闭。 */
    WF_FEATURE_DISABLED,
    /** 运行被取消。 */
    WF_CANCELLED,
    /** 不可恢复内部错误或领取次数耗尽。 */
    WF_INTERNAL
}
