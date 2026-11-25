package com.riccon.worker.workflow.node.domain;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * END 节点组装出的最终结果内容。
 *
 * <p>引擎负责把本对象序列化为 {@code workflow_run.result_json} 并执行 5 MiB 上限检查；
 * {@code sizeBytes} 是 {@code text} 的 UTF-8 字节数，不包含 JSON 包装开销
 * （ADR-0024 §3.4，契约 {@code WorkflowResult.sizeBytes}）。
 *
 * @param format 结果格式
 * @param text 结果文本
 */
public record WorkflowResultPayload(WorkflowResultFormat format, String text) {

    /** 校验格式与文本。 */
    public WorkflowResultPayload {
        Objects.requireNonNull(format, "format must not be null");
        text = text == null ? "" : text;
    }

    /**
     * 返回结果文本 UTF-8 字节数。
     *
     * @return 字节数
     */
    public int sizeBytes() {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
