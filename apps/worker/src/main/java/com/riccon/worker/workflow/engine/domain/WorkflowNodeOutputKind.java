package com.riccon.worker.workflow.engine.domain;

/**
 * 模型节点输出形态（与契约 {@code WorkflowNodeConfig.outputKind} 一致）。
 *
 * <p>API 侧同名枚举位于 {@code com.riccon.api.workflow.domain}，部署物之间不能互相依赖；
 * Worker 解析发布快照时使用本枚举，未知值在运行入口拒绝（ADR-0024 §4.4/§4.1）。
 */
public enum WorkflowNodeOutputKind {
    /** 自由文本输出。 */
    TEXT,
    /** 分类标签输出；必须命中节点配置的 {@code labels}。 */
    CLASSIFICATION
}
