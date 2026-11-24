package com.riccon.api.workflow.persistence;

import java.util.Objects;
import java.util.UUID;

/**
 * 全局内置模板行（只读）。
 *
 * <p>{@code workflow_template} 是 ADR-0012 §4 的全局目录例外（由 ADR-0024 §3.3.7 扩展），不带
 * {@code tenant_id}；本行只承载复制草稿所需的名称、说明与图 JSON，不读取凭据或用户内容。
 *
 * @param id 模板 ID
 * @param name 模板名
 * @param description 模板说明
 * @param graphJson 图 JSON
 * @param version 模板版本
 */
public record WorkflowDefinitionTemplateRow(UUID id, String name, String description, String graphJson, int version) {

    /** 校验必填字段。 */
    public WorkflowDefinitionTemplateRow {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(graphJson, "graphJson must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
    }
}
