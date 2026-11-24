package com.riccon.api.workflow.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.riccon.platform.domain.workflow.WorkflowConditionSource;
import com.riccon.platform.domain.workflow.WorkflowConditionValueType;
import java.util.Objects;

/**
 * 条件节点左值。
 *
 * <p>对应契约 {@code WorkflowConditionSource}。{@code source=NODE_OUTPUT} 时必须给出上游节点 ID；
 * {@code path} 只允许运行输入字段或受控输出字段（{@code hitCount}/{@code classificationLabel}/
 * {@code decision}/{@code status}），不解析任意表达式（ADR-0024 第 4.5 节）。
 *
 * @param source 数据来源
 * @param nodeId 上游节点 ID；{@code source=NODE_OUTPUT} 时必填
 * @param path 字段路径，最多 64 字符
 * @param valueType 左值类型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowGraphConditionSource(
        WorkflowConditionSource source, String nodeId, String path, WorkflowConditionValueType valueType) {

    /** 路径最大长度，与契约一致。 */
    public static final int MAX_PATH_LENGTH = 64;

    /** 校验必填字段与路径长度。 */
    public WorkflowGraphConditionSource {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(valueType, "valueType must not be null");
        if (path.isBlank() || path.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("path length must be between 1 and " + MAX_PATH_LENGTH);
        }
        if (nodeId != null && nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank when present");
        }
    }
}
