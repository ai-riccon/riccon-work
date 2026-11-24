package com.riccon.api.workflow.domain;

/**
 * 模型节点的输出形态。
 *
 * <p>契约 {@code WorkflowNodeConfig.outputKind} 是封闭枚举：{@link #TEXT} 输出自由文本，
 * {@link #CLASSIFICATION} 必须命中节点配置的 {@code labels} 之一。M06 不支持其他输出形态，
 * 未知值在保存/发布/运行三处拒绝（ADR-0024 第 4.4 节）。
 */
public enum WorkflowGraphOutputKind {
    /** 自由文本输出。 */
    TEXT,
    /** 分类标签输出；运行结果必须命中配置的标签集合。 */
    CLASSIFICATION
}
