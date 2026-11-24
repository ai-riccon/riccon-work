package com.riccon.platform.domain.workflow;

/**
 * 模板来源类型。
 *
 * <p>{@link #BUILT_IN} 为全局内置目录（只读）；{@link #TENANT} 为租户内用户模板（M06b 启用）。
 * M06 只读取内置模板，用户模板表无读写路径（ADR-0024 第 3.3.7/12 节）。
 */
public enum WorkflowTemplateSource {
    /** 全局内置模板。 */
    BUILT_IN,
    /** 租户内用户模板。 */
    TENANT
}
