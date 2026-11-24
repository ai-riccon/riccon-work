package com.riccon.api.workflow.definition.domain;

import com.riccon.api.workflow.domain.WorkflowGraph;
import java.util.Objects;
import java.util.UUID;

/**
 * 可用于复制为草稿的内置模板快照。
 *
 * <p>M06 只读取全局 {@code workflow_template} 的 {@code BUILT_IN} 目录；用户模板在 M06b 启用。
 * 复制只读取模板，不修改源模板（ADR-0024 §3.3.7/§12，AC-10）。
 *
 * @param id 模板 ID
 * @param name 模板名
 * @param description 模板说明
 * @param graph 模板图
 * @param version 模板版本
 * @param sourceType 模板来源类型
 */
public record WorkflowTemplateSnapshot(
        UUID id,
        String name,
        String description,
        WorkflowGraph graph,
        int version,
        WorkflowDefinitionSourceType sourceType) {

    /** 校验必填字段。 */
    public WorkflowTemplateSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(graph, "graph must not be null");
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
    }
}
