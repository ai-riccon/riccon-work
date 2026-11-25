package com.riccon.worker.workflow.engine.domain;

import com.riccon.platform.domain.workflow.WorkflowConditionSource;
import com.riccon.platform.domain.workflow.WorkflowConditionValueType;
import java.util.Objects;

/**
 * 条件节点左值（Worker 只读副本）。
 *
 * <p>{@code source=NODE_OUTPUT} 时必须给出上游节点 key；{@code path} 只允许受控字段
 * （{@code hitCount}/{@code classificationLabel}/{@code decision}/{@code status}），
 * 不解析任意表达式（ADR-0024 §4.5）。
 *
 * @param source 数据来源
 * @param nodeId 上游节点 key；运行输入来源为空
 * @param path 字段名
 * @param valueType 声明类型
 */
public record WorkflowGraphConditionSource(
        WorkflowConditionSource source, String nodeId, String path, WorkflowConditionValueType valueType) {

    /** 校验必填字段。 */
    public WorkflowGraphConditionSource {
        Objects.requireNonNull(source, "condition source must not be null");
        Objects.requireNonNull(path, "condition path must not be null");
        Objects.requireNonNull(valueType, "condition value type must not be null");
    }
}
