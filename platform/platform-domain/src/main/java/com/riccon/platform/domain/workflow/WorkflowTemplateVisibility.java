package com.riccon.platform.domain.workflow;

/**
 * 模板可见范围。
 *
 * <p>内置模板固定 {@link #BUILT_IN}，避免被误读为 {@code PRIVATE}/{@code TENANT}；用户模板默认
 * {@link #PRIVATE}，M06b 才开放 {@link #TENANT} 共享（ADR-0024 第 12 节，契约 F-018）。
 */
public enum WorkflowTemplateVisibility {
    /** 全局内置目录；只读。 */
    BUILT_IN,
    /** 仅创建人可见；M06b 用户模板默认值。 */
    PRIVATE,
    /** 租户内共享；M06b 启用。 */
    TENANT
}
