package com.riccon.api.workflow.history.domain;

/**
 * 运行最终结果格式（与契约 {@code WorkflowResult.format} 一致）。
 *
 * <p>API 侧独立声明该枚举：Worker 的 {@code WorkflowResultFormat} 属于另一个部署物，不能互相依赖；
 * 两端都以契约枚举为唯一词汇（ADR-0024 §3.4/§7.3）。
 */
public enum WorkflowResultFormat {
    /** 纯文本。 */
    TEXT,
    /** Markdown。 */
    MARKDOWN,
    /** JSON 文本。 */
    JSON
}
