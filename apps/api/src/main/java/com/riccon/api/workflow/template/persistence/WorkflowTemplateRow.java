package com.riccon.api.workflow.template.persistence;

import com.riccon.platform.domain.workflow.WorkflowTemplateVisibility;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 全局内置模板目录行（只读）。
 *
 * <p>{@code workflow_template} 是 ADR-0012 §4 全局表例外清单的 M06 扩展（ADR-0024 §3.3.7），不带
 * {@code tenant_id}；本行只承载列表展示字段，不读取图 JSON，也不读取任何租户或用户内容。
 *
 * @param id 模板 ID
 * @param name 展示名
 * @param scenario 适用场景
 * @param description 说明；可为空
 * @param visibility 可见范围
 * @param version 模板版本
 * @param updatedAt 最近更新时间（UTC）
 */
public record WorkflowTemplateRow(
        UUID id,
        String name,
        String scenario,
        String description,
        WorkflowTemplateVisibility visibility,
        int version,
        Instant updatedAt) {

    /** 校验必填字段与版本下限。 */
    public WorkflowTemplateRow {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(scenario, "scenario must not be null");
        Objects.requireNonNull(visibility, "visibility must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
    }
}
