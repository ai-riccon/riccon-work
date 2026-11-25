package com.riccon.worker.workflow.node.domain;

/**
 * 最终结果格式（与契约 {@code WorkflowResult.format} 一致）。
 */
public enum WorkflowResultFormat {
    /** 纯文本。 */
    TEXT,
    /** Markdown。 */
    MARKDOWN,
    /** JSON 文本。 */
    JSON
}
