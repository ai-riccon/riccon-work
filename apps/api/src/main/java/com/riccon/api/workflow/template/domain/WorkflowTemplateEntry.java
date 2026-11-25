package com.riccon.api.workflow.template.domain;

import com.riccon.platform.domain.workflow.WorkflowTemplateVisibility;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 内置模板目录条目（只读）。
 *
 * <p>M06 只暴露全局 {@code workflow_template} 中 {@code BUILT_IN} 且启用的模板；用户模板在 M06b 启用，
 * 本记录不承载租户、所有者或升级状态（ADR-0024 §3.3.7/§12，AC-10）。
 *
 * @param id 模板 ID（固定 UUID，升级不改变）
 * @param name 展示名
 * @param scenario 适用场景
 * @param description 说明；可为空
 * @param visibility 可见范围；M06 恒为 {@link WorkflowTemplateVisibility#BUILT_IN}
 * @param version 模板版本
 * @param updatedAt 最近更新时间（UTC）
 */
public record WorkflowTemplateEntry(
        UUID id,
        String name,
        String scenario,
        String description,
        WorkflowTemplateVisibility visibility,
        int version,
        Instant updatedAt) {

    /** 校验必填字段与版本下限。 */
    public WorkflowTemplateEntry {
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
